package com.c17.glass.xposed.glass

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.HardwareRenderer
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.findField
import com.c17.glass.xposed.statusbar.findMethodsNamed
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * ColorOS 17 系统桌面长按应用小浮窗（COUIPopupListWindowContainer / RoundFrameLayout）液态玻璃 Hook。
 *
 * 已在真机 `OplusLauncher` dex 上核实结构并修复三处关键兼容点：
 *  1. R8 混淆后 `COUIPopupMenuRootView` 重命名为 `com.coui.appcompat.poplist.b`/`d`，且浮窗位于独立 `PopupWindow`，
 *     改为识别 `com.coui.appcompat.poplist.*` 父级与 `mPopupListWindow.q`/`r`；
 *  2. Hook `RoundFrameLayout` 的 COUI 模糊控制器 `C`（`BackgroundBlurBuilder`）与 `ViewRootManager.setBlurRadius(0)`，
 *     抑制原生乳白模糊覆层覆写 `background`，关闭「全局生效」时自动还原；
 *  3. Hook `PopupBlurView$Companion.getPopBlurView(Context, OplusDragLayer)`，在系统高斯模糊盖上去之前采集一次
 *     干净未模糊桌面+壁纸 `0.5x` 快照，再按第 2 组（桌面与 Dock）参数做 GPU `RenderEffect` 模糊、`2x2` 边缘折射
 *     及 `draw(Canvas)` 后置液态高光描边。
 */
internal object LauncherPopupGlassHook {

    private const val TAG = "launcher-popup-glass"
    private const val CAPTURE_SCALE = 0.5f

    private const val CLS_POPUP_CONTAINER = "com.android.launcher3.popup.view.COUIPopupListWindowContainer"
    private const val CLS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView"
    private const val CLS_POPUP_BLUR_COMPANION = "com.android.launcher3.popup.PopupBlurView\$Companion"
    private const val CLS_ROUND_FRAME = "com.coui.appcompat.poplist.RoundFrameLayout"
    private const val CLS_POPUP_MENU_ROOT = "com.coui.appcompat.poplist.COUIPopupMenuRootView"
    private const val CLS_POPUP_LIST_BASE = "com.coui.appcompat.poplist.a"
    private const val CLS_BITMAP_UTILS = "com.oplus.basecommon.util.BitmapUtils"
    private const val CLS_VRM = "com.oplus.view.ViewRootManager"

    private const val POPUP_GLASS_AGSL = """
uniform shader sceneBackdrop;
uniform float4 uRect;
uniform float uRadius;
uniform float uBand;
uniform float uRefraction;
uniform float uRim;
uniform float4 uTint;
uniform float3 uRow0;
uniform float3 uRow1;
uniform float3 uRow2;

float2 safeNorm(float2 v) {
    float l = length(v);
    return l > 1e-5 ? v / l : float2(0.0);
}

float2 toScene(float2 p) {
    float3 v = float3(p, 1.0);
    float z = dot(uRow2, v);
    z = abs(z) < 1e-5 ? 1e-5 : z;
    return float2(dot(uRow0, v), dot(uRow1, v)) / z;
}

float rboxSdf(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float2 rboxGrad(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    float2 m = max(q, float2(0.0));
    if (m.x > 1e-4 || m.y > 1e-4) {
        return sign(p) * safeNorm(m);
    }
    return sign(p) * (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
}

float circleSlope(float x) {
    float t = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, (1.0 - t) * (1.0 + t)));
}

half3 sampleGlass(float2 p, float2 center, float2 halfSize, float r, float band, float mn) {
    float sdf = rboxSdf(p - center, halfSize, r);
    float depth = max(-sdf, 0.0);
    float normPos = clamp(1.0 - depth / band, 0.0, 1.0);
    float slope = circleSlope(normPos);
    float shift = min(slope * uRefraction, max(mn * 0.85, 16.0));
    float2 n = rboxGrad(p - center, halfSize, r);
    float2 q = p - shift * n;
    half3 col = sceneBackdrop.eval(toScene(q)).rgb;
    if (shift > 3.0) {
        float disp = min(shift * 0.08, 6.0);
        half rCh = sceneBackdrop.eval(toScene(q - disp * n)).r;
        half bCh = sceneBackdrop.eval(toScene(q + disp * n)).b;
        col = half3(mix(col.r, rCh, half(0.45)), col.g, mix(col.b, bCh, half(0.45)));
    }
    return col;
}

half4 main(float2 pos) {
    float2 halfSize = (uRect.zw - uRect.xy) * 0.5;
    float2 center = (uRect.xy + uRect.zw) * 0.5;
    float r = clamp(uRadius, 2.0, min(halfSize.x, halfSize.y));
    float sdf = rboxSdf(pos - center, halfSize, r);
    if (sdf >= 0.75) return half4(0.0);

    float depth = max(-sdf, 0.0);
    float band = max(uBand, 1.0);
    float mn = min(halfSize.x, halfSize.y);
    float normPos = clamp(1.0 - depth / band, 0.0, 1.0);
    float slope = circleSlope(normPos);

    half3 col;
    if (uRefraction < 0.5 || depth >= band + 1.0) {
        col = sceneBackdrop.eval(toScene(pos)).rgb;
    } else {
        half3 acc = half3(0.0);
        for (int yy = 0; yy < 2; yy++) {
            for (int xx = 0; xx < 2; xx++) {
                float2 q = pos + (float2(float(xx), float(yy)) + 0.5) * 0.5 - 0.5;
                acc += sampleGlass(q, center, halfSize, r, band, mn);
            }
        }
        col = acc * half(0.25);
    }

    if (uRim > 0.005) {
        float rimGate = smoothstep(0.54, 0.90, slope);
        float bright = smoothstep(0.80, 1.0, slope);
        float mask = clamp(uRim * rimGate * (0.28 + 0.52 * bright), 0.0, 0.76);
        col = mix(col, min(col * 1.24 + half3(0.10), half3(1.0)), half(mask));
    }

    float veilAlpha = uTint.a * (1.0 - 0.42 * smoothstep(0.55, 1.0, slope));
    col = mix(col, half3(uTint.rgb), half(veilAlpha));
    float edgeAlpha = 1.0 - smoothstep(-1.0, 0.5, sdf);
    return half4(clamp(col, half3(0.0), half3(1.0)) * edgeAlpha, edgeAlpha);
}
"""

    private lateinit var isGlobalEnabled: () -> Boolean
    private lateinit var paramsProvider: () -> NativeGlassShader.GlassShaderParams
    private lateinit var blurRadiusProvider: () -> Float

    private var clsPopupContainer: Class<*>? = null
    private var clsPopupMenuRoot: Class<*>? = null
    private var clsRoundFrame: Class<*>? = null
    private var mGetPopupListWindow: Method? = null
    private var popupListFrameFields: List<Field> = emptyList()

