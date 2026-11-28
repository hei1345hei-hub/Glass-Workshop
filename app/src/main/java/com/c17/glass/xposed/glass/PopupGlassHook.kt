package com.c17.glass.xposed.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.findField
import com.c17.glass.xposed.statusbar.findMethod
import com.c17.glass.xposed.statusbar.findMethodsNamed
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ColorOS 17 悬浮消息弹窗（Heads-Up 通知横幅 + FullScreenBanner 简洁横幅）液态玻璃渲染器。
 *
 * 原理说明：
 * ColorOS 17 对 Heads-Up 弹窗切换为 `ViewBlurProxy.BlurTypeMotion`，底层改用 AOSP `BackgroundBlurDrawable`
 * 走 SurfaceFlinger 窗后模糊，不经过 `com.oplus.posteffect.agsl.DrawableShader`。
 * 本 Hook 在启用「全局生效」时：
 *  1. 拦截 `ViewBlurManager.blurForHeadsUp` 与 `FullScreenBannerContainer`，包装并返回自绘代理 Drawable；
 *  2. 依托系统原生 `BlurDrawable(appContext, surfaceControl)` 与 `BlurDrawableManager.updateCapture`
 *     获取实时背景采集纹理；
 *  3. 使用 Kyant0/AndroidLiquidGlass 同款 circleMap 凸透镜折射方程 + G2 连续曲率超椭圆 SDF +
 *     三次 B 样条平滑重采样，在 `RuntimeShader` 内实时绘制弹窗折射玻璃体，并按日/夜模式自适应磨砂底色；
 *  4. 在外层 `ViewGroup.dispatchDraw` / `FullScreenBannerContainer.dispatchDraw` 叠加高光边缘描边与柔和阴影，
 *     禁用与改回原生后即刻无痕回退至原始 `BackgroundBlurDrawable`。
 */
internal object PopupGlassHook {

    private const val TAG = "popup-glass"

    data class PopupConfig(
        val enabled: Boolean,
        val refractionAmt: Float,
        val bandRaw: Float,
        val blurRadiusPx: Int,
        val rimStrength: Float,
        val cornerScale: Float,
        val saturation: Float,
        val revision: Long
    )

    private const val CLS_VIEW_BLUR_MANAGER = "com.oplus.systemui.notification.blur.ViewBlurManager"
    private const val CLS_VIEW_BLUR_PROXY = "com.oplusos.systemui.common.blurability.ViewBlurProxy"
    private const val CLS_BASE_DRAWABLE = "com.oplus.posteffect.drawable.BaseDrawable"
    private const val CLS_CONTINUOUS_BLUR = "com.oplus.posteffect.drawable.ContinuousBlurDrawable"
    private const val CLS_BLEND_DRAWABLE = "com.oplus.posteffect.drawable.BlendDrawable"
    private const val CLS_BLUR_DRAWABLE = "com.oplus.posteffect.BlurDrawable"
    private const val CLS_BLUR_PARAM = "com.oplus.posteffect.BlurParam"
    private const val CLS_BLUR_MANAGER = "com.oplus.posteffect.manager.BlurDrawableManager"

    private const val CLS_FULLSCREEN_BANNER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner"
    private const val CLS_FULLSCREEN_BANNER_CONTAINER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer"
    private const val CLS_FULLSCREEN_BG_VIEW =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBackgroundView"
    private const val CLS_HEADS_UP_LAYOUT =
        "com.oplus.systemui.notification.headsup.windowframe.HeadsUpLayout"
    private const val CLS_NOTIF_BG_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val CLS_EXPANDABLE_ROW =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val CLS_SHADOW_EDGE =
        "com.oplus.systemui.notification.material.HeadsUpShadowEdgeController"
    private const val CLS_SPOT_LIGHT =
        "com.oplus.systemui.notification.material.NotificationSpotLightDelegate"
    private const val CLS_FULLSCREEN_HELPER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.FullScreenBannerHelper"
    private const val CLS_HEADS_UP_ANIMATOR =
        "com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpAnimator"

    private const val CAPTURE_SETTLE_WINDOW_MS = 480L
    private const val STARTUP_WAIT_WINDOW_MS = 1_500L
    private const val CAPTURE_RETRY_DELAY_MS = 1_000L
    private const val CAPTURE_INFLIGHT_TIMEOUT_MS = 160L
    private const val HANDOFF_HOLD_MS = 900L

    /**
     * Kyant0/AndroidLiquidGlass circleMap inward lens + G2 continuous superellipse SDF +
     * cubic B-spline scene reconstruction.
     */
    private const val SHADER_POPUP_GLASS = """
uniform shader uScene;
uniform float2 uSize;
uniform float uRadius;
uniform float uHeight;
uniform float uAmount;
uniform float uTintAlpha;
uniform float uSaturation;
uniform float3 uTint;
uniform float uAlpha;
uniform float2 uTouch;
uniform float uTouchPress;
uniform float3 uInverse0;
uniform float3 uInverse1;
uniform float3 uInverse2;

float lp25(float2 v) {
    v = max(v, float2(0.0));
    return pow(max(dot(v * v, sqrt(v)), 0.00000001), 0.4);
}

float3 g2(float2 p) {
    float r = max(uRadius, 0.0001);
    float2 halfSize = uSize * 0.5;
    float2 s = sign(p - halfSize);
    float2 q = abs(p - halfSize) - (halfSize - r);
    if (min(q.x, q.y) <= 0.0) {
        if (q.x > q.y) return float3(q.x - r, s.x, 0.0);
        return float3(q.y - r, 0.0, s.y);
    }
    float inside = lp25(q / r) - 1.0;
    float2 v = q / max(lp25(q / r), 0.0001);
    if (max(q.x, q.y) < r * 0.35) {
        v = q.x >= q.y ? float2(r, 0.0) : float2(0.0, r);
    }
    for (int i = 0; i < 4; i++) {
        float2 z = max(v / r, float2(0.0));
        float2 n = z * sqrt(z);
        n = n / max(length(n), 0.00001);
        float2 t = float2(-n.y, n.x);
        v = max(v - t * dot(v - q, t), float2(0.0));
        v = v / max(lp25(v / r), 0.0001);
    }
    float2 z = max(v / r, float2(0.0));
    float2 normal = z * sqrt(z);
    normal = normal / max(length(normal), 0.00001);
    float distance = length(q - v) * (inside < 0.0 ? -1.0 : 1.0);
    return float3(distance, normal * s);
}

float4 cubicWeights(float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return float4(
        1.0 - 3.0 * t + 3.0 * t2 - t3,
        4.0 - 6.0 * t2 + 3.0 * t3,
        // 标准三次 B 样条：四项权重之和恒为 1。
        // 第三项若误写为 -t³，亮度会随每个纹理像素的 fract 坐标周期变化，产生细横线/网格纹。
        1.0 + 3.0 * t + 3.0 * t2 - 3.0 * t3,
        t3
    ) / 6.0;
}

half4 sampleSceneCubic(float2 uv) {
    float2 p = uv - 0.5;
    float2 base = floor(p);
    float4 wx = cubicWeights(fract(p.x));
    float4 wy = cubicWeights(fract(p.y));

    // 将相邻两个 texel 合并成一次硬件双线性采样，16 taps -> 4 taps。
    // 合并后的权重和采样坐标与原三次 B 样条完全等价。
    float2 g0 = float2(wx.x + wx.y, wy.x + wy.y);
    float2 g1 = float2(wx.z + wx.w, wy.z + wy.w);
    float2 offset0 = float2(wx.y, wy.y) / g0 - 0.5;
    float2 offset1 = float2(wx.w, wy.w) / g1 + 1.5;

    float4 c00 = float4(uScene.eval(base + float2(offset0.x, offset0.y)));
    float4 c10 = float4(uScene.eval(base + float2(offset1.x, offset0.y)));
    float4 c01 = float4(uScene.eval(base + float2(offset0.x, offset1.y)));
    float4 c11 = float4(uScene.eval(base + float2(offset1.x, offset1.y)));
    float4 color = c00 * (g0.x * g0.y)
                 + c10 * (g1.x * g0.y)
                 + c01 * (g0.x * g1.y)
                 + c11 * (g1.x * g1.y);
    float weightSum = (g0.x + g1.x) * (g0.y + g1.y);
    return half4(clamp(color / max(weightSum, 0.00001), float4(0.0), float4(1.0)));
}

float circleMap(float x) {
    return 1.0 - sqrt(max(0.0, (1.0 - x) * (1.0 + x)));
}

half4 main(float2 p) {
    float3 geom = g2(p);
    float depth = max(-geom.x, 0.0);
    float x = uHeight > 0.5 ? clamp(1.0 - depth / max(uHeight, 1.0), 0.0, 1.0) : 0.0;
    float displacement = uAmount * circleMap(x);
    float2 sourceLocal = p - displacement * geom.yz;

    float side = max(min(uSize.x, uSize.y), 1.0);
    float2 contact = (p - uTouch * uSize) / side;
    float dist = length(contact);
    float2 dir = contact / max(dist, 0.001);
    float dent = uTouchPress * exp(-dist * dist * 9.0);
    sourceLocal += dir * side * dent * 0.016 * clamp(uAmount / 96.0, 0.0, 2.0);

    float3 h = float3(sourceLocal, 1.0);
    float divisor = dot(uInverse2, h);
    if (abs(divisor) < 0.00001) divisor = divisor < 0.0 ? -0.00001 : 0.00001;
    float2 uv = float2(dot(uInverse0, h), dot(uInverse1, h)) / divisor;

    half4 captured = sampleSceneCubic(uv);
    float3 color = float3(captured.rgb) / max(float(captured.a), 0.0001);
    float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(float3(luminance), color, uSaturation), 0.0, 1.0);
    color = mix(color, uTint, uTintAlpha);
    float edgeSheen = clamp(dent * 0.025 + pow(x, 2.2) * 0.055, 0.0, 0.10);
    color = mix(color, float3(1.0), edgeSheen);
    return half4(half3(color * uAlpha), half(uAlpha));
}
"""