    private var fRoundAnimRect: Field? = null
    private var fRoundRectF: Field? = null
    private var fRoundSmoothCornerRadius: Field? = null
    private var fRoundRadius: Field? = null
    private var fRoundDimenRadius: Field? = null
    private var fRoundBlurBuilder: Field? = null
    private var fRoundEdgeEnabled: Field? = null

    private var fBuilderVrm: Field? = null
    private var builderViewFields: List<Field> = emptyList()
    private var mVrmSetBlurRadius: Method? = null

    private var fPopupBlurWallpaper: Field? = null
    private var fPopupBlurDragLayer: Field? = null
    private var fPopupWallpaperBlendColor: Field? = null
    private var fPopupWallpaperDarkBlendColor: Field? = null
    private var mViewToBitmap: Method? = null
    private var mCreateHardwareBitmap: Method? = null
    private var mBaseActivityFromContext: Method? = null
    private var mLauncherGetWorkspace: Method? = null
    private var mRestoreHwLayerBeforeBlur: Method? = null
    private var mDisableChildrenHwLayers: Method? = null
    private var cOplusWallpaperManager: Class<*>? = null
    private var mOplusGetWallpaperBitmap: Method? = null
    private var iWindowManagerObj: Any? = null
    private var mScreenshotWallpaper: Method? = null

    private val originalBackgrounds = Collections.synchronizedMap(WeakHashMap<View, Drawable?>())
    private val trackedFrames = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val loggedBind = AtomicBoolean(false)
    private val loggedCapture = AtomicBoolean(false)
    private val loggedDraw = AtomicBoolean(false)

    private data class SceneSnapshot(
        val rawBitmap: Bitmap,
        val capW: Int,
        val capH: Int,
        val dragLayerW: Int,
        val dragLayerH: Int,
        val screenToDragLayer: Matrix,
        val backdropScaleX: Float,
        val backdropScaleY: Float,
        val hasWallpaper: Boolean
    )

    private data class WallpaperDrawResult(
        val drew: Boolean,
        val source: String
    )

    private val bitmapCopyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val capScrTmp = IntArray(2)
    private val capWinTmp = IntArray(2)

    @Volatile
    private var lastBlurViewRef: WeakReference<View>? = null

    @Volatile
    private var lastDragLayerRef: WeakReference<View>? = null

    @Volatile
    private var cachedSnapshotShader: BitmapShader? = null

    @Volatile
    private var cachedSnapshotBlurPx: Float = Float.NaN

    @Volatile
    private var cachedSnapshot: SceneSnapshot? = null

    @Volatile
    private var cachedBlurredBitmap: Bitmap? = null

    @Volatile
    private var cachedWallId: Int = Int.MIN_VALUE

    @Volatile
    private var cachedWallBitmap: Bitmap? = null

    @Volatile
    private var sharedRuntimeShader: RuntimeShader? = null

    @Volatile
    private var sharedShaderRejected: Boolean = false