    /** 弹窗顶部外阴影 + G2 边框方向性高光轮廓着色器。 */
    private const val SHADER_POPUP_CONTOUR = """
uniform float2 uSize;
uniform float uRadius;
uniform float uAlpha;
uniform float uRimStrength;

float lp25(float2 v) {
    v = max(v, float2(0.0));
    return pow(max(dot(v * v, sqrt(v)), 0.00000001), 0.4);
}

float3 g2(float2 p) {
    float r = max(uRadius, 0.0001);
    float2 halfSize = uSize * 0.5;
    float2 s = sign(p - halfSize);
    float2 q = abs(p - halfSize) - (halfSize - r);
    if (min(q.x, q.y) <= 0.0) {
        if (q.x > q.y) return float3(q.x - r, s.x, 0.0);
        return float3(q.y - r, 0.0, s.y);
    }
    float inside = lp25(q / r) - 1.0;
    float2 v = q / max(lp25(q / r), 0.0001);
    if (max(q.x, q.y) < r * 0.35) {
        v = q.x >= q.y ? float2(r, 0.0) : float2(0.0, r);
    }
    for (int i = 0; i < 4; i++) {
        float2 z = max(v / r, float2(0.0));
        float2 n = z * sqrt(z);
        n = n / max(length(n), 0.00001);
        float2 t = float2(-n.y, n.x);
        v = max(v - t * dot(v - q, t), float2(0.0));
        v = v / max(lp25(v / r), 0.0001);
    }
    float2 z = max(v / r, float2(0.0));
    float2 normal = z * sqrt(z);
    normal = normal / max(length(normal), 0.00001);
    float distance = length(q - v) * (inside < 0.0 ? -1.0 : 1.0);
    return float3(distance, normal * s);
}

half4 main(float2 p) {
    float3 geom = g2(p);
    float d = geom.x;

    // 柔和外阴影
    float outside = smoothstep(-0.6, 0.8, d);
    float dDrop = max(g2(p - float2(0.0, 4.0)).x, 0.0) / 8.5;
    float dTight = max(g2(p - float2(0.0, 1.0)).x, 0.0) / 1.4;
    float shadowAlpha = (0.15 * exp(-0.5 * dDrop * dDrop) + 0.22 * exp(-0.5 * dTight * dTight)) * outside * uAlpha;

    // 内部边缘方向性高光
    float rimWidth = 2.4;
    float inBand = smoothstep(-rimWidth - 0.6, -rimWidth + 0.4, d) * (1.0 - smoothstep(-0.4, 0.25, d));
    float toward = dot(geom.yz, normalize(float2(-0.45, -0.89)));
    float bright = (0.08 + 0.62 * max(toward, 0.0)) * inBand * uRimStrength;
    float bevel = (0.06 + 0.26 * max(-toward, 0.0)) * inBand * uRimStrength;
    float rimAlpha = min(bright + bevel, 0.82) * uAlpha;
    float rimLum = rimAlpha > 0.0001 ? bright / max(bright + bevel, 0.0001) : 0.0;

    float outAlpha = clamp(rimAlpha + shadowAlpha * (1.0 - rimAlpha), 0.0, 1.0);
    float3 outRgb = float3(rimLum) * rimAlpha;
    return half4(half3(outRgb), half(outAlpha));
}
"""

    private val viewToState = Collections.synchronizedMap(WeakHashMap<View, PopupState>())
    private val nativeToState = Collections.synchronizedMap(WeakHashMap<Any, PopupState>())
    private val activeStateCount = AtomicInteger(0)

    private val managersLock = Any()
    private val sharedManagers = WeakHashMap<ClassLoader, Any>()

    private val loggedFirstBind = AtomicBoolean(false)
    private val loggedFirstCapture = AtomicBoolean(false)

    private lateinit var configProvider: () -> PopupConfig

    // Reflected SystemUI / posteffect handles
    private var mProxyGetView: Method? = null
    private var mProxyGetBlurType: Method? = null
    private var mProxyGetBlurEnabled: Method? = null
    private var mProxyGetBlurAmount: Method? = null
    private var mProxyGetBlurConfig: Method? = null
    private var mBlurConfigGetCornerRadius: Method? = null
    private var mViewBlurMgrGetProxy: Method? = null

    private var clsBlurDrawable: Class<*>? = null
    private var ctorBlurDrawable: Constructor<*>? = null
    private var clsBlurParam: Class<*>? = null
    private var ctorBlurParamIntInt: Constructor<*>? = null
    private var mBlurParamGetBase: Method? = null
    private var mBaseParamSetZoomScale: Method? = null
    private var mBlurParamSetCaptureRegion: Method? = null
    private var mBlurParamSetBlurRadius: Method? = null
    private var clsBlurManager: Class<*>? = null
    private var ctorBlurManager: Constructor<*>? = null
    private var mManagerUpdateCapture: Method? = null
    private var fContinuousManagerDelegate: Field? = null
    private var mGetBlurDrawableManager: Method? = null
    private var mContinuousAddBlurDrawable: Method? = null
    private var mContinuousRemoveBlurDrawable: Method? = null

    private var fBaseRenderNode: Field? = null
    private var mBaseGetDataLock: Method? = null
    private var mBaseSetEnableBlurShader: Method? = null
    private var mBaseSetPlaceHolderColor: Method? = null
    private var mBaseSetPosThresholdPx: Method? = null
    private var mBaseSetBlurParamsSync: Method? = null
    private var mBaseInvalidate: Method? = null
    private var mBaseSetContentDirty: Method? = null
    private var mBaseGetDrawableId: Method? = null
    private var mBaseGetDrawingImageInfo: Method? = null
    private var mBaseGetPendingImageInfo: Method? = null
    private var mImageInfoGetImage: Method? = null
    private var mImageRefGet: Method? = null
    private var mContentImageRequire: Method? = null
    private var mContentImageGet: Method? = null
    private var fContentImageRecycled: Field? = null
    private var mBaseGetBitmapMatrix: Method? = null
    private var mBaseGetDrawingBounds: Method? = null
    private var mBaseGetPaintAlpha: Method? = null
    private var mBaseGetPaint: Method? = null
    private var mPaintReset: Method? = null

    private var mViewGetViewRootImpl: Method? = null
    private var mRootGetSurfaceControl: Method? = null
    private var mSurfaceIsValid: Method? = null
    private var mSurfaceGetLayerId: Method? = null

    private var fBannerInContainer: Field? = null
    private var fBannerBgInContainer: Field? = null
    private var fBannerBlurMgrInContainer: Field? = null
    private var fBannerProxyInContainer: Field? = null
    private var fRowBackgroundNormal: Field? = null
    private var fRowBgCornerRadii: Field? = null
    private var mGetViewBlurManager: Method? = null

    private val clsSurfaceControl: Class<*>? by lazy {
        runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull()
    }