    fun install(
        env: HookEnv,
        globalEnabled: () -> Boolean,
        params: () -> NativeGlassShader.GlassShaderParams,
        launcherBlurRadius: () -> Float
    ) {
        isGlobalEnabled = globalEnabled
        paramsProvider = params
        blurRadiusProvider = launcherBlurRadius

        clsPopupContainer = env.findClass(CLS_POPUP_CONTAINER)
        clsPopupMenuRoot = env.findClass(CLS_POPUP_MENU_ROOT)
        clsRoundFrame = env.findClass(CLS_ROUND_FRAME)

        mViewToBitmap = env.findClass(CLS_BITMAP_UTILS)?.findMethodsNamed("viewToBitmapByRenderer")
            ?.firstOrNull { it.parameterTypes.size == 2 }
        mCreateHardwareBitmap = runCatching {
            HardwareRenderer::class.java.getDeclaredMethod(
                "createHardwareBitmap",
                RenderNode::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()

        installBlurViewHooks(env)
        installPopupContainerHooks(env)
        resolveCaptureApis(env)
        val rfCls = clsRoundFrame ?: return
        resolveRoundFrameFields(env, rfCls)
        installRoundFrameHooks(env, rfCls)
        Handler(Looper.getMainLooper()).postDelayed({ prewarmShader() }, 900L)

        HookLog.ok("[$TAG] 桌面长按应用浮窗玻璃 Hook 已就绪")
    }

    private fun resolveCaptureApis(env: HookEnv) {
        val baseActivityCls = env.findClass("com.android.launcher3.BaseActivity")
        mBaseActivityFromContext = baseActivityCls?.findMethodsNamed("fromContext")
            ?.firstOrNull { it.parameterTypes.size == 1 }
        val launcherCls = env.findClass("com.android.launcher3.Launcher")
        mLauncherGetWorkspace = launcherCls?.findMethodsNamed("getWorkspace")
            ?.firstOrNull { it.parameterTypes.isEmpty() }
        val oplusWsCls = env.findClass("com.android.launcher3.OplusWorkspace")
        mRestoreHwLayerBeforeBlur = oplusWsCls?.findMethodsNamed("restoreHardwareLayerBeforeBlurCaptureIfNeeded")
            ?.firstOrNull { it.parameterTypes.isEmpty() }
        val wsCls = env.findClass("com.android.launcher3.Workspace") ?: oplusWsCls?.superclass
        mDisableChildrenHwLayers = wsCls?.findMethodsNamed("disableChildrenHwLayers")
            ?.firstOrNull { it.parameterTypes.isEmpty() }

        cOplusWallpaperManager = runCatching { Class.forName("android.app.OplusWallpaperManager") }.getOrNull()
        mOplusGetWallpaperBitmap = cOplusWallpaperManager?.declaredMethods?.firstOrNull { m ->
            m.name == "getWallpaperBitmap" && m.parameterTypes.size == 4
        }?.apply { isAccessible = true }

        runCatching {
            val smCls = Class.forName("android.os.ServiceManager")
            val binder = smCls.getMethod("getService", String::class.java).invoke(null, "window") as? IBinder
                ?: return@runCatching
            val stubCls = Class.forName("android.view.IWindowManager\$Stub")
            val wmObj = stubCls.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return@runCatching
            iWindowManagerObj = wmObj
            mScreenshotWallpaper = wmObj.javaClass.methods.firstOrNull {
                it.name == "screenshotWallpaper" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }
        }
    }

    private fun prewarmShader() {
        if (sharedRuntimeShader != null || sharedShaderRejected) return
        runCatching {
            sharedRuntimeShader = RuntimeShader(POPUP_GLASS_AGSL)
        }.onFailure {
            sharedShaderRejected = true
        }
    }

    private fun installBlurViewHooks(env: HookEnv) {
        env.findClass(CLS_POPUP_BLUR_VIEW)?.let { pbCls ->
            fPopupBlurWallpaper = pbCls.findField("mWallpaperDrawable")
            fPopupBlurDragLayer = pbCls.findField("mDragLayerDrawable")
            fPopupWallpaperBlendColor = pbCls.findField("mWallpaperBlendColor")
            fPopupWallpaperDarkBlendColor = pbCls.findField("mWallpaperDarkBlendColor")
            pbCls.findMethodsNamed("setWallpaperDrawable").forEach { m ->
                env.hookAfter(m, "$TAG:PopupBlurView.setWallpaperDrawable") { chain ->
                    val pbv = chain.thisObject as? View ?: return@hookAfter
                    lastBlurViewRef = WeakReference(pbv)
                    val wallDrawable = chain.args.getOrNull(0) as? Drawable
                    if (wallDrawable != null && cachedSnapshot?.hasWallpaper != true) {
                        clearSnapshot()
                        refreshTrackedFrames()
                    }
                }
            }
            pbCls.findMethodsNamed("setDragLayerDrawable").forEach { m ->
                env.hookAfter(m, "$TAG:PopupBlurView.setDragLayerDrawable") { chain ->
                    val pbv = chain.thisObject as? View ?: return@hookAfter
                    lastBlurViewRef = WeakReference(pbv)
                    if (cachedSnapshot == null) {
                        invalidateSnapshot()
                        refreshTrackedFrames()
                    }
                }
            }
            pbCls.findMethodsNamed("finish").forEach { m ->
                env.hookAfter(m, "$TAG:PopupBlurView.finish") { _ ->
                    clearSnapshot()
                }
            }
        }

        env.findClass(CLS_POPUP_BLUR_COMPANION)?.findMethodsNamed("getPopBlurView")
            ?.filter { it.parameterTypes.size == 2 }
            ?.forEach { m ->
                env.hookReplace(m, "$TAG:PopupBlurView.Companion.getPopBlurView") { chain ->
                    val res = chain.proceed()
                    runCatching {
                        val pbv = res as? View
                        val context = (chain.args.getOrNull(0) as? Context) ?: pbv?.context
                        val dragLayer = chain.args.getOrNull(1) as? View
                        if (pbv != null && context != null && dragLayer != null) {
                            lastBlurViewRef = WeakReference(pbv)
                            lastDragLayerRef = WeakReference(dragLayer)
                            if (isGlassActive()) {
                                clearSnapshot()
                                cachedSnapshot = captureCleanSceneSnapshot(context, dragLayer, pbv)
                            }
                        }
                    }
                    res
                }
            }
    }

    private fun installPopupContainerHooks(env: HookEnv) {
        val containerCls = clsPopupContainer ?: return
        mGetPopupListWindow = findMethodInHierarchy(containerCls, "getMPopupListWindow")
        env.findClass(CLS_POPUP_LIST_BASE)?.let { plCls ->
            val rfCls = clsRoundFrame
            popupListFrameFields = plCls.declaredFields.filter { f ->
                rfCls?.isAssignableFrom(f.type) == true || f.type.name == CLS_ROUND_FRAME
            }.onEach { it.isAccessible = true }
        }

        containerCls.findMethodsNamed("animateOpen").forEach { m ->
            env.hookAfter(m, "$TAG:COUIPopupListWindowContainer.animateOpen") { chain ->
                val container = chain.thisObject ?: return@hookAfter
                (container as? View)?.let { view ->
                    findDragLayerAncestor(view)?.let { lastDragLayerRef = WeakReference(it) }
                    if (isGlassActive()) bindPopupFramesInTree(view)
                }
                if (isGlassActive()) {
                    bindFramesFromPopupListWindow(container)
                }
            }
        }
        containerCls.findMethodsNamed("closeComplete").forEach { m ->
            env.hookAfter(m, "$TAG:COUIPopupListWindowContainer.closeComplete") { _ ->
                clearSnapshot()
            }
        }
    }

    private fun resolveRoundFrameFields(env: HookEnv, rfCls: Class<*>) {
        fRoundAnimRect = rfCls.findField("q")?.takeIf { it.type == Rect::class.java }
        fRoundRectF = rfCls.findField("v")?.takeIf { it.type == RectF::class.java }
        fRoundSmoothCornerRadius = rfCls.findField("x")?.takeIf { it.type == Float::class.javaPrimitiveType }
        fRoundRadius = rfCls.findField("w")?.takeIf { it.type == Float::class.javaPrimitiveType }
        fRoundDimenRadius = rfCls.findField("r")?.takeIf { it.type == Float::class.javaPrimitiveType }
        fRoundEdgeEnabled = rfCls.findField("G")?.takeIf { it.type == Boolean::class.javaPrimitiveType }

        val vrmCls = env.findClass(CLS_VRM)
        val builderField = rfCls.declaredFields.firstOrNull { f ->
            !f.type.isPrimitive && runCatching {
                f.type.declaredFields.any { inner ->
                    inner.type == vrmCls || inner.type.name == CLS_VRM
                }
            }.getOrDefault(false)
        } ?: rfCls.findField("C")
        fRoundBlurBuilder = builderField?.apply { isAccessible = true }

        val builderCls = builderField?.type ?: return
        fBuilderVrm = builderCls.declaredFields.firstOrNull { f ->
            f.type == vrmCls || f.type.name == CLS_VRM
        }?.apply { isAccessible = true }
        builderViewFields = builderCls.declaredFields.filter { f ->
            View::class.java.isAssignableFrom(f.type)
        }.onEach { it.isAccessible = true }
        mVrmSetBlurRadius = (fBuilderVrm?.type ?: vrmCls)?.findMethodsNamed("setBlurRadius")
            ?.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            ?.apply { isAccessible = true }

        // Hook `BackgroundBlurBuilder` (`z21`) apply / state update so COUI blur doesn't clobber our background
        builderCls.declaredMethods.filter { m ->
            (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE && m.name == "a") ||
                (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java && m.returnType == Void.TYPE)
        }.forEach { m ->
            env.hookAfter(m, "$TAG:${builderCls.simpleName}.${m.name}") { chain ->
                if (!isGlassActive()) return@hookAfter
                val builder = chain.thisObject ?: return@hookAfter
                suppressBuilderBlur(builder)
                for (vf in builderViewFields) {
                    val v = runCatching { vf.get(builder) as? View }.getOrNull() ?: continue
                    if (isLauncherPopupFrame(v)) {
                        syncFrameBackground(v)
                    }
                }
            }
        }
    }

    private fun installRoundFrameHooks(env: HookEnv, rfCls: Class<*>) {
        rfCls.findMethodsNamed("onAttachedToWindow").forEach { m ->
            env.hookAfter(m, "$TAG:RoundFrameLayout.onAttachedToWindow") { chain ->
                val frame = chain.thisObject as? View ?: return@hookAfter
                if (!isLauncherPopupFrame(frame)) return@hookAfter
                registerPopupFrame(frame)
            }
        }
        rfCls.findMethodsNamed("onSizeChanged").forEach { m ->
            env.hookAfter(m, "$TAG:RoundFrameLayout.onSizeChanged") { chain ->
                val frame = chain.thisObject as? View ?: return@hookAfter
                if (!isLauncherPopupFrame(frame)) return@hookAfter
                if (isGlassActive()) syncFrameBackground(frame)
            }
        }
        rfCls.findMethodsNamed("draw").forEach { m ->
            env.hookReplace(m, "$TAG:RoundFrameLayout.draw") { chain ->
                val frame = chain.thisObject as? View ?: return@hookReplace chain.proceed()
                if (!isLauncherPopupFrame(frame)) return@hookReplace chain.proceed()

                var restoreEdge = false
                runCatching {
                    trackedFrames[frame] = true
                    syncFrameBackground(frame)
                    if (isGlassActive()) {
                        fRoundEdgeEnabled?.let { fld ->
                            val prevEdge = runCatching { fld.getBoolean(frame) }.getOrDefault(false)
                            if (prevEdge) {
                                runCatching { fld.setBoolean(frame, false) }
                                restoreEdge = true
                            }
                        }
                    }
                }

                try {
                    val res = chain.proceed()
                    val canvas = chain.args.getOrNull(0) as? Canvas
                    val bg = frame.background
                    if (canvas != null && isGlassActive() && bg is PopupLiquidGlassDrawable) {
                        runCatching { bg.drawRimOverlay(canvas) }
                    }
                    res
                } finally {
                    if (restoreEdge) {
                        fRoundEdgeEnabled?.let { fld -> runCatching { fld.setBoolean(frame, true) } }
                    }
                }
            }
        }
    }

    fun onConfigChanged() {
        invalidateSnapshot()
        refreshTrackedFrames()
    }

    private fun isGlassActive(): Boolean = isGlobalEnabled() && paramsProvider().enabled

    private fun registerPopupFrame(frame: View) {
        trackedFrames[frame] = true
        findDragLayerAncestor(frame)?.let { lastDragLayerRef = WeakReference(it) }
        syncFrameBackground(frame)
    }

    private fun bindFramesFromPopupListWindow(container: Any) {
        val mGet = mGetPopupListWindow ?: return
        val popupWin = runCatching { mGet.invoke(container) }.getOrNull() ?: return
        for (fld in popupListFrameFields) {
            val rf = runCatching { fld.get(popupWin) as? View }.getOrNull() ?: continue
            registerPopupFrame(rf)
        }
    }

    private fun isLauncherPopupFrame(view: View): Boolean {
        var cur: Any? = view.parent
        var depth = 0
        while (cur != null && depth < 12) {
            if (clsPopupContainer?.isInstance(cur) == true || clsPopupMenuRoot?.isInstance(cur) == true) {
                return true
            }
            val name = cur.javaClass.name
            if (name.startsWith("com.coui.appcompat.poplist.") ||
                name.contains("PopupListWindowContainer") ||
                name.contains("PopupMenuRootView") ||
                name.contains("PopupDecorView")
            ) {
                return true
            }
            cur = (cur as? View)?.parent
            depth++
        }
        val rootName = view.rootView?.javaClass?.name.orEmpty()
        return rootName.contains("PopupDecorView") || rootName.startsWith("com.coui.appcompat.poplist.")
    }

    private fun findDragLayerAncestor(view: View): View? {
        var cur: Any? = view
        var depth = 0
        while (cur != null && depth < 18) {
            val v = cur as? View
            if (v != null && v.javaClass.name.contains("DragLayer")) return v
            cur = v?.parent
            depth++
        }
        return lastDragLayerRef?.get()
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            cur.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let {
                it.isAccessible = true
                return it
            }
            cur = cur.superclass
        }
        return null
    }

    private fun bindPopupFramesInTree(root: View) {
        if (root.javaClass.name == CLS_ROUND_FRAME || root.javaClass.name.endsWith(".RoundFrameLayout")) {
            registerPopupFrame(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                bindPopupFramesInTree(root.getChildAt(i))
            }
        }
    }

    private fun refreshTrackedFrames() {
        val frames = synchronized(trackedFrames) { trackedFrames.keys.toList() }
        frames.forEach { f ->
            if (f.isAttachedToWindow) {
                f.post { syncFrameBackground(f) }
            }
        }
    }

    private fun suppressBuilderBlur(builder: Any) {
        val vrm = runCatching { fBuilderVrm?.get(builder) }.getOrNull() ?: return
        runCatching { mVrmSetBlurRadius?.invoke(vrm, 0) }
    }

    private fun suppressRoundFrameNativeBlur(frame: View) {
        val builder = runCatching { fRoundBlurBuilder?.get(frame) }.getOrNull() ?: return
        suppressBuilderBlur(builder)
    }

    private fun syncFrameBackground(frame: View) {
        val enabled = isGlassActive()
        val curBg = frame.background
        if (!enabled) {
            if (curBg is PopupLiquidGlassDrawable) {
                val orig = originalBackgrounds.remove(frame)
                setBackgroundPreservePadding(frame, orig)
                runCatching {
                    val builder = fRoundBlurBuilder?.get(frame) ?: return@runCatching
                    val mApply = builder.javaClass.declaredMethods.firstOrNull {
                        it.name == "a" && it.parameterTypes.isEmpty()
                    }
                    mApply?.isAccessible = true
                    mApply?.invoke(builder)
                }
            }
            return
        }

        suppressRoundFrameNativeBlur(frame)
        if (curBg !is PopupLiquidGlassDrawable) {
            if (!originalBackgrounds.containsKey(frame)) {
                originalBackgrounds[frame] = curBg
            }
            setBackgroundPreservePadding(frame, PopupLiquidGlassDrawable(frame, curBg))
            if (loggedBind.compareAndSet(false, true)) {
                HookLog.ok(
                    "[$TAG] 已绑定桌面长按小浮窗 RoundFrameLayout (${frame.width}x${frame.height})，抑制 COUI 原生模糊覆层"
                )
            }
        } else {
            curBg.invalidateSelf()
        }
    }

    private fun setBackgroundPreservePadding(view: View, drawable: Drawable?) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        view.background = drawable
        view.setPadding(pl, pt, pr, pb)
    }

    private fun resolveFrameCornerRadiusPx(view: View, rect: RectF): Float {
        val density = view.resources.displayMetrics.density
        var base = 0f
        runCatching {
            val sx = fRoundSmoothCornerRadius?.getFloat(view) ?: 0f
            val rx = fRoundRadius?.getFloat(view) ?: 0f
            val dx = fRoundDimenRadius?.getFloat(view) ?: 0f
            base = when {
                sx > 1f -> sx
                rx > 1f -> rx
                dx > 1f -> dx
                else -> 0f
            }
        }
        if (base <= 1f) {
            val out = Outline()
            runCatching {
                view.outlineProvider?.getOutline(view, out)
                if (out.radius > 1f) base = out.radius
            }
        }
        if (base <= 1f) {
            base = 22f * density
        }
        val maxR = min(rect.width(), rect.height()) * 0.5f
        return base.coerceIn(4f, max(4f, maxR))
    }

    private fun invalidateSnapshot() {
        cachedSnapshotShader = null
        cachedSnapshotBlurPx = Float.NaN
    }

    private fun clearSnapshot() {
        invalidateSnapshot()
        cachedSnapshot?.rawBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
        cachedSnapshot = null
        cachedBlurredBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
        cachedBlurredBitmap = null
    }

    private fun computeViewToScreenMatrix(
        view: View,
        out: Matrix,
        locScr: IntArray,
        locWin: IntArray
    ) {
        out.reset()
        view.transformMatrixToGlobal(out)
        view.getLocationOnScreen(locScr)
        view.getLocationInWindow(locWin)
        out.postTranslate(
            (locScr[0] - locWin[0]).toFloat(),
            (locScr[1] - locWin[1]).toFloat()
        )
    }