    fun install(env: HookEnv, provider: () -> PopupConfig) {
        configProvider = provider
        resolveHandles(env)
        HookLog.info(
            "[$TAG] 句柄：ContentImage=${mContentImageRequire != null} " +
                "setBlurParamsSync=${mBaseSetBlurParamsSync != null} " +
                "contentDirty=${mBaseSetContentDirty != null} " +
                "managerInject=${fContinuousManagerDelegate != null && ctorBlurManager != null} " +
                "prime=${mContinuousAddBlurDrawable != null} updateCapture=${mManagerUpdateCapture != null}"
        )

        env.findClass(CLS_VIEW_BLUR_MANAGER)?.let { mgrCls ->
            mgrCls.findMethodsNamed("blurForHeadsUp").forEach { m ->
                env.hookAfter(m, "$TAG:ViewBlurManager.blurForHeadsUp") { chain ->
                    val bgView = chain.args.firstOrNull { it is View } as? View ?: return@hookAfter
                    val proxy = runCatching { mViewBlurMgrGetProxy?.invoke(chain.thisObject, bgView) }.getOrNull()
                    if (proxy != null) {
                        bindPopupView(bgView, proxy)
                    }
                }
            }
            mgrCls.findMethodsNamed("cancelBlurForHeadsUp").forEach { m ->
                env.hookBefore(m, "$TAG:ViewBlurManager.cancelBlurForHeadsUp") { chain ->
                    val bgView = chain.args.firstOrNull { it is View } as? View ?: return@hookBefore
                    val proxy = runCatching { mViewBlurMgrGetProxy?.invoke(chain.thisObject, bgView) }.getOrNull()
                    val state = viewToState[bgView] ?: return@hookBefore
                    if (proxy == null || state.proxy.get() === proxy) {
                        if (isNotificationBackgroundView(bgView)) {
                            state.onHeadsUpCancel(proxy)
                        } else if (!isFullScreenBannerView(bgView)) {
                            detachState(bgView, proxy)
                        }
                    }
                }
            }
            mgrCls.findMethodsNamed("releaseBlurProxyForView").forEach { m ->
                env.hookBefore(m, "$TAG:ViewBlurManager.releaseBlurProxyForView") { chain ->
                    val bgView = chain.args.firstOrNull { it is View } as? View ?: return@hookBefore
                    val proxy = runCatching { mViewBlurMgrGetProxy?.invoke(chain.thisObject, bgView) }.getOrNull()
                    detachState(bgView, proxy)
                }
            }
        }

        env.findClass(CLS_VIEW_BLUR_PROXY)?.let { proxyCls ->
            proxyCls.findMethodsNamed("verifyDrawable").forEach { m ->
                env.hookReplace(m, "$TAG:ViewBlurProxy.verifyDrawable") { chain ->
                    val drawable = chain.args.firstOrNull() as? PopupGlassDrawable
                    if (drawable != null) {
                        val state = drawable.state
                        if (state.active && state.proxy.get() === chain.thisObject && state.isEligible()) {
                            return@hookReplace true
                        }
                    }
                    chain.proceed()
                }
            }
            proxyCls.findMethodsNamed("setBlurEnabled").forEach { m ->
                env.hookAfter(m, "$TAG:ViewBlurProxy.setBlurEnabled") { chain ->
                    val proxy = chain.thisObject ?: return@hookAfter
                    val view = runCatching { mProxyGetView?.invoke(proxy) as? View }.getOrNull() ?: return@hookAfter
                    val state = viewToState[view] ?: return@hookAfter
                    if (state.proxy.get() === proxy) {
                        if (state.isEligible()) {
                            state.startFrames()
                        } else {
                            state.releaseSdk()
                        }
                    }
                }
            }
        }

        env.findClass(CLS_FULLSCREEN_BANNER_CONTAINER)?.let { fsCls ->
            fsCls.findMethodsNamed("onViewAttachedToWindow").forEach { m ->
                env.hookAfter(m, "$TAG:FullScreenBannerContainer.onAttached") { chain ->
                    reconcileBannerContainer(chain.thisObject)
                }
            }
            fsCls.findMethodsNamed("onViewAdded").forEach { m ->
                env.hookAfter(m, "$TAG:FullScreenBannerContainer.onViewAdded") { chain ->
                    reconcileBannerContainer(chain.thisObject)
                }
            }
            fsCls.findMethodsNamed("dispatchDraw").forEach { m ->
                env.hookAfter(m, "$TAG:FullScreenBannerContainer.dispatchDraw") { chain ->
                    val canvas = chain.args.firstOrNull() as? Canvas ?: return@hookAfter
                    drawBannerContour(chain.thisObject, canvas)
                }
            }
            fsCls.findMethodsNamed("dispatchTouchEvent").forEach { m ->
                env.hookBefore(m, "$TAG:FullScreenBannerContainer.dispatchTouchEvent") { chain ->
                    val view = chain.thisObject as? View ?: return@hookBefore
                    val ev = chain.args.firstOrNull() as? MotionEvent ?: return@hookBefore
                    dispatchPopupTouch(view, ev)
                }
            }
        }

        env.findClass(CLS_HEADS_UP_LAYOUT)?.let { huCls ->
            listOf("addView", "onViewAdded", "onViewRemoved").forEach { name ->
                huCls.findMethodsNamed(name).forEach { m ->
                    env.hookAfter(m, "$TAG:HeadsUpLayout.$name") { chain ->
                        val layout = chain.thisObject as? ViewGroup ?: return@hookAfter
                        reconcileHeadsUpLayout(layout)
                    }
                }
            }
            huCls.findMethodsNamed("dispatchTouchEvent").forEach { m ->
                env.hookBefore(m, "$TAG:HeadsUpLayout.dispatchTouchEvent") { chain ->
                    val view = chain.thisObject as? View ?: return@hookBefore
                    val ev = chain.args.firstOrNull() as? MotionEvent ?: return@hookBefore
                    dispatchPopupTouch(view, ev)
                }
            }
        }

        env.findClass(CLS_NOTIF_BG_VIEW)?.let { bgCls ->
            bgCls.findMethodsNamed("onDraw").forEach { m ->
                env.hookBefore(m, "$TAG:NotificationBackgroundView.onDraw") { chain ->
                    val view = chain.thisObject as? View ?: return@hookBefore
                    val state = viewToState[view] ?: return@hookBefore
                    if (state.active && state.isHeadsUpOrMotionTarget()) {
                        state.wakeFromBackgroundDraw()
                    }
                }
            }
        }

        // Fast-path guarded ViewGroup.dispatchDraw：仅当屏幕上存在激活态悬浮弹窗时，检查行视图并补绘高光边框
        ViewGroup::class.java.findMethodsNamed("dispatchDraw").forEach { m ->
            env.hookAfter(m, "$TAG:ViewGroup.dispatchDraw") { chain ->
                if (activeStateCount.get() <= 0) return@hookAfter
                val vg = chain.thisObject as? ViewGroup ?: return@hookAfter
                if (vg.javaClass.name != CLS_EXPANDABLE_ROW) return@hookAfter
                val canvas = chain.args.firstOrNull() as? Canvas ?: return@hookAfter
                drawHeadsUpRowContour(vg, canvas)
            }
        }

        // 用自绘 AGSL 玻璃替换 Owned capture BlurDrawable 的绘制管线
        env.findClass(CLS_BASE_DRAWABLE)?.let { bdCls ->
            bdCls.findMethodsNamed("drawBlurShader").forEach { m ->
                env.hookReplace(m, "$TAG:BaseDrawable.drawBlurShader") { chain ->
                    val state = nativeToState[chain.thisObject] ?: return@hookReplace chain.proceed()
                    val canvas = chain.args.firstOrNull() as? Canvas ?: return@hookReplace null
                    runCatching {
                        state.paint(canvas)
                    }.onFailure { t ->
                        state.failed = true
                        state.hasContent = false
                        state.invalidate()
                        HookLog.error("[$TAG] drawBlurShader 渲染失败", t)
                    }
                    null
                }
            }
            bdCls.findMethodsNamed("setPendingImageInfo").forEach { m ->
                env.hookAfter(m, "$TAG:BaseDrawable.setPendingImageInfo") { chain ->
                    val state = nativeToState[chain.thisObject] ?: return@hookAfter
                    state.onCaptureDelivered()
                }
            }
        }

        env.findClass(CLS_BLEND_DRAWABLE)?.let { blendCls ->
            blendCls.findMethodsNamed("onDrawContent").forEach { m ->
                env.hookReplace(m, "$TAG:BlendDrawable.onDrawContent") { chain ->
                    val state = nativeToState[chain.thisObject] ?: return@hookReplace chain.proceed()
                    val canvas = chain.args.firstOrNull() as? Canvas ?: return@hookReplace chain.proceed()
                    val handled = runCatching {
                        if (state.drawStartupSlot(canvas)) {
                            val paint = mBaseGetPaint?.invoke(chain.thisObject)
                            if (paint != null) mPaintReset?.invoke(paint)
                            true
                        } else {
                            false
                        }
                    }.getOrElse { t ->
                        state.failed = true
                        state.hasContent = false
                        state.nativeWaiting = false
                        state.invalidate()
                        HookLog.error("[$TAG] onDrawContent 渲染失败", t)
                        false
                    }
                    if (handled) null else chain.proceed()
                }
            }
        }

        // 抑制原生暗色阴影外框与 spotlight，改用我们的 G2 边缘高光描边
        listOf(CLS_SHADOW_EDGE, CLS_SPOT_LIGHT).forEach { clsName ->
            env.findClass(clsName)?.let { cls ->
                cls.findMethodsNamed("draw").forEach { m ->
                    env.hookReplace(m, "$TAG:${cls.simpleName}.draw") { chain ->
                        if (activeStateCount.get() <= 0) return@hookReplace chain.proceed()
                        for (arg in chain.args) {
                            if (arg is View) {
                                val targetView = if (arg.javaClass.name == CLS_FULLSCREEN_BANNER_CONTAINER) {
                                    runCatching { fBannerInContainer?.get(arg) as? View }.getOrNull() ?: arg
                                } else {
                                    arg
                                }
                                val st = viewToState[targetView]
                                if (st != null && st.active && st.isEligible() &&
                                    (st.hasContent || clsName == CLS_SHADOW_EDGE)
                                ) {
                                    return@hookReplace null
                                }
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }

        // 退出或下滑切换时同步释放悬浮态
        listOf(CLS_HEADS_UP_ANIMATOR, CLS_FULLSCREEN_HELPER).forEach { clsName ->
            env.findClass(clsName)?.let { cls ->
                listOf("dismiss", "hideBanner", "executeBannerCollpseAnim").forEach { name ->
                    cls.findMethodsNamed(name).forEach { m ->
                        env.hookAfter(m, "$TAG:${cls.simpleName}.$name") { _ ->
                            invalidateAll()
                        }
                    }
                }
            }
        }

        HookLog.ok("[$TAG] 消息弹窗液态玻璃渲染器挂载就绪")
    }

    /** 由 [FluidGlassHook] 在 `ViewBlurProxy.getBlurDrawable` 后调起：符合弹窗条件则注入代理 Drawable。 */
    fun interceptBlurDrawable(proxy: Any, view: View, rawResult: Any?): Any? {
        val resultDrawable = rawResult as? Drawable ?: return rawResult
        if (resultDrawable is PopupGlassDrawable) return resultDrawable

        var state = viewToState[view]
        val isBanner = isFullScreenBannerView(view)
        val isHeadsUpMotion = isNotificationBackgroundView(view) && isBlurTypeMotion(proxy)

        if (state == null || !state.active || state.proxy.get() !== proxy) {
            if (isBanner || isHeadsUpMotion) {
                bindPopupView(view, proxy)
                state = viewToState[view]
            }
        }

        if (state == null || !state.active || state.proxy.get() !== proxy) {
            return rawResult
        }
        if (!state.isEligible()) {
            state.releaseSdk()
            return rawResult
        }
        state.fallback = WeakReference(resultDrawable)
        state.startFrames()
        runCatching { state.ensureSdk() }.onFailure { t ->
            state.failed = true
            state.hasContent = false
            HookLog.error("[$TAG] interceptBlurDrawable ensureSdk 失败", t)
        }
        return state.wrapper
    }

    /** 由 [FluidGlassHook] 在 `ViewBlurProxy.setBlurType` 后调起，切换为非动效模糊（如拉下通知栏）时退出弹窗态。 */
    fun onBlurTypeChanged(proxy: Any, view: View) {
        val state = viewToState[view] ?: return
        if (state.proxy.get() !== proxy) return
        if (!isFullScreenBannerView(view) && !isBlurTypeMotion(proxy)) {
            if (isNotificationBackgroundView(view)) {
                state.onHeadsUpCancel(proxy)
            } else {
                detachState(view, proxy)
            }
            return
        }
        if (isBlurTypeMotion(proxy) && isNotificationBackgroundView(view)) {
            state.onNotificationUpdate()
            state.startFrames()
            state.invalidate()
        }
    }

    fun isOwnedDrawable(target: Any?): Boolean = target != null && nativeToState.containsKey(target)

    fun onConfigChanged() {
        val snapshot = synchronized(viewToState) { viewToState.values.toList() }
        snapshot.forEach { state ->
            runCatching {
                if (!state.isEligible()) {
                    state.releaseSdk()
                } else {
                    state.updateParams()
                    state.startFrames()
                }
                state.invalidate()
            }
        }
    }

    private fun resolveHandles(env: HookEnv) {
        env.findClass(CLS_VIEW_BLUR_MANAGER)?.let { cls ->
            mViewBlurMgrGetProxy = cls.findMethod("getBlurProxyForView", View::class.java)
        }
        env.findClass(CLS_VIEW_BLUR_PROXY)?.let { cls ->
            mProxyGetView = cls.findMethod("getView")
            mProxyGetBlurType = cls.findMethod("getBlurType")
            mProxyGetBlurEnabled = cls.findMethod("getBlurEnabled")
            mProxyGetBlurAmount = cls.findMethod("getBlurAmount")
            mProxyGetBlurConfig = cls.findMethod("getBlurConfig")
        }
        env.findClass("com.oplusos.systemui.common.blurability.BlurConfig")?.let { cls ->
            mBlurConfigGetCornerRadius = cls.findMethod("getCornerRadius")
        }
        env.findClass(CLS_BLUR_DRAWABLE)?.let { cls ->
            clsBlurDrawable = cls
            val sc = clsSurfaceControl
            ctorBlurDrawable = if (sc != null) {
                cls.declaredConstructors.firstOrNull {
                    it.parameterCount == 2 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        sc.isAssignableFrom(it.parameterTypes[1])
                }?.apply { isAccessible = true }
            } else null
        }
        env.findClass(CLS_BLUR_PARAM)?.let { cls ->
            clsBlurParam = cls
            ctorBlurParamIntInt = cls.declaredConstructors.firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            mBlurParamGetBase = cls.findMethod("getBaseParam")
            mBlurParamSetCaptureRegion = cls.findMethod("setCaptureRegion", Rect::class.java)
            mBlurParamSetBlurRadius = cls.findMethod("setBlurRadius", Int::class.javaPrimitiveType!!)
        }
        env.findClass("com.oplus.posteffect.BaseBlurParam")?.let { cls ->
            mBaseParamSetZoomScale = cls.findMethod("setZoomScale", Float::class.javaPrimitiveType!!)
        }
        env.findClass(CLS_BLUR_MANAGER)?.let { cls ->
            clsBlurManager = cls
            ctorBlurManager = cls.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.apply { isAccessible = true }
            val sc = clsSurfaceControl
            if (sc != null) {
                mManagerUpdateCapture = cls.findMethod("updateCapture", sc)
            }
        }
        env.findClass(CLS_CONTINUOUS_BLUR)?.let { cls ->
            fContinuousManagerDelegate = cls.findField("blurDrawableManager\$delegate")
            mGetBlurDrawableManager = cls.findMethod("getBlurDrawableManager")
            mContinuousAddBlurDrawable = cls.findMethod("addBlurDrawable")
            mContinuousRemoveBlurDrawable = cls.findMethod("removeBlurDrawable", Boolean::class.javaPrimitiveType!!)
            if (clsBlurParam != null) {
                // 实际声明在 ContinuousBlurDrawable；从 BaseDrawable 查找只会得到 null。
                mBaseSetBlurParamsSync = cls.findMethod("setBlurParamsSync", clsBlurParam!!)
            }
        }
        env.findClass(CLS_BASE_DRAWABLE)?.let { cls ->
            fBaseRenderNode = cls.findField("renderNode")
            mBaseGetDataLock = cls.findMethod("getDataLock")
            mBaseSetEnableBlurShader = cls.findMethod("setEnableBlurShader", Boolean::class.javaPrimitiveType!!)
            mBaseSetPlaceHolderColor = cls.findMethod("setPlaceHolderColor", Int::class.javaPrimitiveType!!)
            mBaseSetPosThresholdPx = cls.findMethod("setPositionDrawThresholdPx", Int::class.javaPrimitiveType!!)
            mBaseInvalidate = cls.findMethod("invalidate")
            mBaseSetContentDirty = cls.findMethod("setContentDirty", Boolean::class.javaPrimitiveType!!)
            mBaseGetDrawableId = cls.findMethod("getDrawableId")
            mBaseGetDrawingImageInfo = cls.findMethod("getDrawingImageInfo")
            mBaseGetPendingImageInfo = cls.findMethod("getPendingImageInfo")
            mBaseGetBitmapMatrix = cls.findMethod("getBitmapMatrix")
            mBaseGetDrawingBounds = cls.findMethod("getDrawingBounds")
            mBaseGetPaintAlpha = cls.findMethod("getPaintAlpha")
            mBaseGetPaint = cls.findMethod("getPaint")
        }
        env.findClass("$CLS_BASE_DRAWABLE\$ImageInfo")?.let { cls ->
            mImageInfoGetImage = cls.findMethod("getImage")
        }
        env.findClass("com.oplus.posteffect.image.ContentImage")?.let { cls ->
            // ColorOS 17 的 ImageInfo.image 是 ContentImage，不是 WeakReference<Bitmap>。
            mContentImageRequire = cls.findMethod("require")
            mContentImageGet = cls.findMethod("get")
            fContentImageRecycled = cls.findField("isRecycled")
        }
        runCatching {
            mImageRefGet = WeakReference::class.java.getMethod("get")
        }
        mPaintReset = Paint::class.java.findMethod("reset")

        mViewGetViewRootImpl = View::class.java.findMethod("getViewRootImpl")
        env.findClass("android.view.ViewRootImpl")?.let { cls ->
            mRootGetSurfaceControl = cls.findMethod("getSurfaceControl")
        }
        clsSurfaceControl?.let { sc ->
            mSurfaceIsValid = sc.findMethod("isValid")
            mSurfaceGetLayerId = sc.findMethod("getLayerId")
        }

        env.findClass(CLS_FULLSCREEN_BANNER_CONTAINER)?.let { cls ->
            fBannerInContainer = cls.findField("mFullScreenBanner")
            fBannerBgInContainer = cls.findField("mFullScreenBannerBg")
            fBannerBlurMgrInContainer = cls.findField("mViewBlurManager")
            fBannerProxyInContainer = cls.findField("mViewBlurProxy")
        }
        env.findClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView")?.let { cls ->
            fRowBackgroundNormal = cls.findField("mBackgroundNormal")
        }
        env.findClass(CLS_NOTIF_BG_VIEW)?.let { cls ->
            fRowBgCornerRadii = cls.findField("mCornerRadii")
        }
        env.findClass(CLS_HEADS_UP_LAYOUT)?.let { cls ->
            mGetViewBlurManager = cls.findMethod("getBlurManager")
        }
    }

    private fun bindPopupView(view: View, proxy: Any) {
        val owner = runCatching { mProxyGetView?.invoke(proxy) }.getOrNull()
        if (owner !== view) return
        if (!isFullScreenBannerView(view) && !isBlurTypeMotion(proxy)) return

        val existing = viewToState[view]
        if (existing != null && existing.proxy.get() === proxy && existing.active) {
            existing.onNotificationUpdate()
            existing.handoffUntil = 0L
            existing.startFrames()
            existing.invalidate()
            return
        }
        existing?.stop()

        val state = PopupState(view, proxy)
        viewToState[view] = state
        state.attach()
        state.startFrames()
        state.invalidate()
        if (loggedFirstBind.compareAndSet(false, true)) {
            HookLog.ok("[$TAG] 已绑定消息弹窗液态玻璃：${view.javaClass.simpleName}")
        }
    }

    private fun detachState(view: View?, proxy: Any?) {
        view ?: return
        val state = viewToState[view] ?: return
        if (proxy != null && state.proxy.get() !== proxy) return
        if (viewToState[view] === state) {
            viewToState.remove(view)
        }
        state.stop()
    }

    private fun reconcileBannerContainer(containerObj: Any?) {
        val group = containerObj as? ViewGroup ?: return
        val banner = (0 until group.childCount).reversed()
            .asSequence()
            .map { group.getChildAt(it) }
            .firstOrNull { isFullScreenBannerView(it) }
            ?: return
        runCatching { fBannerInContainer?.set(group, banner) }
        val mgr = runCatching { fBannerBlurMgrInContainer?.get(group) }.getOrNull()
        var proxy = if (mgr != null) runCatching { mViewBlurMgrGetProxy?.invoke(mgr, banner) }.getOrNull() else null
        if (proxy == null) {
            val cachedProxy = runCatching { fBannerProxyInContainer?.get(group) }.getOrNull()
            if (cachedProxy != null && runCatching { mProxyGetView?.invoke(cachedProxy) }.getOrNull() === banner) {
                proxy = cachedProxy
            }
        }
        if (proxy == null) return
        var state = viewToState[banner]
        if (state == null || !state.active || state.proxy.get() !== proxy) {
            bindPopupView(banner, proxy)
            state = viewToState[banner]
        }
        if (state == null || !state.isEligible()) return
        val bg = banner.background
        if (bg !== state.wrapper) {
            val realFallback = if (bg is PopupGlassDrawable) bg.state.fallback.get() else bg
            if (realFallback != null) {
                state.fallback = WeakReference(realFallback)
            }
            banner.background = state.wrapper
        }
        if (state.wrapper.bounds.isEmpty && banner.width > 0 && banner.height > 0) {
            state.wrapper.setBounds(0, 0, banner.width, banner.height)
        }
        runCatching { fBannerBgInContainer?.set(group, state.wrapper) }
        runCatching { fBannerProxyInContainer?.set(group, proxy) }
        state.startFrames()
    }

    private fun reconcileHeadsUpLayout(layout: ViewGroup) {
        val mgr = runCatching { mGetViewBlurManager?.invoke(layout) }.getOrNull() ?: return
        val bgViews = ArrayList<View>(4)
        collectNotifBgViews(layout, bgViews)
        for (bgView in bgViews) {
            val proxy = runCatching { mViewBlurMgrGetProxy?.invoke(mgr, bgView) }.getOrNull() ?: continue
            val existing = viewToState[bgView]
            if (isBlurTypeMotion(proxy) &&
                (existing == null || !existing.active || existing.proxy.get() !== proxy)
            ) {
                bindPopupView(bgView, proxy)
            }
        }
    }

    private fun collectNotifBgViews(view: View, out: MutableList<View>) {
        if (isNotificationBackgroundView(view)) {
            out += view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectNotifBgViews(view.getChildAt(i), out)
            }
        }
    }

    private fun drawHeadsUpRowContour(row: ViewGroup, canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        val bgView = runCatching { fRowBackgroundNormal?.get(row) as? View }.getOrNull() ?: return
        val state = viewToState[bgView] ?: return
        if (!state.active || !state.hasContent || !state.isEligible() || !state.isVisible()) return
        if (bgView.width <= 0 || bgView.height <= 0) return

        val m = Matrix()
        var ancestor: View? = bgView
        var found = false
        var depth = 0
        while (ancestor != null && depth < 16) {
            if (ancestor === row) {
                found = true
                break
            }
            ancestor = ancestor.parent as? View
            depth++
        }
        if (!found) return
        bgView.transformMatrixToGlobal(m)
        row.transformMatrixToLocal(m)

        val alphaFactor = runCatching {
            val alpha = state.wrapper.alpha
            val viewAlpha = relativeAlpha(bgView, row)
            (alpha * viewAlpha).roundToInt()
        }.getOrDefault(255)
        if (alphaFactor <= 0) return

        val bounds = Rect(state.wrapper.bounds)
        if (bounds.isEmpty) bounds.set(0, 0, bgView.width, bgView.height)
        val save = canvas.save()
        try {
            canvas.concat(m)
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            state.drawContourOverlay(canvas, bounds.width(), bounds.height(), alphaFactor)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawBannerContour(containerObj: Any?, canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        val container = containerObj as? View ?: return
        val banner = runCatching { fBannerInContainer?.get(container) as? View }.getOrNull() ?: return
        val state = viewToState[banner] ?: return
        if (!state.active || !state.hasContent || !state.isEligible() || !state.isVisible()) return
        if (banner.background !== state.wrapper) return
        val bounds = state.wrapper.bounds
        if (bounds.isEmpty) return

        val alpha = (state.wrapper.alpha * banner.alpha).roundToInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val save = canvas.save()
        try {
            canvas.translate(
                (banner.left - container.scrollX).toFloat(),
                (banner.top - container.scrollY).toFloat()
            )
            canvas.concat(banner.matrix)
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            state.drawContourOverlay(canvas, bounds.width(), bounds.height(), alpha)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun dispatchPopupTouch(root: View, ev: MotionEvent) {
        val masked = when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_CANCEL
            else -> ev.actionMasked
        }
        val states = collectStatesUnder(root)
        for (state in states) {
            val owner = state.materialRoot() ?: continue
            if (!state.isEligible() || !owner.isEnabled) continue
            val w = max(1, owner.width)
            val h = max(1, owner.height)
            val matrix = Matrix()
            root.transformMatrixToGlobal(matrix)
            owner.transformMatrixToLocal(matrix)
            val pts = floatArrayOf(ev.x, ev.y)
            matrix.mapPoints(pts)
            if (masked == MotionEvent.ACTION_DOWN && (pts[0] < 0f || pts[1] < 0f || pts[0] > w || pts[1] > h)) {
                continue
            }
            val nx = (pts[0] / w).coerceIn(0f, 1f)
            val ny = (pts[1] / h).coerceIn(0f, 1f)
            state.onTouch(masked, nx, ny)
        }
    }

    private fun collectStatesUnder(root: View): List<PopupState> {
        val out = ArrayList<PopupState>(2)
        collectStatesRecursive(root, out)
        return out
    }

    private fun collectStatesRecursive(view: View, out: MutableList<PopupState>) {
        viewToState[view]?.let { if (it.active) out += it }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectStatesRecursive(view.getChildAt(i), out)
            }
        }
    }

    private fun relativeAlpha(child: View, ancestor: View): Float {
        var cur: View? = child
        var a = 1.0f
        var steps = 0
        while (cur != null && cur !== ancestor && steps < 48) {
            a *= cur.alpha
            cur = cur.parent as? View
            steps++
        }
        return if (cur === ancestor) a.coerceIn(0f, 1f) else 0f
    }

    private fun invalidateAll() {
        val list = synchronized(viewToState) { viewToState.values.toList() }
        list.forEach { it.invalidate() }
    }

    private fun attachSharedCaptureManager(drawable: Any, appContext: Context, cl: ClassLoader): Any? {
        // 不复用 SystemUI 的 Manager，而是按 ClassLoader 共享一个
        // 独立 Manager，并替换 Drawable 的 Lazy delegate，保证注册和 updateCapture 同连接。
        if (clsBlurManager == null) return null
        val ctor = ctorBlurManager ?: return null
        val fDel = fContinuousManagerDelegate ?: return null
        val mgr = synchronized(managersLock) {
            sharedManagers.getOrPut(cl) { ctor.newInstance(appContext) }
        }
        return runCatching {
            val lazyCls = Class.forName("kotlin.InitializedLazyImpl", true, cl)
            val lazyObj = lazyCls.getConstructor(Any::class.java).newInstance(mgr)
            fDel.set(drawable, lazyObj)
            check(mGetBlurDrawableManager?.invoke(drawable) === mgr) {
                "independent BlurDrawableManager injection failed"
            }
            mgr
        }.onFailure {
            HookLog.error("[$TAG] 独立捕获管理器注入失败", it)
        }.getOrNull()
    }

    /** 同时兼容 ColorOS 17 ContentImage 与旧版 WeakReference<Bitmap>。 */
    private fun bitmapFromImageInfo(info: Any?): Bitmap? {
        info ?: return null
        val image = runCatching { mImageInfoGetImage?.invoke(info) }.getOrNull() ?: return null
        val recycled = runCatching { fContentImageRecycled?.getBoolean(image) == true }.getOrDefault(false)
        if (recycled) return null
        val bitmap = when (image) {
            is Bitmap -> image
            is WeakReference<*> -> runCatching { image.get() as? Bitmap }.getOrNull()
            else -> runCatching { mContentImageRequire?.invoke(image) as? Bitmap }.getOrNull()
                ?: runCatching { mContentImageGet?.invoke(image) as? Bitmap }.getOrNull()
                ?: runCatching { mImageRefGet?.invoke(image) as? Bitmap }.getOrNull()
        }
        return bitmap?.takeUnless { it.isRecycled }
    }

    private fun hasSdkBitmap(drawable: Any, pending: Boolean): Boolean {
        val mInfo = if (pending) mBaseGetPendingImageInfo else mBaseGetDrawingImageInfo
        val info = runCatching { mInfo?.invoke(drawable) }.getOrNull() ?: return false
        return bitmapFromImageInfo(info) != null
    }

    private fun isFullScreenBannerView(view: View?): Boolean =
        view != null && view.javaClass.name == CLS_FULLSCREEN_BANNER

    private fun isNotificationBackgroundView(view: View?): Boolean =
        view != null && view.javaClass.name == CLS_NOTIF_BG_VIEW

    private fun isBlurTypeMotion(proxy: Any?): Boolean {
        val bt = runCatching { mProxyGetBlurType?.invoke(proxy) }.getOrNull() ?: return false
        return bt.javaClass.name.endsWith("\$BlurTypeMotion")
    }

    private fun hasAncestor(view: View?, targetClassName: String): Boolean {
        var cur = view
        var depth = 0
        while (cur != null && depth < 32) {
            if (cur.javaClass.name == targetClassName) return true
            cur = cur.parent as? View
            depth++
        }
        return false
    }

    /** 按 Kyant / ColorOS 超椭圆参数构建 2.5 次幂平滑圆角路径。 */
    private fun buildSuperEllipsePath(w: Float, h: Float, radius: Float): Path {
        val r = max(0f, min(radius, min(w, h) * 0.5f))
        val path = Path()
        if (r < 0.5f) {
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            return path
        }
        fun addArc(cx: Float, cy: Float, startRad: Double) {
            val steps = 32
            for (i in 1..steps) {
                val theta = startRad + (i * Math.PI * 0.5) / steps
                val c = cos(theta)
                val s = sin(theta)
                val x = cx + (Math.copySign(abs(c).pow(0.8), c) * r).toFloat()
                val y = cy + (Math.copySign(abs(s).pow(0.8), s) * r).toFloat()
                path.lineTo(x, y)
            }
        }
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        addArc(w - r, r, -Math.PI * 0.5)
        path.lineTo(w, h - r)
        addArc(w - r, h - r, 0.0)
        path.lineTo(r, h)
        addArc(r, h - r, Math.PI * 0.5)
        path.lineTo(0f, r)
        addArc(r, r, Math.PI)
        path.close()
        return path
    }

    private class PopupGlassDrawable(val state: PopupState) : Drawable(), Drawable.Callback {
        var explicitAlpha: Int = -1

        private fun drawFallback(canvas: Canvas, fb: Drawable?) {
            fb ?: return
            fb.bounds = bounds
            if (explicitAlpha >= 0) fb.alpha = explicitAlpha
            fb.draw(canvas)
        }

        override fun draw(canvas: Canvas) {
            val fb = state.fallback.get()
            val cfg = configProvider()
            if (!canvas.isHardwareAccelerated || !state.active || !cfg.enabled ||
                (!state.isEligible() && !state.isHeadsUpStandardWindow())
            ) {
                drawFallback(canvas, fb)
                return
            }
            try {
                state.ensureSdk()
                state.updateParams()
                val native = state.nativeDrawable
                if (native != null) {
                    native.bounds = bounds
                    if (!state.drawNativeSlot(canvas, native)) {
                        drawFallback(canvas, fb)
                    }
                } else {
                    drawFallback(canvas, fb)
                }
                state.startFrames()
            } catch (t: Throwable) {
                state.failed = true
                state.hasContent = false
                drawFallback(canvas, fb)
                HookLog.error("[$TAG] wrapper draw 回退原生", t)
            }
        }

        override fun getAlpha(): Int {
            if (explicitAlpha >= 0) return explicitAlpha
            val fb = state.fallback.get()
            if (isFullScreenBannerView(state.owner.get()) || fb == null) return 255
            return fb.alpha
        }

        override fun setAlpha(alpha: Int) {
            val clamped = alpha.coerceIn(0, 255)
            explicitAlpha = clamped
            state.fallback.get()?.alpha = clamped
            state.nativeDrawable?.alpha = clamped
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            state.fallback.get()?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun invalidateDrawable(who: Drawable) {
            invalidateSelf()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            scheduleSelf(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            unscheduleSelf(what)
        }
    }

    private class PopupState(view: View, proxyObj: Any) :
        Choreographer.FrameCallback,
        View.OnAttachStateChangeListener,
        ViewTreeObserver.OnPreDrawListener {

        val owner = WeakReference(view)
        val proxy = WeakReference(proxyObj)
        var fallback = WeakReference<Drawable>(null)
        val wrapper = PopupGlassDrawable(this)

        @Volatile
        var active = true

        @Volatile
        var failed = false

        @Volatile
        var hasContent = false

        @Volatile
        var nativeWaiting = false

        @Volatile
        var inFlight = false

        var nativeDrawable: Drawable? = null
        private var nativeSlot: RenderNode? = null
        private var nativeSlotLock: Any? = null
        private var manager: Any? = null
        private var param: Any? = null
        private var surface: Any? = null
        private var windowRoot = WeakReference<Any>(null)

        private var observer: ViewTreeObserver? = null
        private var posted = false
        private var glassWasReady = false
        private var capturePrimed = false
        private var capturePrimedAt = 0L
        private var startupUntil = SystemClock.uptimeMillis() + STARTUP_WAIT_WINDOW_MS
        private var settleUntil = SystemClock.uptimeMillis() + CAPTURE_SETTLE_WINDOW_MS
        private var retryAt = 0L
        private var requestedAt = 0L
        private var lastRequestNanos = 0L
        private var suppressWake = false
        var handoffUntil = 0L

        private val region = Rect()
        private val lastRegion = Rect()
        private val bitmapToLocal = Matrix()
        private val globalTransform = FloatArray(9)
        private val lastGlobalTransform = FloatArray(9)
        private var hasGlobalTransform = false
        private var lastBitmap = WeakReference<Bitmap>(null)
        private var lastBlurPx = -1
        private var lastAlpha = -1
        private var lastRev = -1L
        private var rawRadius = -1f

        private var touchX = 0.5f
        private var touchY = 0.5f
        private var touchPress = 0f

        private var glassShader: RuntimeShader? = null
        private var glassBitmapRef: Bitmap? = null
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val inverseMatrix = Matrix()
        private val inverseVals = FloatArray(9)

        private var contourShader: RuntimeShader? = null
        private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedPathW = -1
        private var cachedPathH = -1
        private var cachedPathR = -1f
        private var cachedPath: Path? = null

        init {
            activeStateCount.incrementAndGet()
        }

        fun attach() {
            val v = owner.get() ?: return
            v.addOnAttachStateChangeListener(this)
            val vto = v.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnPreDrawListener(this)
                observer = vto
            }
        }

        fun stop() {
            if (!active) return
            active = false
            activeStateCount.updateAndGet { max(0, it - 1) }
            if (posted) {
                Choreographer.getInstance().removeFrameCallback(this)
                posted = false
            }
            owner.get()?.removeOnAttachStateChangeListener(this)
            observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
            observer = null
            releaseSdk()
            glassPaint.shader = null
            glassShader = null
            glassBitmapRef = null
            contourShader = null
            contourPaint.shader = null
            invalidate()
        }

        fun onTouch(action: Int, nx: Float, ny: Float) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = nx; touchY = ny; touchPress = 1f
                    wakeCapture("touch-down")
                }
                MotionEvent.ACTION_MOVE -> {
                    touchX = nx; touchY = ny; touchPress = 1f
                    wakeCapture("touch-move")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchPress = 0f
                    wakeCapture("touch-up")
                }
            }
            invalidate()
        }

        fun onCaptureDelivered() {
            val wasInFlight = inFlight
            inFlight = false
            suppressWake = wasInFlight
            // BaseDrawable.setPendingImageInfo() 只赋值，主线程 invalidate() 也不会置脏。
            // 必须显式标记内容脏，位置回调才会重录 RenderNode 并执行我们的玻璃绘制。
            runCatching { mBaseSetContentDirty?.invoke(nativeDrawable, true) }
            invalidate()
        }

        fun wakeFromBackgroundDraw() {
            if (!active) return
            if (suppressWake) {
                suppressWake = false
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now + 120L > settleUntil) {
                settleUntil = now + 240L
            }
            startFrames()
        }

        fun wakeCapture(reason: String) {
            if (!active) return
            settleUntil = SystemClock.uptimeMillis() + CAPTURE_SETTLE_WINDOW_MS
            startFrames()
        }

        fun onNotificationUpdate() {
            val v = owner.get()
            if (isFullScreenBannerView(v)) {
                if ((wrapper.bounds.isEmpty && hasContent) || wrapper.explicitAlpha == 0 || lastAlpha == 0) {
                    releaseSdk()
                }
                wrapper.explicitAlpha = -1
                if (v != null && v.width > 0 && v.height > 0) {
                    wrapper.setBounds(0, 0, v.width, v.height)
                }
            }
            val now = SystemClock.uptimeMillis()
            retryAt = 0L
            inFlight = false
            requestedAt = 0L
            lastRequestNanos = 0L
            suppressWake = false
            hasGlobalTransform = false
            failed = false
            glassWasReady = false
            nativeWaiting = false
            startupUntil = now + STARTUP_WAIT_WINDOW_MS
            settleUntil = now + CAPTURE_SETTLE_WINDOW_MS
            lastRegion.setEmpty()
            invalidate()
        }

        fun onHeadsUpCancel(p: Any?) {
            if (p != null && proxy.get() !== p) return
            handoffUntil = SystemClock.uptimeMillis() + HANDOFF_HOLD_MS
            inFlight = false
            requestedAt = 0L
            retryAt = 0L
            lastRequestNanos = 0L
            suppressWake = false
            hasGlobalTransform = false
            invalidate()
            startFrames()
        }

        fun isHeadsUpOrMotionTarget(): Boolean {
            val v = owner.get()
            val p = proxy.get()
            return isFullScreenBannerView(v) || isBlurTypeMotion(p)
        }

        fun isHeadsUpStandardWindow(): Boolean {
            val v = owner.get() ?: return false
            return isNotificationBackgroundView(v) && hasAncestor(v, CLS_HEADS_UP_LAYOUT)
        }

        fun isVisible(): Boolean {
            val v = owner.get() ?: return false
            return v.isAttachedToWindow && v.isShown && v.windowVisibility == View.VISIBLE
        }

        fun isEligible(): Boolean {
            if (!active) return false
            val cfg = configProvider()
            if (!cfg.enabled) return false
            val p = proxy.get() ?: return false
            val v = owner.get() ?: return false
            if (isFullScreenBannerView(v) || isHeadsUpStandardWindow()) return true
            val blurEnabled = runCatching { mProxyGetBlurEnabled?.invoke(p) == true }.getOrDefault(false)
            val blurAmt = runCatching { (mProxyGetBlurAmount?.invoke(p) as? Number)?.toFloat() ?: 0f }.getOrDefault(0f)
            return blurEnabled && blurAmt > 0f && isBlurTypeMotion(p)
        }

        fun materialRoot(): View? {
            val v = owner.get() ?: return null
            if (isFullScreenBannerView(v)) return v
            var cur: View? = v
            var depth = 0
            while (cur != null && depth < 32) {
                if (cur.javaClass.name == CLS_EXPANDABLE_ROW) return cur
                cur = cur.parent as? View
                depth++
            }
            return v
        }

        fun invalidate() {
            var cur = owner.get() ?: return
            cur.postInvalidateOnAnimation()
            var depth = 0
            while (depth < 32) {
                cur.postInvalidateOnAnimation()
                if (cur.javaClass.name == CLS_HEADS_UP_LAYOUT ||
                    cur.javaClass.name == CLS_FULLSCREEN_BANNER_CONTAINER
                ) {
                    return
                }
                cur = (cur.parent as? View) ?: return
                depth++
            }
        }

        fun startFrames() {
            if (!active || posted || !isVisible()) return
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            if (!active) return
            val v = owner.get()
            if (v == null) {
                stop()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (isNotificationBackgroundView(v) && !isHeadsUpStandardWindow() &&
                handoffUntil > 0L && now >= handoffUntil
            ) {
                if (viewToState[v] === this) viewToState.remove(v)
                stop()
                return
            }
            if (!isVisible()) {
                releaseSdk()
                return
            }
            if (!isEligible() && !isHeadsUpStandardWindow()) {
                releaseSdk()
                return
            }
            val cfg = configProvider()
            if (!cfg.enabled) {
                releaseSdk()
                return
            }
            // 所有可见且符合条件的 Motion/Heads-Up 状态都持续刷新；不同系统视图可能把
            // 同一个弹窗绑定到 FullScreenBackgroundView 或 NotificationBackgroundView。
            // 退出条件统一交给上方的可见性、eligible 与 handoff 判断。
            try {
                if (failed) {
                    releaseSdk()
                    retryAt = now + CAPTURE_RETRY_DELAY_MS
                }
                observeGlobalMotion()
                ensureSdk()
                updateParams()
                requestCapture(now, frameTimeNanos)
                startFrames()
            } catch (t: Throwable) {
                releaseSdk()
                retryAt = SystemClock.uptimeMillis() + CAPTURE_RETRY_DELAY_MS
                HookLog.error("[$TAG] frame loop 异常", t)
            }
        }

        override fun onPreDraw(): Boolean {
            if (!active) return true
            val v = owner.get() ?: return true
            if (isFullScreenBannerView(v) && v.parent is ViewGroup) {
                reconcileBannerContainer(v.parent)
            }
            if (observeGlobalMotion()) {
                wakeCapture("preDraw-move")
            }
            return true
        }

        override fun onViewAttachedToWindow(v: View) {
            val vto = v.viewTreeObserver
            if (vto.isAlive && observer !== vto) {
                observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
                vto.addOnPreDrawListener(this)
                observer = vto
            }
            wakeCapture("attach")
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (posted) {
                Choreographer.getInstance().removeFrameCallback(this)
                posted = false
            }
            observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
            observer = null
            releaseSdk()
        }

        fun ensureSdk() {
            val v = owner.get() ?: return
            val p = proxy.get() ?: return
            if (!isEligible() || !isVisible()) return
            val root = runCatching { mViewGetViewRootImpl?.invoke(v) }.getOrNull()
            if (root == null) {
                if (windowRoot.get() != null) releaseSdk()
                return
            }
            val sc = runCatching { mRootGetSurfaceControl?.invoke(root) }.getOrNull()
            val scValid = sc != null && runCatching { mSurfaceIsValid?.invoke(sc) == true }.getOrDefault(false)
            if (!scValid) {
                releaseSdk()
                return
            }
            if (windowRoot.get() !== root || surface !== sc) {
                releaseSdk()
            }
            windowRoot = WeakReference(root)
            surface = sc
            if (nativeDrawable != null || SystemClock.uptimeMillis() < retryAt) return

            val bdCtor = ctorBlurDrawable ?: return
            val bpCtor = ctorBlurParamIntInt ?: return
            val appCtx = v.context.applicationContext ?: v.context
            val cl = p.javaClass.classLoader ?: return
            val drawable = bdCtor.newInstance(appCtx, sc) as Drawable
            nativeDrawable = drawable
            nativeToState[drawable] = this
            drawable.callback = wrapper
            try {
                manager = attachSharedCaptureManager(drawable, appCtx, cl)
                val bp = bpCtor.newInstance(0, 63)
                param = bp
                runCatching {
                    val baseParam = mBlurParamGetBase?.invoke(bp)
                    if (baseParam != null) mBaseParamSetZoomScale?.invoke(baseParam, 1.0f)
                }
                mBaseSetEnableBlurShader?.invoke(drawable, true)
                mBaseSetPlaceHolderColor?.invoke(drawable, 0)
                mBaseSetPosThresholdPx?.invoke(drawable, 1)
                val rn = fBaseRenderNode?.get(drawable) as? RenderNode
                val lock = mBaseGetDataLock?.invoke(drawable)
                if (rn == null || lock == null) {
                    throw IllegalStateException("BlurDrawable renderNode/dataLock unavailable")
                }
                rn.setClipToBounds(false)
                nativeSlot = rn
                nativeSlotLock = lock
                failed = false
                hasContent = false
                glassWasReady = false
                nativeWaiting = false
                inFlight = false
                lastBlurPx = -1
                lastAlpha = -1
                lastRegion.setEmpty()
                capturePrimedAt = SystemClock.uptimeMillis()
                settleUntil = capturePrimedAt + CAPTURE_SETTLE_WINDOW_MS
                updateParams()
                primeCapture()
            } catch (t: Throwable) {
                releaseSdk()
                retryAt = SystemClock.uptimeMillis() + CAPTURE_RETRY_DELAY_MS
                throw t
            }
        }

        fun releaseSdk() {
            val nd = nativeDrawable
            nativeDrawable = null
            nativeSlot = null
            nativeSlotLock = null
            manager = null
            param = null
            surface = null
            windowRoot.clear()
            hasContent = false
            glassWasReady = false
            nativeWaiting = false
            inFlight = false
            lastBitmap.clear()
            if (nd != null) {
                nativeToState.remove(nd)
                if (capturePrimed) {
                    runCatching { mContinuousRemoveBlurDrawable?.invoke(nd, false) }
                        .onFailure { HookLog.error("[$TAG] 注销弹窗捕获 Drawable 失败", it) }
                }
                nd.callback = null
            }
            capturePrimed = false
            capturePrimedAt = 0L
        }

        fun updateParams() {
            val nd = nativeDrawable ?: return
            val v = owner.get() ?: return
            val p = proxy.get() ?: return
            val bp = param ?: return
            val cfg = configProvider()
            val dm = v.resources.displayMetrics
            val now = SystemClock.uptimeMillis()
            // 弹窗模糊完全沿用用户设置，不做额外透明度/半径缩放。
            val popupBlurPx = cfg.blurRadiusPx.coerceAtLeast(1)

            if (!glassWasReady && (capturePrimedAt <= 0L || now - capturePrimedAt < 450L)) {
                region.set(0, 0, dm.widthPixels, dm.heightPixels)
            } else if (v.getGlobalVisibleRect(region)) {
                val pad = -ceil(cfg.refractionAmt + popupBlurPx * 3f + 28f).toInt()
                region.inset(pad, pad)
                region.intersect(0, 0, dm.widthPixels, dm.heightPixels)
            } else {
                region.setEmpty()
            }

            var dirty = cfg.revision != lastRev
            lastRev = cfg.revision
            if (!region.isEmpty && region != lastRegion) {
                val hadRegion = !lastRegion.isEmpty
                mBlurParamSetCaptureRegion?.invoke(bp, Rect(region))
                lastRegion.set(region)
                if (hadRegion) wakeCapture("region-resize")
                dirty = true
            }
            if (lastBlurPx != popupBlurPx) {
                lastBlurPx = popupBlurPx
                mBlurParamSetBlurRadius?.invoke(bp, popupBlurPx)
                dirty = true
            }
            if (dirty) {
                mBaseSetBlurParamsSync?.invoke(nd, bp)
                mBaseInvalidate?.invoke(nd)
            }
            val blurCfg = runCatching { mProxyGetBlurConfig?.invoke(p) }.getOrNull()
            if (blurCfg != null) {
                rawRadius = runCatching { (mBlurConfigGetCornerRadius?.invoke(blurCfg) as? Number)?.toFloat() ?: -1f }
                    .getOrDefault(-1f)
            }
            val fb = fallback.get()
            val alpha = when {
                wrapper.explicitAlpha >= 0 -> wrapper.explicitAlpha
                isFullScreenBannerView(v) || fb == null -> 255
                else -> fb.alpha
            }
            if (lastAlpha != alpha) {
                lastAlpha = alpha
                nd.alpha = alpha
            }
        }

        private fun primeCapture() {
            val nd = nativeDrawable ?: return
            if (manager == null || surface == null) return
            if (!capturePrimed) {
                val addBlurDrawable = mContinuousAddBlurDrawable
                    ?: throw IllegalStateException("addBlurDrawable unavailable")
                addBlurDrawable.invoke(nd)
                capturePrimed = true
                capturePrimedAt = SystemClock.uptimeMillis()
                HookLog.ok("[$TAG] 独立捕获连接已注册")
            }
            requestCapture(SystemClock.uptimeMillis(), System.nanoTime())
        }

        private fun requestCapture(nowMs: Long, frameNanos: Long) {
            if (nativeDrawable == null || manager == null || surface == null) return
            if (inFlight && (requestedAt <= 0L || nowMs - requestedAt < CAPTURE_INFLIGHT_TIMEOUT_MS)) return
            if (lastRequestNanos > 0L && frameNanos > lastRequestNanos && (frameNanos - lastRequestNanos) < 8_000_000L) {
                return
            }
            if (runCatching { mSurfaceIsValid?.invoke(surface) != true }.getOrDefault(true)) {
                releaseSdk()
                return
            }
            requestedAt = nowMs
            lastRequestNanos = frameNanos
            inFlight = true
            mManagerUpdateCapture?.invoke(manager, surface)
        }

        private fun observeGlobalMotion(): Boolean {
            val v = owner.get() ?: return false
            val m = Matrix()
            v.transformMatrixToGlobal(m)
            m.getValues(globalTransform)
            var changed = false
            if (hasGlobalTransform) {
                for (i in 0 until 9) {
                    if (abs(globalTransform[i] - lastGlobalTransform[i]) > 0.01f) {
                        changed = true
                        break
                    }
                }
            } else {
                changed = true
            }
            System.arraycopy(globalTransform, 0, lastGlobalTransform, 0, 9)
            hasGlobalTransform = true
            return changed
        }

        private fun effectiveRadius(w: Int, h: Int, density: Float, cornerScale: Float): Float {
            var base = rawRadius
            if (isNotificationBackgroundView(owner.get())) {
                val radii = runCatching { fRowBgCornerRadii?.get(owner.get()) as? FloatArray }.getOrNull()
                if (radii != null && radii.isNotEmpty() && radii[0] > 0f) {
                    base = radii[0]
                }
            }
            if (!base.isFinite() || base <= 0f) {
                base = min(28f * density, 0.32f * h)
            }
            val scaled = (base * cornerScale).coerceAtLeast(min(26f * density, min(w, h) * 0.5f))
            return scaled.coerceIn(0f, min(w, h) * 0.5f)
        }

        fun drawNativeSlot(canvas: Canvas, drawable: Drawable): Boolean {
            val rn = nativeSlot ?: return false
            val lock = nativeSlotLock ?: return false
            if (drawable !== nativeDrawable || failed) return false
            synchronized(lock) {
                val bmp = lastBitmap.get()
                val readyBmp = hasContent && bmp != null && !bmp.isRecycled
                val waiting = !readyBmp && SystemClock.uptimeMillis() < startupUntil
                val hasSdkBuf = hasSdkBitmap(drawable, pending = true) || hasSdkBitmap(drawable, pending = false)
                if (!readyBmp && !waiting && !hasSdkBuf) return false

                if (!rn.hasDisplayList() || (!readyBmp && !nativeWaiting)) {
                    val b = runCatching { mBaseGetDrawingBounds?.invoke(drawable) as? Rect }.getOrNull()
                    if (b != null && !b.isEmpty) {
                        val rec = rn.beginRecording()
                        try {
                            val pa = runCatching { (mBaseGetPaintAlpha?.invoke(drawable) as? Number)?.toInt() ?: 255 }.getOrDefault(255)
                            nativeWaiting = drawWaitingTint(rec, b.width(), b.height(), pa)
                        } finally {
                            rn.endRecording()
                        }
                        if (!nativeWaiting) return false
                    } else if (!readyBmp) {
                        return false
                    }
                }
                drawable.draw(canvas)
                return true
            }
        }

        fun drawStartupSlot(canvas: Canvas): Boolean {
            nativeWaiting = false
            val nd = nativeDrawable ?: return false
            if (!canvas.isHardwareAccelerated || !active || failed) return false
            if (hasSdkBitmap(nd, pending = false)) return false
            if (SystemClock.uptimeMillis() >= startupUntil) return false
            val b = runCatching { mBaseGetDrawingBounds?.invoke(nd) as? Rect }.getOrNull() ?: return false
            if (b.isEmpty) return false
            val pa = runCatching { (mBaseGetPaintAlpha?.invoke(nd) as? Number)?.toInt() ?: 255 }.getOrDefault(255)
            nativeWaiting = drawWaitingTint(canvas, b.width(), b.height(), pa)
            return nativeWaiting
        }

        private fun drawWaitingTint(canvas: Canvas, w: Int, h: Int, alpha: Int): Boolean {
            val v = owner.get() ?: return false
            val cfg = configProvider()
            val radius = effectiveRadius(w, h, v.resources.displayMetrics.density, cfg.cornerScale)
            val path = ensurePath(w, h, radius)
            val night = isDarkMode(v)
            val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val tintA = ((if (night) 0.30f else 0.14f) * (alpha.coerceIn(0, 255) / 255f) * 255f).roundToInt()
            tintPaint.color = (tintA shl 24) or (if (night) 0x1E222A else 0xF5F7FA)
            canvas.drawPath(path, tintPaint)
            return true
        }

        fun paint(canvas: Canvas) {
            nativeWaiting = false
            val nd = nativeDrawable ?: return
            val v = owner.get() ?: return
            if (!active || failed) return
            if (!isEligible() && !isHeadsUpStandardWindow()) return

            val prevContent = hasContent
            try {
                val info = mBaseGetDrawingImageInfo?.invoke(nd) ?: return
                val bmp = bitmapFromImageInfo(info) ?: return
                val mat = mBaseGetBitmapMatrix?.invoke(nd) as? Matrix ?: return
                bitmapToLocal.set(mat)
                val b = mBaseGetDrawingBounds?.invoke(nd) as? Rect ?: return
                if (b.isEmpty) return
                val pa = (mBaseGetPaintAlpha?.invoke(nd) as? Number)?.toInt() ?: 255

                if (drawGlassShader(canvas, v, bmp, bitmapToLocal, b.width(), b.height(), pa)) {
                    hasContent = true
                    glassWasReady = true
                    lastBitmap = WeakReference(bmp)
                    if (loggedFirstCapture.compareAndSet(false, true)) {
                        HookLog.ok("[$TAG] 消息弹窗液态玻璃首帧捕获完成 ${bmp.width}x${bmp.height}")
                    }
                    if (!prevContent) invalidate()
                }
            } finally {
                if (!prevContent && !hasContent) invalidate()
            }
        }

        private fun drawGlassShader(
            canvas: Canvas,
            view: View,
            bitmap: Bitmap,
            mat: Matrix,
            w: Int,
            h: Int,
            paintAlpha: Int
        ): Boolean {
            if (!canvas.isHardwareAccelerated || bitmap.isRecycled || w <= 0 || h <= 0) return false
            if (!mat.invert(inverseMatrix)) return false
            inverseMatrix.getValues(inverseVals)
            for (f in inverseVals) {
                if (!f.isFinite()) return false
            }

            val cfg = configProvider()
            val shader = glassShader ?: RuntimeShader(SHADER_POPUP_GLASS).also {
                glassShader = it
                glassPaint.shader = it
            }
            if (glassBitmapRef !== bitmap) {
                val bs = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
                shader.setInputShader("uScene", bs)
                glassBitmapRef = bitmap
            }

            val density = view.resources.displayMetrics.density
            val radius = effectiveRadius(w, h, density, cfg.cornerScale)
            val path = ensurePath(w, h, radius)
            val night = isDarkMode(view)
            val alphaNorm = paintAlpha.coerceIn(0, 255) / 255f

            shader.setFloatUniform("uSize", w.toFloat(), h.toFloat())
            shader.setFloatUniform("uRadius", radius)
            shader.setFloatUniform("uHeight", cfg.bandRaw.coerceIn(0f, min(w, h) * 0.48f))
            shader.setFloatUniform("uAmount", cfg.refractionAmt.coerceIn(0f, 240f))
            shader.setFloatUniform("uTintAlpha", if (night) 0.34f else 0.14f)
            shader.setFloatUniform("uSaturation", (cfg.saturation * 0.85f).coerceIn(1.0f, 1.65f))
            if (night) {
                shader.setFloatUniform("uTint", 0.11f, 0.13f, 0.17f)
            } else {
                shader.setFloatUniform("uTint", 0.96f, 0.97f, 1.0f)
            }
            shader.setFloatUniform("uAlpha", 1.0f)
            shader.setFloatUniform("uTouch", touchX, touchY)
            shader.setFloatUniform("uTouchPress", touchPress)
            shader.setFloatUniform("uInverse0", inverseVals[0], inverseVals[1], inverseVals[2])
            shader.setFloatUniform("uInverse1", inverseVals[3], inverseVals[4], inverseVals[5])
            shader.setFloatUniform("uInverse2", inverseVals[6], inverseVals[7], inverseVals[8])

            if (alphaNorm > 0f) {
                val save = if (alphaNorm < 1f) {
                    canvas.saveLayerAlpha(-24f, -24f, w + 24f, h + 24f, paintAlpha.coerceIn(0, 255))
                } else {
                    canvas.save()
                }
                canvas.drawPath(path, glassPaint)
                canvas.restoreToCount(save)
            }
            return true
        }

        fun drawContourOverlay(canvas: Canvas, w: Int, h: Int, alpha: Int) {
            val v = owner.get() ?: return
            if (w <= 0 || h <= 0 || alpha <= 0 || !canvas.isHardwareAccelerated) return
            val cfg = configProvider()
            val radius = effectiveRadius(w, h, v.resources.displayMetrics.density, cfg.cornerScale)
            val rimStrength = (cfg.rimStrength / 0.72f).coerceIn(0f, 1.25f)
            val cs = contourShader ?: RuntimeShader(SHADER_POPUP_CONTOUR).also {
                contourShader = it
                contourPaint.shader = it
            }
            cs.setFloatUniform("uSize", w.toFloat(), h.toFloat())
            cs.setFloatUniform("uRadius", radius)
            cs.setFloatUniform("uAlpha", alpha.coerceIn(0, 255) / 255f)
            cs.setFloatUniform("uRimStrength", rimStrength)
            canvas.drawRect(-20f, -12f, w + 20f, h + 28f, contourPaint)
        }

        private fun ensurePath(w: Int, h: Int, r: Float): Path {
            val clampedR = max(0f, min(r, min(w, h) * 0.5f))
            val cur = cachedPath
            if (cur != null && cachedPathW == w && cachedPathH == h && abs(cachedPathR - clampedR) < 0.25f) {
                return cur
            }
            val next = buildSuperEllipsePath(w.toFloat(), h.toFloat(), clampedR)
            cachedPath = next
            cachedPathW = w
            cachedPathH = h
            cachedPathR = clampedR
            return next
        }

        private fun isDarkMode(view: View): Boolean {
            val nightMask = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightMask == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