    private fun ensureBackdropShader(view: View): BitmapShader? {
        val density = view.resources.displayMetrics.density.coerceIn(1.5f, 4.5f)
        val sliderDp = blurRadiusProvider().coerceIn(2f, 32f)
        // PopupBlurView 背景本身自带较强高斯模糊；这里以背景模糊量作为基线再叠加第 2 组模糊滑块，
        // 使默认 10dp 时弹窗模糊度与背景持平或略高，同时保留滑块调节梯度。
        val targetDp = 12f + sliderDp * 0.55f
        val desiredBlur = (targetDp * density * CAPTURE_SCALE).coerceIn(6f, 48f)

        val dragLayer = lastDragLayerRef?.get()
        val dm = view.resources.displayMetrics
        val expectedDlW = if (dragLayer != null && dragLayer.width > 0) dragLayer.width else dm.widthPixels
        val expectedDlH = if (dragLayer != null && dragLayer.height > 0) dragLayer.height else dm.heightPixels
        val expectedCapW = max(1, (expectedDlW * CAPTURE_SCALE).toInt())
        val expectedCapH = max(1, (expectedDlH * CAPTURE_SCALE).toInt())

        val validSnap = cachedSnapshot?.takeIf {
            !it.rawBitmap.isRecycled && it.capW == expectedCapW && it.capH == expectedCapH
        }
        if (validSnap != null) {
            cachedSnapshotShader?.let { existing ->
                if (kotlin.math.abs(cachedSnapshotBlurPx - desiredBlur) < 0.5f) {
                    return existing
                }
            }
        }

        val snap = validSnap ?: run {
            val pbv = lastBlurViewRef?.get()
            val built = if (dragLayer != null) {
                captureCleanSceneSnapshot(view.context, dragLayer, pbv)
            } else {
                captureFallbackSceneSnapshot(view.context, pbv, expectedCapW, expectedCapH, expectedDlW, expectedDlH)
            } ?: return null
            cachedSnapshot?.rawBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
            cachedSnapshot = built
            built
        }

        val blurred = blurBitmapHardware(snap.rawBitmap, desiredBlur) ?: snap.rawBitmap
        if (blurred !== cachedBlurredBitmap) {
            cachedBlurredBitmap?.let { old ->
                if (old !== snap.rawBitmap && !old.isRecycled) runCatching { old.recycle() }
            }
            cachedBlurredBitmap = blurred
        }
        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        cachedSnapshotShader = shader
        cachedSnapshotBlurPx = desiredBlur
        return shader
    }

    private fun prepareWorkspaceForCapture(context: Context) {
        runCatching {
            val activity = mBaseActivityFromContext?.invoke(null, context) ?: return@runCatching
            val workspace = mLauncherGetWorkspace?.invoke(activity) ?: return@runCatching
            mRestoreHwLayerBeforeBlur?.invoke(workspace)
            mDisableChildrenHwLayers?.invoke(workspace)
        }
    }

    private fun drawBlendScrims(canvas: Canvas, darkBlend: Int, wallBlend: Int) {
        if (darkBlend != 0) canvas.drawColor(darkBlend)
        if (wallBlend != 0) canvas.drawColor(wallBlend)
    }

    private fun drawBitmapSafe(
        canvas: Canvas,
        bmp: Bitmap,
        destW: Int,
        destH: Int,
        centerCrop: Boolean,
        hardwareCanvas: Boolean
    ): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return false
        var drawnBmp = bmp
        var tempSw: Bitmap? = null
        if (!hardwareCanvas && bmp.config == Bitmap.Config.HARDWARE) {
            tempSw = runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                ?: return false
            drawnBmp = tempSw
        }
        try {
            if (centerCrop && (drawnBmp.width != destW || drawnBmp.height != destH)) {
                val scale = max(destW.toFloat() / drawnBmp.width, destH.toFloat() / drawnBmp.height)
                val dx = (destW - drawnBmp.width * scale) * 0.5f
                val dy = (destH - drawnBmp.height * scale) * 0.5f
                canvas.drawBitmap(
                    drawnBmp,
                    null,
                    RectF(dx, dy, dx + drawnBmp.width * scale, dy + drawnBmp.height * scale),
                    bitmapCopyPaint
                )
            } else {
                canvas.drawBitmap(drawnBmp, null, Rect(0, 0, destW, destH), bitmapCopyPaint)
            }
            return true
        } finally {
            if (tempSw != null && !tempSw.isRecycled) {
                runCatching { tempSw.recycle() }
            }
        }
    }

    private fun drawDrawableSafe(
        canvas: Canvas,
        drawable: Drawable,
        w: Int,
        h: Int,
        hardwareCanvas: Boolean
    ): Boolean {
        val bmp = (drawable as? BitmapDrawable)?.bitmap
        if (bmp != null && !bmp.isRecycled) {
            return drawBitmapSafe(canvas, bmp, w, h, centerCrop = true, hardwareCanvas = hardwareCanvas)
        }
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return true
    }

    private fun queryOplusWallpaperBitmap(context: Context, dlW: Int, dlH: Int): Bitmap? {
        val cls = cOplusWallpaperManager ?: return null
        val m = mOplusGetWallpaperBitmap ?: return null
        return runCatching {
            val mgr = cls.getDeclaredConstructor().newInstance()
            val pts = arrayListOf(Point(max(1, dlW), max(1, dlH)))
            val appCtx = context.applicationContext ?: context
            val userId = runCatching {
                UserHandle::class.java.getMethod("myUserId").invoke(null) as? Int
            }.getOrNull() ?: 0
            (m.invoke(mgr, appCtx, pts, 1, userId) as? Bitmap)?.takeIf {
                !it.isRecycled && it.width > 0 && it.height > 0
            }
        }.getOrNull()
    }

    private fun toArgb8888(bmp: Bitmap, recycleSrcIfCopied: Boolean): Bitmap {
        if (bmp.config != Bitmap.Config.HARDWARE) return bmp
        val sw = runCatching {
            bmp.copy(Bitmap.Config.ARGB_8888, false)?.apply {
                density = Bitmap.DENSITY_NONE
            }
        }.getOrNull()
        if (sw != null && recycleSrcIfCopied && !bmp.isRecycled) {
            runCatching { bmp.recycle() }
        }
        return sw ?: bmp
    }

    private fun captureCleanSceneSnapshot(
        context: Context,
        dragLayer: View,
        pbv: View?
    ): SceneSnapshot? = runCatching {
        val dm = context.resources.displayMetrics
        val dlW = if (dragLayer.width > 0) dragLayer.width else dm.widthPixels
        val dlH = if (dragLayer.height > 0) dragLayer.height else dm.heightPixels
        val w = max(1, (dlW * CAPTURE_SCALE).toInt())
        val h = max(1, (dlH * CAPTURE_SCALE).toInt())

        val dlToScreen = Matrix()
        computeViewToScreenMatrix(dragLayer, dlToScreen, capScrTmp, capWinTmp)
        val screenToDl = Matrix()
        if (!dlToScreen.invert(screenToDl)) {
            screenToDl.reset()
        }

        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xFF14171C.toInt() else 0xFFE8ECF2.toInt()
        val darkBlend = runCatching { pbv?.let { fPopupWallpaperDarkBlendColor?.getInt(it) } }.getOrNull() ?: 0
        val wallBlend = runCatching { pbv?.let { fPopupWallpaperBlendColor?.getInt(it) } }.getOrNull() ?: 0

        var rawDragBmp: Bitmap? = null
        if (dragLayer.width > 0 && dragLayer.height > 0) {
            prepareWorkspaceForCapture(context)
            val prevVis = pbv?.visibility
            try {
                if (pbv != null && pbv.visibility != View.INVISIBLE) {
                    pbv.visibility = View.INVISIBLE
                }
                // ColorOS BitmapUtils.viewToBitmapByRenderer(v, scaleRate) 仅裁剪宽/高而不缩放 Canvas，
                // 传入 0.5f 会只截取左上 1/4 原尺寸画面并在绘制到全屏缓冲时被放大约 2 倍；
                // 必须以 1.0f 采集完整 DragLayer 后再双线性缩放到 0.5x。
                rawDragBmp = runCatching {
                    mViewToBitmap?.invoke(null, dragLayer, 1.0f) as? Bitmap
                }.getOrNull()
            } finally {
                if (pbv != null && prevVis != null && pbv.visibility != prevVis) {
                    pbv.visibility = prevVis
                }
            }
        }

        // 优先全程走 GPU RenderNode 合成壁纸（含动态壁纸截屏与 PopupBlurView 混色层）和 1.0x DragLayer，避免 14MB CPU 拷回
        val hwCreate = mCreateHardwareBitmap
        var out: Bitmap? = null
        var wallRes = WallpaperDrawResult(false, "none")
        if (hwCreate != null && (rawDragBmp == null || !rawDragBmp.isRecycled)) {
            out = runCatching {
                val node = RenderNode("c17-popup-cap")
                node.setPosition(0, 0, w, h)
                val rec = node.beginRecording(w, h)
                rec.drawColor(bgColor)
                wallRes = drawWallpaperLayer(context, rec, w, h, dlW, dlH, pbv, hardwareCanvas = true)
                drawBlendScrims(rec, darkBlend, wallBlend)
                if (rawDragBmp != null && !rawDragBmp.isRecycled) {
                    rec.drawBitmap(rawDragBmp, null, Rect(0, 0, w, h), bitmapCopyPaint)
                } else if (dragLayer.width > 0 && dragLayer.height > 0) {
                    val sx = w.toFloat() / dragLayer.width.toFloat()
                    val sy = h.toFloat() / dragLayer.height.toFloat()
                    val save = rec.save()
                    rec.scale(sx, sy)
                    dragLayer.draw(rec)
                    rec.restoreToCount(save)
                }
                node.endRecording()
                (hwCreate.invoke(null, node, w, h) as? Bitmap)?.apply {
                    density = Bitmap.DENSITY_NONE
                }
            }.getOrNull()
        }

        if (out == null) {
            val swOut = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                density = Bitmap.DENSITY_NONE
            }
            val canvas = Canvas(swOut)
            canvas.drawColor(bgColor)
            wallRes = drawWallpaperLayer(context, canvas, w, h, dlW, dlH, pbv, hardwareCanvas = false)
            drawBlendScrims(canvas, darkBlend, wallBlend)
            if (rawDragBmp != null && !rawDragBmp.isRecycled) {
                drawBitmapSafe(canvas, rawDragBmp, w, h, centerCrop = false, hardwareCanvas = false)
            } else if (dragLayer.width > 0 && dragLayer.height > 0) {
                val sx = w.toFloat() / dragLayer.width.toFloat()
                val sy = h.toFloat() / dragLayer.height.toFloat()
                val save = canvas.save()
                canvas.scale(sx, sy)
                dragLayer.draw(canvas)
                canvas.restoreToCount(save)
            }
            out = swOut
        }

        if (rawDragBmp != null && !rawDragBmp.isRecycled) {
            runCatching { rawDragBmp.recycle() }
        }

        val finalOut = out ?: return@runCatching null
        if (loggedCapture.compareAndSet(false, true)) {
            HookLog.ok(
                "[$TAG] 浮窗背景快照=${wallRes.source} (${w}x${h}, " +
                    "dark=0x${Integer.toHexString(darkBlend)}, blend=0x${Integer.toHexString(wallBlend)})"
            )
        }
        SceneSnapshot(
            rawBitmap = finalOut,
            capW = w,
            capH = h,
            dragLayerW = dlW,
            dragLayerH = dlH,
            screenToDragLayer = screenToDl,
            backdropScaleX = w.toFloat() / dlW.toFloat(),
            backdropScaleY = h.toFloat() / dlH.toFloat(),
            hasWallpaper = wallRes.drew
        )
    }.getOrNull()

    private fun captureFallbackSceneSnapshot(
        context: Context,
        pbv: View?,
        w: Int,
        h: Int,
        dlW: Int,
        dlH: Int
    ): SceneSnapshot? = runCatching {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
        val canvas = Canvas(out)
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        canvas.drawColor(if (isNight) 0xFF14171C.toInt() else 0xFFE8ECF2.toInt())

        val wallRes = drawWallpaperLayer(context, canvas, w, h, dlW, dlH, pbv, hardwareCanvas = false)
        val darkBlend = runCatching { pbv?.let { fPopupWallpaperDarkBlendColor?.getInt(it) } }.getOrNull() ?: 0
        val wallBlend = runCatching { pbv?.let { fPopupWallpaperBlendColor?.getInt(it) } }.getOrNull() ?: 0
        drawBlendScrims(canvas, darkBlend, wallBlend)

        val dlDrawable = runCatching { pbv?.let { fPopupBlurDragLayer?.get(it) as? Drawable } }.getOrNull()
        if (dlDrawable != null) {
            drawDrawableSafe(canvas, dlDrawable, w, h, hardwareCanvas = false)
        }

        SceneSnapshot(
            rawBitmap = out,
            capW = w,
            capH = h,
            dragLayerW = dlW,
            dragLayerH = dlH,
            screenToDragLayer = Matrix(),
            backdropScaleX = w.toFloat() / max(1, dlW).toFloat(),
            backdropScaleY = h.toFloat() / max(1, dlH).toFloat(),
            hasWallpaper = wallRes.drew
        )
    }.getOrNull()

    private fun drawWallpaperLayer(
        context: Context,
        canvas: Canvas,
        w: Int,
        h: Int,
        dlW: Int,
        dlH: Int,
        pbv: View?,
        hardwareCanvas: Boolean
    ): WallpaperDrawResult = runCatching {
        val wm = WallpaperManager.getInstance(context)
        val isLive = runCatching { wm.wallpaperInfo != null }.getOrDefault(false)
        val pbvDrawable = runCatching { pbv?.let { fPopupBlurWallpaper?.get(it) as? Drawable } }.getOrNull()

        if (isLive) {
            // 动态壁纸模式下 WallpaperManager.drawable 会返回错误的静态回退壁纸；
            // 优先通过 WindowManager 截取实时动态壁纸画面，其次复用 PopupBlurView 壁纸层。
            val liveShot = runCatching {
                val wmObj = iWindowManagerObj ?: return@runCatching null
                (mScreenshotWallpaper?.invoke(wmObj) as? Bitmap)?.takeIf {
                    !it.isRecycled && it.width > 0 && it.height > 0
                }
            }.getOrNull()
            if (liveShot != null) {
                try {
                    if (drawBitmapSafe(canvas, liveShot, w, h, centerCrop = true, hardwareCanvas = hardwareCanvas)) {
                        return@runCatching WallpaperDrawResult(true, "live:screenshotWallpaper")
                    }
                } finally {
                    if (!liveShot.isRecycled) runCatching { liveShot.recycle() }
                }
            }
            if (pbvDrawable != null && drawDrawableSafe(canvas, pbvDrawable, w, h, hardwareCanvas)) {
                return@runCatching WallpaperDrawResult(true, "live:pbvDrawable")
            }
            val oplusBmp = queryOplusWallpaperBitmap(context, dlW, dlH)
            if (oplusBmp != null) {
                try {
                    if (drawBitmapSafe(canvas, oplusBmp, w, h, centerCrop = true, hardwareCanvas = hardwareCanvas)) {
                        return@runCatching WallpaperDrawResult(true, "live:oplusWm")
                    }
                } finally {
                    if (!oplusBmp.isRecycled) runCatching { oplusBmp.recycle() }
                }
            }
            return@runCatching WallpaperDrawResult(false, "live:none")
        }

        val wallId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        var wallBmp = cachedWallBitmap?.takeIf {
            !it.isRecycled && cachedWallId == wallId && it.width == w && it.height == h
        }
        var source = "static:cache"
        if (wallBmp == null) {
            val built = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                density = Bitmap.DENSITY_NONE
            }
            val c = Canvas(built)
            var populated = false
            val oplusBmp = queryOplusWallpaperBitmap(context, dlW, dlH)
            if (oplusBmp != null) {
                try {
                    populated = drawBitmapSafe(c, oplusBmp, w, h, centerCrop = true, hardwareCanvas = false)
                    if (populated) source = "static:oplusWm"
                } finally {
                    if (!oplusBmp.isRecycled) runCatching { oplusBmp.recycle() }
                }
            }
            if (!populated) {
                val d = wm.drawable
                if (d != null && drawDrawableSafe(c, d, w, h, hardwareCanvas = false)) {
                    populated = true
                    source = "static:wmDrawable"
                }
            }
            if (!populated) {
                if (!built.isRecycled) runCatching { built.recycle() }
                if (pbvDrawable != null && drawDrawableSafe(canvas, pbvDrawable, w, h, hardwareCanvas)) {
                    return@runCatching WallpaperDrawResult(true, "static:pbvDrawable")
                }
                return@runCatching WallpaperDrawResult(false, "static:none")
            }
            cachedWallBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
            cachedWallBitmap = built
            cachedWallId = wallId
            wallBmp = built
        }
        canvas.drawBitmap(wallBmp, null, Rect(0, 0, w, h), bitmapCopyPaint)
        WallpaperDrawResult(true, source)
    }.getOrDefault(WallpaperDrawResult(false, "error"))

    private fun blurBitmapHardware(src: Bitmap, radiusPx: Float): Bitmap? {
        if (radiusPx <= 0.5f) return toArgb8888(src, recycleSrcIfCopied = false)
        val hwCreate = mCreateHardwareBitmap ?: return null
        return runCatching {
            val node = RenderNode("c17-popup-blur")
            node.setPosition(0, 0, src.width, src.height)
            node.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            val rec = node.beginRecording(src.width, src.height)
            rec.drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            val hwBmp = hwCreate.invoke(null, node, src.width, src.height) as? Bitmap ?: return@runCatching null
            hwBmp.density = Bitmap.DENSITY_NONE
            // 仅把 0.5x 模糊结果转为 ARGB_8888，规避 widecg HARDWARE 位图跨窗口 RuntimeShader 采样偏色
            toArgb8888(hwBmp, recycleSrcIfCopied = true)
        }.getOrNull()
    }

    private class PopupLiquidGlassDrawable(
        view: View,
        private val original: Drawable?
    ) : Drawable(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private val hostRef = WeakReference(view)
        private var observerRef: WeakReference<ViewTreeObserver>? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val clipPath = Path()
        private val rectF = RectF()
        private val rimRectF = RectF()
        private val matrixTmp = Matrix()
        private val matrixVals = FloatArray(9)
        private val preDrawVals = FloatArray(9)
        private val prevDrawVals = FloatArray(9) { Float.NaN }
        private val scrTmp = IntArray(2)
        private val winTmp = IntArray(2)
        private var shader: RuntimeShader? = null
        private var shaderRejected = false
        private var alphaVal = 255
        private var lastRadiusPx = 0f

        init {
            view.addOnAttachStateChangeListener(this)
            ensureObserving()
        }

        private fun ensureObserving() {
            val v = hostRef.get() ?: return
            val vto = v.viewTreeObserver
            if (!vto.isAlive || observerRef?.get() === vto) return
            stopObserving()
            vto.addOnPreDrawListener(this)
            observerRef = WeakReference(vto)
        }

        private fun stopObserving() {
            val old = observerRef?.get()
            if (old != null && old.isAlive) {
                runCatching { old.removeOnPreDrawListener(this) }
            }
            observerRef = null
        }

        override fun onViewAttachedToWindow(v: View) {
            ensureObserving()
        }

        override fun onViewDetachedFromWindow(v: View) {
            stopObserving()
            if (v.background !== this) {
                v.removeOnAttachStateChangeListener(this)
            }
        }

        override fun onPreDraw(): Boolean {
            val v = hostRef.get()
            if (v == null || v.background !== this) {
                stopObserving()
                return true
            }
            if (!isGlassActive() || v.width <= 0 || v.height <= 0) return true
            computeSceneMatrix(v, matrixTmp, preDrawVals)
            var changed = false
            for (i in 0 until 9) {
                if (kotlin.math.abs(preDrawVals[i] - prevDrawVals[i]) > 0.002f) {
                    changed = true
                    break
                }
            }
            if (changed) {
                System.arraycopy(preDrawVals, 0, prevDrawVals, 0, 9)
                v.invalidate()
            }
            return true
        }

        private fun computeSceneMatrix(view: View, m: Matrix, outVals: FloatArray) {
            computeViewToScreenMatrix(view, m, scrTmp, winTmp)
            val snap = cachedSnapshot
            if (snap != null) {
                m.postConcat(snap.screenToDragLayer)
                m.postScale(snap.backdropScaleX, snap.backdropScaleY)
            } else {
                m.postScale(CAPTURE_SCALE, CAPTURE_SCALE)
            }
            m.getValues(outVals)
        }

        private fun resolveDrawRect(view: View): Boolean {
            val animRect = runCatching { fRoundAnimRect?.get(view) as? Rect }.getOrNull()
            if (animRect != null && !animRect.isEmpty) {
                rectF.set(animRect)
            } else if (!bounds.isEmpty) {
                rectF.set(bounds)
            } else {
                val rf = runCatching { fRoundRectF?.get(view) as? RectF }.getOrNull()
                if (rf != null && !rf.isEmpty) {
                    rectF.set(rf)
                } else {
                    rectF.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                }
            }
            return rectF.width() > 2f && rectF.height() > 2f
        }

        override fun draw(canvas: Canvas) {
            val view = hostRef.get()
            if (view == null || view.width <= 0 || view.height <= 0 || !canvas.isHardwareAccelerated) {
                original?.apply {
                    bounds = this@PopupLiquidGlassDrawable.bounds
                    draw(canvas)
                }
                return
            }

            val p = paramsProvider()
            if (!isGlobalEnabled() || !p.enabled) {
                original?.apply {
                    bounds = this@PopupLiquidGlassDrawable.bounds
                    draw(canvas)
                }
                return
            }

            if (!resolveDrawRect(view)) return

            val backdropShader = ensureBackdropShader(view)
            if (backdropShader == null) {
                original?.apply {
                    bounds = this@PopupLiquidGlassDrawable.bounds
                    draw(canvas)
                }
                return
            }

            if (shader == null && !shaderRejected) {
                val cached = sharedRuntimeShader
                if (cached != null) {
                    shader = cached
                } else if (!sharedShaderRejected) {
                    runCatching {
                        val compiled = RuntimeShader(POPUP_GLASS_AGSL)
                        sharedRuntimeShader = compiled
                        shader = compiled
                    }.onFailure {
                        shaderRejected = true
                        sharedShaderRejected = true
                        HookLog.warn("[$TAG] Popup RuntimeShader 编译失败：${it.message}")
                    }
                } else {
                    shaderRejected = true
                }
            }
            val rt = shader
            if (rt == null) {
                original?.apply {
                    bounds = this@PopupLiquidGlassDrawable.bounds
                    draw(canvas)
                }
                return
            }

            val baseRadius = resolveFrameCornerRadiusPx(view, rectF)
            val radius = (baseRadius * p.cornerScale).coerceIn(4f, min(rectF.width(), rectF.height()) * 0.5f)
            lastRadiusPx = radius

            computeSceneMatrix(view, matrixTmp, matrixVals)
            System.arraycopy(matrixVals, 0, prevDrawVals, 0, 9)

            val isNight = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

            rt.setInputShader("sceneBackdrop", backdropShader)
            rt.setFloatUniform("uRect", rectF.left, rectF.top, rectF.right, rectF.bottom)
            rt.setFloatUniform("uRadius", radius)
            rt.setFloatUniform("uBand", (p.bandRaw * 0.90f).coerceIn(8f, 96f))
            rt.setFloatUniform("uRefraction", (p.refractionAmt * 0.85f).coerceIn(0f, 200f))
            rt.setFloatUniform("uRim", p.rimStrength)
            if (isNight) {
                rt.setFloatUniform("uTint", 0.14f, 0.16f, 0.20f, 0.08f)
            } else {
                rt.setFloatUniform("uTint", 0.98f, 0.99f, 1.00f, 0.10f)
            }
            rt.setFloatUniform("uRow0", matrixVals[0], matrixVals[1], matrixVals[2])
            rt.setFloatUniform("uRow1", matrixVals[3], matrixVals[4], matrixVals[5])
            rt.setFloatUniform("uRow2", matrixVals[6], matrixVals[7], matrixVals[8])

            clipPath.reset()
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)

            val save = canvas.save()
            try {
                canvas.clipPath(clipPath)
                paint.alpha = alphaVal
                paint.shader = rt
                canvas.drawRoundRect(rectF, radius, radius, paint)
                paint.shader = null
                if (loggedDraw.compareAndSet(false, true)) {
                    HookLog.ok(
                        "[$TAG] 长按小浮窗 AGSL 首帧渲染完成 (${rectF.width().toInt()}x${rectF.height().toInt()}, " +
                            "r=${radius.toInt()}px, ref=${p.refractionAmt.toInt()})"
                    )
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }

        fun drawRimOverlay(canvas: Canvas) {
            val view = hostRef.get() ?: return
            if (alphaVal <= 4 || !canvas.isHardwareAccelerated) return
            val p = paramsProvider()
            if (!isGlobalEnabled() || !p.enabled || p.rimStrength <= 0.005f) return
            if (!resolveDrawRect(view)) return

            val density = view.resources.displayMetrics.density
            val strokePx = (1.35f * density).coerceIn(1.5f, 5.5f)
            val inset = strokePx * 0.5f
            rimRectF.set(
                rectF.left + inset,
                rectF.top + inset,
                rectF.right - inset,
                rectF.bottom - inset
            )
            if (rimRectF.width() <= 2f || rimRectF.height() <= 2f) return
            val r = max(2f, lastRadiusPx - inset)
            val isNight = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val af = (alphaVal / 255f) * p.rimStrength.coerceIn(0f, 1.2f)
            val hiA = ((if (isNight) 138f else 225f) * af).toInt().coerceIn(0, 255)
            val midA = ((if (isNight) 28f else 56f) * af).toInt().coerceIn(0, 255)
            val loA = ((if (isNight) 72f else 142f) * af).toInt().coerceIn(0, 255)

            rimPaint.strokeWidth = strokePx
            rimPaint.shader = LinearGradient(
                rimRectF.left,
                rimRectF.top,
                rimRectF.right,
                rimRectF.bottom,
                intArrayOf(
                    Color.argb(hiA, 255, 255, 255),
                    Color.argb(midA, 255, 255, 255),
                    Color.argb(loA, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rimRectF, r, r, rimPaint)
            rimPaint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            alphaVal = alpha.coerceIn(0, 255)
            original?.alpha = alphaVal
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            original?.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
