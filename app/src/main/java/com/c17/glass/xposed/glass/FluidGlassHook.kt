package com.c17.glass.xposed.glass

import android.content.Context
import android.content.res.Resources
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.View
import com.c17.glass.xposed.statusbar.HookConfig
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.StatusbarHook
import com.c17.glass.xposed.statusbar.findField
import com.c17.glass.xposed.statusbar.findMethod
import com.c17.glass.xposed.statusbar.findMethodsNamed
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorOS 17 控制中心与通知中心原生 AGSL 液态玻璃 Hook。
 *
 * 与实机验证通过的渲染管线对齐：
 *  1. 拦截 `BlurDrawableShaderBaseStringKt.buildShaderString` 与 `RuntimeShader` 构造，
 *     向 ColorOS 原生 `com.oplus.posteffect` 着色器注入曲面内向折射采样与边缘高光曲线；
 *  2. Hook `DrawableShader.setBaseUniform` / `setSize` 实时下发 `u_c17` / `u_c17_rim` / `u_c17_source_scale`；
 *  3. 保留系统控制中心下拉缩放（NotifiAndQsPlatformBlurExKt / WallpaperManager 原生动画），不截断面板缩放；
 *  4. 同步 `BlurDrawableManager.onBlurReady` 四分之一缓冲采样倍率，修正 `MixColorTileDrawable` 宽磁贴与滑块；
 *  5. 覆盖控制中心磁贴、音量/亮度滑块、媒体卡、独立设备卡及通知中心卡片。
 */
object FluidGlassHook : StatusbarHook {
    override val id = "fluid-glass"
    override val label = "ColorOS 17 液态玻璃（控制中心 + 通知中心）"

    private const val CLS_BASE_SHADER_KT = "com.oplus.posteffect.agsl.BlurDrawableShaderBaseStringKt"
    private const val CLS_DRAWABLE_SHADER = "com.oplus.posteffect.agsl.DrawableShader"
    private const val CLS_CORNER_PARAMS = "com.oplus.posteffect.params.CornerParams"
    private const val CLS_RUNTIME_SHADER = "android.graphics.RuntimeShader"
    private const val CLS_BLUR_MANAGER = "com.oplus.posteffect.manager.BlurDrawableManager"
    private const val CLS_BASE_DRAWABLE = "com.oplus.posteffect.drawable.BaseDrawable"
    private const val CLS_IMAGE_INFO = "$CLS_BASE_DRAWABLE\$ImageInfo"
    private const val CLS_BLUR_DRAWABLE = "com.oplus.posteffect.BlurDrawable"
    private const val CLS_CONTINUOUS_BLUR = "com.oplus.posteffect.drawable.ContinuousBlurDrawable"
    private const val CLS_BLEND_DRAWABLE = "com.oplus.posteffect.drawable.BlendDrawable"
    private const val CLS_BLUR_PARAM = "com.oplus.posteffect.BlurParam"

    private const val CLS_VIEW_BLUR_PROXY = "com.oplusos.systemui.common.blurability.ViewBlurProxy"
    private const val CLS_PLATFORM_BLUR_DRAWABLE = "com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable"
    private const val CLS_QS_BLUR_PROVIDER = "com.oplusos.systemui.common.util.QSBlurConfigProvider"
    private const val CLS_NOTIF_BLUR_MANAGER = "com.oplus.systemui.notification.blur.NotificationPlatFormBlurParamsManager"
    private const val CLS_VIEW_BLUR_MANAGER = "com.oplus.systemui.notification.blur.ViewBlurManager"
    private const val CLS_FULLSCREEN_BANNER_CONTAINER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer"

    private const val CLS_MIX_TILE = "com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable"
    private const val CLS_MIX_TILE_BUILDER = "$CLS_MIX_TILE\$Builder"
    private const val CLS_TILE_TRANSITION = "com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable"
    private const val CLS_TILE_FLAG = "com.oplus.systemui.qs.base.res.model.TileViewFlag"
    private const val CLS_TILE_RESIZEABLE = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView"
    private const val CLS_TILE_RESIZEABLE_TWO_ONE = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne"
    private const val CLS_QS_HIGHLIGHT_TILE = "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView"
    private const val CLS_QS_SEEKBAR_MANAGER = "com.oplus.systemui.qs.base.util.QsSeekBarBlurManager"
    private const val CLS_QS_SEEKBAR = "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"
    private const val CLS_VOLUME_SEEKBAR = "com.oplus.systemui.volume.OplusVolumeSeekBar"
    private const val CLS_CLIP_BRIGHTNESS = "com.oplus.systemui.qs.base.seek.ClipBrightnessView"
    private const val CLS_QS_COLOR_UTIL = "com.oplus.systemui.qs.base.util.QsColorUtil"
    private const val CLS_NOTIF_BG_EXT = "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp"
    private const val CLS_EXPANDABLE_ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val CLS_FOREGROUND_BLUR_PARAM = "com.oplus.posteffect.ForegroundBlurParam"

    /** 固定调校：仅轻微增强控制中心折射；音量与桌面各自独立滑块控制。 */
    private const val CONTROL_CENTER_REFRACTION_MULTIPLIER = 1.06f

    private enum class SurfaceKind {
        QS,
        NOTIFICATION,
        VOLUME,
        OTHER
    }

    private val EXCLUDED_QS_BUTTON_IDS = arrayOf(
        "qs_edit_button",
        "quicksettings_settings_button",
        "quicksettings_settings_button_remote",
        "quicksettings_settings_button_icon"
    )

    @Volatile
    private var controlCenterEnabled: Boolean = true

    @Volatile
    private var notificationsEnabled: Boolean = true

    @Volatile
    private var volumeEnabled: Boolean = true

    @Volatile
    private var applyGloballyEnabled: Boolean = true

    @Volatile
    private var shaderParams: NativeGlassShader.GlassShaderParams =
        NativeGlassShader.GlassShaderParams.from(HookConfig(null), surfaceEnabled = false)

    @Volatile
    private var volumeShaderParams: NativeGlassShader.GlassShaderParams =
        NativeGlassShader.GlassShaderParams.fromVolume(HookConfig(null), surfaceEnabled = false)

    @Volatile
    private var rawBlurRadiusDp: Float = 24f

    @Volatile
    private var configRevision: Long = 1L

    @Volatile
    private var configWatcher: GlassConfigWatcher? = null

    private data class PaintFilterRecord(var captured: Boolean = false, var original: ColorFilter? = null)
    private data class BlurPaintRecord(
        val shaderPaint: PaintFilterRecord = PaintFilterRecord(),
        val bitmapPaint: PaintFilterRecord = PaintFilterRecord(),
        var lastSaturation: Float = Float.NaN,
        var cachedShaderPaintRef: WeakReference<Paint>? = null,
        var cachedBitmapPaintRef: WeakReference<Paint>? = null
    )

    // Weak associations: DrawableShader -> View, Drawable -> View, DrawableShader -> sourceScale, Paint & Blur tracking
    private val shaderToView = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
    private val drawableToView = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
    private val shaderToSurface = Collections.synchronizedMap(WeakHashMap<Any, SurfaceKind>())
    private val drawableToSurface = Collections.synchronizedMap(WeakHashMap<Any, SurfaceKind>())
    private val shaderSourceScale = Collections.synchronizedMap(WeakHashMap<Any, Float>())
    private val scaledBlurParams = Collections.synchronizedMap(WeakHashMap<Any, Float>())
    private val qsOwnedProxies = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val qsOwnedDrawables = Collections.synchronizedMap(WeakHashMap<Any, BlurPaintRecord>())
    private val headsUpBannerViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val headsUpLogged = AtomicBoolean(false)
    private val paintOwnedShaders = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val mixTileFilters = Collections.synchronizedMap(WeakHashMap<Any, PaintFilterRecord>())
    private val mixTileBoundRev = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val mixTilePaintCache = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Paint>>())
    private val mixTileNormalizedKey = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val drawableSyncRev = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val dsSyncedStateKey = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val dsExcludedCache = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val blurScaleCache = Collections.synchronizedMap(WeakHashMap<Any, Float>())
    private val dsBaseCornerParams = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val dsLastCornerScale = Collections.synchronizedMap(WeakHashMap<Any, Float>())
    private val activeContinuousBlur = ThreadLocal<ArrayDeque<Any>>()
    private val cornerGuard = ThreadLocal<Boolean>()
    private val wideTileInternal = ThreadLocal<Boolean>()
    private val sunColorLogged = AtomicBoolean(false)

    @Volatile
    private var cachedSatValue: Float = Float.NaN

    @Volatile
    private var cachedSatFilter: ColorFilter? = null

    @Volatile
    private var toFloatArrayBlurInstalled = false

    // Cached reflection handles
    private var fDsShader: Field? = null
    private var fDsWidth: Field? = null
    private var fDsHeight: Field? = null
    private var fDsDrawWidth: Field? = null
    private var fDsDrawHeight: Field? = null
    private var mDsSetCornerParam: Method? = null
    private var mDsSetCornerShader: Method? = null
    private var fCpRadius: Field? = null
    private var fCpType: Field? = null
    private var fCpWeight: Field? = null
    private var cCornerParams: Constructor<*>? = null
    private var fProxyView: Field? = null
    private var mProxyGetBlurDrawable: Method? = null
    private var mProxyGetStaticBlurDrawable: Method? = null
    private var mGetDrawableShader: Method? = null
    private var mGetShaderDirect: Method? = null
    private var mGetDrawableShaderPaint: Method? = null
    private var mGetPaint: Method? = null
    private var mSetContentDirty: Method? = null
    private var mSyncBlurParams: Method? = null
    private var fPbdBlurDrawable: Field? = null
    private var fBaseDrawingImageInfo: Field? = null
    private var fBaseDrawableShader: Field? = null
    private var fImageInfoScale: Field? = null
    private var fBlurRadius: Field? = null
    private var fMixPaint: Field? = null
    private var fMixAutoBlurDrawable: Field? = null
    private var excludedViewIdsCache: IntArray? = null
    private val autoBlurGetProxyMethods = Collections.synchronizedMap(WeakHashMap<Class<*>, Method>())
    private val staticBlurGetDrawableMethods = Collections.synchronizedMap(WeakHashMap<Class<*>, Method>())
    private val transSetAlphaMethods = Collections.synchronizedMap(WeakHashMap<Class<*>, Method>())
    private val transGetAlphaMethods = Collections.synchronizedMap(WeakHashMap<Class<*>, Method>())
    private val stateFieldCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())
    private val tempStateFieldCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())
    private val directStateFieldCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())
    private val volumeDrawableFieldsCache = Collections.synchronizedMap(WeakHashMap<Class<*>, List<Field>>())
    private var mAtomicBoolGet: Method? = null
    private var fRowIsHeadsUp: Field? = null
    private var fRowHeadsUpDisappear: Field? = null
    private var fRowPinnedStatus: Field? = null
    private var mRowPinnedStatusIsPinned: Method? = null

    override fun install(env: HookEnv) {
        if (!env.config.hasSystemUiGlass) {
            HookLog.info("[$id] 液态玻璃范围均未启用：不挂载 SystemUI 玻璃 hook")
            return
        }

        controlCenterEnabled = env.config.glassControlCenter
        notificationsEnabled = env.config.glassNotifications
        volumeEnabled = env.config.glassVolume
        applyGloballyEnabled = env.config.applyGlobally
        rawBlurRadiusDp = env.config.blurRadius
        shaderParams = NativeGlassShader.GlassShaderParams.from(
            env.config,
            surfaceEnabled = env.config.glassControlCenter || env.config.glassNotifications || env.config.applyGlobally
        )
        volumeShaderParams = NativeGlassShader.GlassShaderParams.fromVolume(env.config)
        val initialStamp = HookConfig.stamp()
        configWatcher = GlassConfigWatcher(initialStamp, minIntervalMs = 1_500L) { _, cfg, disabled ->
            applyReloadedConfig(cfg, disabled)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            configWatcher?.requestCheck(force = true)
        }, 1_200L)

        resolveCommonHandles(env)

        step("shader-injection") { hookShaderInjection(env) }
        step("shader-uniforms") { hookShaderUniforms(env) }
        step("corner-radius") { hookCornerRadius(env) }
        step("source-scale-sync") { hookSourceScaleSync(env) }
        step("blur-scale") { hookBlurRadiusPipeline(env) }
        step("qs-and-notif-surfaces") { hookQsAndNotificationSurfaces(env) }
        step("popup-glass") { PopupGlassHook.install(env, ::currentPopupConfig) }
        step("wide-tile-active") { hookMixColorTileWideActive(env) }
        step("brightness-sun-icon") { hookBrightnessSunIcon(env) }

        val p = shaderParams
        val vp = volumeShaderParams
        HookLog.ok(
            "[$id] C17 原生 AGSL 液态玻璃已挂载 " +
                "(cc=$controlCenterEnabled, notif=$notificationsEnabled, vol=$volumeEnabled, global=$applyGloballyEnabled, " +
                "qs[amt=${p.refractionAmt},blur=${p.blurScale},corner=${p.cornerScale}], " +
                "vol[amt=${vp.refractionAmt},blur=${vp.blurScale},corner=${vp.cornerScale}], toFloatArray=$toFloatArrayBlurInstalled)"
        )
    }

    private fun resolveCommonHandles(env: HookEnv) {
        val cpCls = env.findClass(CLS_CORNER_PARAMS)
        if (cpCls != null) {
            fCpRadius = cpCls.findField("radius")
            fCpType = cpCls.findField("type")
            fCpWeight = cpCls.findField("weight")
            cCornerParams = cpCls.declaredConstructors.firstOrNull { ctor ->
                val pts = ctor.parameterTypes
                pts.size == 3 &&
                    pts[1] == Float::class.javaPrimitiveType &&
                    pts[2] == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
        }
        env.findClass(CLS_DRAWABLE_SHADER)?.let { dsCls ->
            fDsShader = dsCls.findField("shader")
            fDsWidth = dsCls.findField("width")
            fDsHeight = dsCls.findField("height")
            fDsDrawWidth = dsCls.findField("drawWidth") ?: fDsWidth
            fDsDrawHeight = dsCls.findField("drawHeight") ?: fDsHeight
            mGetShaderDirect = dsCls.findMethod("getShaderDirect")
            if (cpCls != null) {
                mDsSetCornerParam = dsCls.findMethod("setCorner", cpCls)
                env.findClass(CLS_RUNTIME_SHADER)?.let { rsCls ->
                    mDsSetCornerShader = dsCls.findMethod("setCorner", rsCls, cpCls)
                }
            }
        }
        env.findClass(CLS_VIEW_BLUR_PROXY)?.let { proxyCls ->
            fProxyView = proxyCls.findField("view")
            mProxyGetBlurDrawable = proxyCls.findMethodsNamed("getBlurDrawable").firstOrNull { it.parameterCount == 0 }
            mProxyGetStaticBlurDrawable = proxyCls.findMethod("getCachePlatformStaticBlurDrawable")
        }
        env.findClass(CLS_PLATFORM_BLUR_DRAWABLE)?.let { pbdCls ->
            fPbdBlurDrawable = pbdCls.findField("blurDrawable")
        }
        env.findClass(CLS_BASE_DRAWABLE)?.let { bdCls ->
            fBaseDrawingImageInfo = bdCls.findField("drawingImageInfo")
            fBaseDrawableShader = bdCls.findField("drawableShader")
            mGetDrawableShader = bdCls.findMethod("getDrawableShader")
            mGetDrawableShaderPaint = bdCls.findMethod("getDrawableShaderPaint")
            mGetPaint = bdCls.findMethod("getPaint")
            mSetContentDirty = bdCls.findMethod("setContentDirty", Boolean::class.javaPrimitiveType!!)
        }
        env.findClass(CLS_IMAGE_INFO)?.let { infoCls ->
            fImageInfoScale = infoCls.findField("scale")
        }
        env.findClass(CLS_BLUR_PARAM)?.let { bpCls ->
            fBlurRadius = bpCls.findField("blurRadius")
        }
        env.findClass(CLS_MIX_TILE)?.let { mixCls ->
            fMixPaint = mixCls.findField("paint")
            fMixAutoBlurDrawable = mixCls.findField("autoBlurDrawable")
        }
        env.findClass(CLS_EXPANDABLE_ROW)?.let { rowCls ->
            fRowIsHeadsUp = rowCls.findField("mIsHeadsUp")
            fRowHeadsUpDisappear = rowCls.findField("mHeadsupDisappearRunning")
            fRowPinnedStatus = rowCls.findField("mPinnedStatus")
        }
    }

    // ─────────────────── 1. AGSL 着色器注入与 RuntimeShader 跟踪 ───────────────────

    private fun hookShaderInjection(env: HookEnv) {
        env.findClass(CLS_BASE_SHADER_KT)?.let { cls ->
            cls.findMethodsNamed("buildShaderString").forEach { m ->
                env.hookAfter(m, "$id:buildShaderString") { chain ->
                    maybeReloadConfig()
                    if (!anySystemUiSurfaceEnabled()) return@hookAfter
                    val sb = chain.args.getOrNull(0) as? StringBuilder ?: return@hookAfter
                    val src = sb.toString()
                    if (src.length < 128) return@hookAfter
                    val injected = NativeGlassShader.inject(src)
                    if (injected != src) {
                        sb.setLength(0)
                        sb.append(injected)
                    }
                }
            }
        }

        env.findClass(CLS_RUNTIME_SHADER)?.let { rsCls ->
            rsCls.declaredConstructors.forEach { ctor ->
                env.hookReplace(ctor, "$id:RuntimeShader.<init>") { chain ->
                    maybeReloadConfig()
                    val rawArgs = chain.args
                    var newArgs: Array<Any?>? = null
                    if (anySystemUiSurfaceEnabled() && rawArgs.isNotEmpty()) {
                        for (i in rawArgs.indices) {
                            val src = rawArgs[i] as? String ?: continue
                            if (src.length >= 128) {
                                val patched = when {
                                    NativeGlassShader.isTargetSource(src) -> NativeGlassShader.inject(src)
                                    NativeGlassShader.isPatchedSource(src) -> NativeGlassShader.patchLargeCardG2(src)
                                    else -> src
                                }
                                if (patched != src) {
                                    if (newArgs == null) newArgs = rawArgs.toTypedArray()
                                    newArgs[i] = patched
                                }
                            }
                        }
                    }
                    val result = if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                    NativeGlassShader.trackIfPatchedArgs(
                        chain.thisObject,
                        newArgs?.asList() ?: rawArgs
                    )
                    result
                }
            }
        }
    }

    // ─────────────────── 2. Uniform 推送与全屏/局部图层判别 ───────────────────

    private fun hookShaderUniforms(env: HookEnv) {
        val dsCls = env.requireClass(CLS_DRAWABLE_SHADER) ?: return

        dsCls.findMethodsNamed("setBaseUniform").forEach { m ->
            env.hookAfter(m, "$id:DrawableShader.setBaseUniform") { chain ->
                val ds = chain.thisObject ?: return@hookAfter
                refreshDrawableShaderUniforms(ds)
            }
        }

        dsCls.findMethodsNamed("setSize").forEach { m ->
            env.hookAfter(m, "$id:DrawableShader.setSize") { chain ->
                val ds = chain.thisObject ?: return@hookAfter
                refreshDrawableShaderUniforms(ds)
            }
        }
    }

    private fun hookCornerRadius(env: HookEnv) {
        val mSetCorner = mDsSetCornerParam ?: return
        env.hookReplace(mSetCorner, "$id:DrawableShader.setCorner") { chain ->
            if (cornerGuard.get() == true) {
                return@hookReplace chain.proceed()
            }
            val ds = chain.thisObject ?: return@hookReplace chain.proceed()
            val rawCp = chain.args.getOrNull(0) ?: return@hookReplace chain.proceed()
            dsBaseCornerParams[ds] = rawCp
            val scaledCp = buildScaledCornerParams(ds, rawCp)
            dsLastCornerScale[ds] = if (scaledCp === rawCp) 1.0f else paramsForSurface(classifyShaderSurface(ds)).cornerScale
            if (scaledCp === rawCp) {
                chain.proceed()
            } else {
                val newArgs = chain.args.toTypedArray()
                newArgs[0] = scaledCp
                chain.proceed(newArgs)
            }
        }
    }

    private fun currentPopupConfig(): PopupGlassHook.PopupConfig {
        maybeReloadConfig()
        val p = shaderParams
        val density = runCatching { Resources.getSystem().displayMetrics.density }.getOrDefault(3f)
        val blurPx = (rawBlurRadiusDp * density * 0.9f).roundToInt().coerceIn(6, 120)
        return PopupGlassHook.PopupConfig(
            enabled = p.enabled && applyGloballyEnabled,
            refractionAmt = p.refractionAmt,
            bandRaw = p.bandRaw,
            blurRadiusPx = blurPx,
            rimStrength = p.rimStrength,
            cornerScale = p.cornerScale,
            saturation = p.paintSaturation,
            revision = configRevision
        )
    }

    private fun anySystemUiSurfaceEnabled(): Boolean =
        shaderParams.enabled || volumeShaderParams.enabled

    private fun paramsForSurface(surface: SurfaceKind): NativeGlassShader.GlassShaderParams =
        when (surface) {
            SurfaceKind.VOLUME -> volumeShaderParams
            SurfaceKind.QS, SurfaceKind.NOTIFICATION, SurfaceKind.OTHER -> shaderParams
        }

    private fun buildScaledCornerParams(ds: Any, rawCp: Any): Any {
        maybeReloadConfig()
        val surface = classifyShaderSurface(ds)
        val p = paramsForSurface(surface)
        if (!p.enabled) return rawCp
        if (!isSurfaceAllowed(surface) || isFullScreenOrExcluded(ds)) return rawCp
        if (abs(p.cornerScale - 1.0f) < 0.01f) return rawCp

        val ctor = cCornerParams ?: return rawCp
        val rawRadius = runCatching { fCpRadius?.getFloat(rawCp) }.getOrNull() ?: return rawCp
        if (rawRadius <= 0.5f) return rawCp
        val type = runCatching { fCpType?.get(rawCp) }.getOrNull() ?: return rawCp
        val weight = runCatching { fCpWeight?.getFloat(rawCp) }.getOrNull() ?: return rawCp

        val w = runCatching { fDsDrawWidth?.getFloat(ds) ?: fDsWidth?.getFloat(ds) ?: 0f }.getOrDefault(0f)
        val h = runCatching { fDsDrawHeight?.getFloat(ds) ?: fDsHeight?.getFloat(ds) ?: 0f }.getOrDefault(0f)
        val maxRadius = if (w > 1f && h > 1f) (min(w, h) * 0.5f).coerceAtLeast(4f) else 160f
        val scaledRadius = (rawRadius * p.cornerScale).coerceIn(4f, maxRadius)
        if (abs(scaledRadius - rawRadius) < 0.25f) return rawCp

        return runCatching { ctor.newInstance(type, scaledRadius, weight) }.getOrDefault(rawCp)
    }

    private fun isSurfaceAllowed(surface: SurfaceKind): Boolean = when (surface) {
        SurfaceKind.QS -> controlCenterEnabled
        SurfaceKind.NOTIFICATION -> notificationsEnabled
        SurfaceKind.VOLUME -> volumeEnabled
        SurfaceKind.OTHER -> applyGloballyEnabled
    }

    private fun refreshDrawableShaderUniforms(drawableShader: Any) {
        maybeReloadConfig()
        val shader = runCatching { fDsShader?.get(drawableShader) }.getOrNull() ?: return
        if (!NativeGlassShader.isPatchedShader(shader)) return

        val surface = classifyShaderSurface(drawableShader)
        val params = paramsForSurface(surface)
        val allowSurface = params.enabled && isSurfaceAllowed(surface)
        val wNow = runCatching { fDsDrawWidth?.getFloat(drawableShader) ?: fDsWidth?.getFloat(drawableShader) ?: 0f }.getOrDefault(0f)
        val hNow = runCatching { fDsDrawHeight?.getFloat(drawableShader) ?: fDsHeight?.getFloat(drawableShader) ?: 0f }.getOrDefault(0f)
        val sizeBucket = if (wNow > 1f && hNow > 1f) ((wNow.toInt() and 0xFFFF) shl 16) or (hNow.toInt() and 0xFFFF) else 0
        val rev = configRevision
        val stateKey = (rev shl 32) xor sizeBucket.toLong()
        val cachedKey = dsSyncedStateKey[drawableShader]
        val bypass = if (cachedKey == stateKey && dsExcludedCache.containsKey(drawableShader)) {
            !allowSurface || (dsExcludedCache[drawableShader] == true)
        } else {
            val excluded = isFullScreenOrExcluded(drawableShader)
            dsExcludedCache[drawableShader] = excluded
            !allowSurface || excluded
        }

        if (cachedKey != stateKey) {
            dsSyncedStateKey[drawableShader] = stateKey
            val baseCp = dsBaseCornerParams[drawableShader]
            val desiredCornerScale = if (bypass) 1.0f else params.cornerScale
            val desiredKey = desiredCornerScale + sizeBucket * 0.00001f
            val prevCornerScale = dsLastCornerScale[drawableShader]
            if (baseCp != null && (prevCornerScale == null || abs(prevCornerScale - desiredKey) >= 0.000005f)) {
                val targetCp = if (bypass || abs(desiredCornerScale - 1.0f) < 0.01f) {
                    baseCp
                } else {
                    buildScaledCornerParams(drawableShader, baseCp)
                }
                dsLastCornerScale[drawableShader] = desiredKey
                cornerGuard.set(true)
                try {
                    runCatching { mDsSetCornerParam?.invoke(drawableShader, targetCp) }
                    runCatching { mDsSetCornerShader?.invoke(drawableShader, shader, targetCp) }
                } finally {
                    cornerGuard.remove()
                }
            }
        }

        val sourceScale = shaderSourceScale[drawableShader] ?: 1.0f
        NativeGlassShader.pushUniforms(
            shader = shader,
            params = params,
            bypass = bypass,
            sourceScale = sourceScale,
            usePaintSaturation = paintOwnedShaders.containsKey(shader),
            refractionScale = if (surface == SurfaceKind.QS) CONTROL_CENTER_REFRACTION_MULTIPLIER else 1f
        )
    }

    private fun isFullScreenOrExcluded(drawableShader: Any): Boolean {
        val view = shaderToView[drawableShader]?.get()
        if (view != null && isExcludedButton(view)) return true

        val w = runCatching { fDsWidth?.getFloat(drawableShader) ?: 0f }.getOrDefault(0f)
        val h = runCatching { fDsHeight?.getFloat(drawableShader) ?: 0f }.getOrDefault(0f)
        if (w > 1f && h > 1f) {
            val dm = Resources.getSystem().displayMetrics
            val maxScr = max(dm.widthPixels, dm.heightPixels).toFloat()
            val minScr = min(dm.widthPixels, dm.heightPixels).toFloat()
            if (maxScr > 1f && minScr > 1f) {
                if (max(w, h) >= 0.96f * maxScr && min(w, h) >= 0.92f * minScr) {
                    return true
                }
            }
        }
        return false
    }

    private fun isExcludedButton(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        var ids = excludedViewIdsCache
        if (ids == null) {
            val res = view.resources
            ids = IntArray(EXCLUDED_QS_BUTTON_IDS.size) { i ->
                res.getIdentifier(EXCLUDED_QS_BUTTON_IDS[i], "id", "com.android.systemui")
            }
            excludedViewIdsCache = ids
        }
        return ids.any { it != 0 && it == id }
    }

    private fun classifyShaderSurface(drawableShader: Any): SurfaceKind {
        shaderToSurface[drawableShader]?.let { return it }
        val view = shaderToView[drawableShader]?.get() ?: return SurfaceKind.OTHER
        val kind = classifyViewSurface(view)
        shaderToSurface[drawableShader] = kind
        return kind
    }

    private fun classifyViewSurface(view: View): SurfaceKind {
        var cur: Any? = view
        var depth = 0
        var sawNotification = false
        var sawQs = false
        while (cur != null && depth < 12) {
            val curView = cur as? View
            if (curView != null && headsUpBannerViews.containsKey(curView)) {
                // 悬浮消息弹窗（Heads-Up / 横幅通知）统一归入「全局生效」开关控制
                return SurfaceKind.OTHER
            }
            val name = cur.javaClass.name
            if (name == CLS_EXPANDABLE_ROW && isExpandableRowHeadsUp(cur)) {
                return SurfaceKind.OTHER
            }
            if (name == "com.oplus.systemui.volume.OplusVolumeSeekBar" ||
                name.startsWith("com.oplus.systemui.volume.") ||
                name.startsWith("com.android.systemui.volume.")
            ) {
                return SurfaceKind.VOLUME
            }
            if (name.contains("HeadsUpLayout") ||
                name.contains("HeadsUpContainerWindow") ||
                name.contains("FullScreenBanner") ||
                name.contains("OplusToastLayout")
            ) {
                return SurfaceKind.OTHER
            }
            if (name.startsWith("com.oplus.systemui.statusbar.notification.") ||
                name.startsWith("com.android.systemui.statusbar.notification.")
            ) {
                sawNotification = true
            } else if (name.contains(".qs.", ignoreCase = true) ||
                name.contains("quicksetting", ignoreCase = true) ||
                name.contains("controlcenter", ignoreCase = true)
            ) {
                sawQs = true
            }
            cur = curView?.parent
            depth++
        }
        return when {
            sawNotification -> SurfaceKind.NOTIFICATION
            sawQs -> SurfaceKind.QS
            else -> SurfaceKind.OTHER
        }
    }

    private fun isExpandableRowHeadsUp(row: Any): Boolean {
        val isHu = runCatching { fRowIsHeadsUp?.getBoolean(row) == true }.getOrDefault(false)
        if (isHu) return true
        val disappearing = runCatching { fRowHeadsUpDisappear?.getBoolean(row) == true }.getOrDefault(false)
        if (disappearing) return true
        val pinnedStatus = runCatching { fRowPinnedStatus?.get(row) }.getOrNull() ?: return false
        val mPinned = mRowPinnedStatusIsPinned
            ?: pinnedStatus.javaClass.findMethod("isPinned")?.also { mRowPinnedStatusIsPinned = it }
            ?: return false
        return runCatching { mPinned.invoke(pinnedStatus) == true }.getOrDefault(false)
    }

    private fun clearDrawableBinding(target: Any?, depth: Int = 0) {
        if (target == null || depth > 12) return
        drawableToSurface.remove(target)
        drawableSyncRev.remove(target)
        blurScaleCache.remove(target)
        val ds = runCatching { mGetDrawableShader?.invoke(target) ?: fBaseDrawableShader?.get(target) }.getOrNull()
        if (ds != null) {
            shaderToSurface.remove(ds)
            dsSyncedStateKey.remove(ds)
            dsExcludedCache.remove(ds)
        }
        when (target) {
            is LayerDrawable -> {
                for (i in 0 until target.numberOfLayers) {
                    clearDrawableBinding(target.getDrawable(i), depth + 1)
                }
            }
            is DrawableWrapper -> clearDrawableBinding(target.drawable, depth + 1)
        }
        if (target.javaClass.name.endsWith("PlatformBlurDrawable")) {
            val inner = runCatching { fPbdBlurDrawable?.get(target) }.getOrNull()
            if (inner != null) clearDrawableBinding(inner, depth + 1)
        }
        if (target is Drawable && target.current !== target) {
            clearDrawableBinding(target.current, depth + 1)
        }
    }

    private fun refreshViewSurfaceBinding(view: View, blurManagerOrProxy: Any? = null) {
        view.context?.let { configWatcher?.bindContext(it) }
        val toInvalidate = ArrayList<Any>(2)
        synchronized(drawableToView) {
            drawableToView.forEach { (d, ref) ->
                if (ref.get() === view) toInvalidate.add(d)
            }
        }
        toInvalidate.forEach { d ->
            clearDrawableBinding(d)
            bindOwnerView(d, view, 0)
        }
        if (blurManagerOrProxy != null && blurManagerOrProxy.javaClass.name == CLS_VIEW_BLUR_PROXY) {
            val drawable = runCatching { mProxyGetBlurDrawable?.invoke(blurManagerOrProxy) }.getOrNull()
            if (drawable != null) {
                clearDrawableBinding(drawable)
                bindOwnerView(drawable, view, 0)
            }
            val staticDrawable = runCatching { mProxyGetStaticBlurDrawable?.invoke(blurManagerOrProxy) }.getOrNull()
            if (staticDrawable != null) {
                clearDrawableBinding(staticDrawable)
                bindOwnerView(staticDrawable, view, 0)
            }
        }
    }

    // ─────────────────── 3. Buffer 采样倍率同步（u_c17_source_scale） ───────────────────

    private fun hookSourceScaleSync(env: HookEnv) {
        env.findClass(CLS_BLUR_MANAGER)?.let { mgrCls ->
            mgrCls.findMethodsNamed("onBlurReady").forEach { m ->
                env.hookReplace(m, "$id:BlurDrawableManager.onBlurReady") { chain ->
                    val args = chain.args
                    if (args.size == 5 &&
                        args[1] is HardwareBuffer &&
                        args[3] is Number &&
                        args[4] is android.graphics.Rect
                    ) {
                        val reqScale = (args[3] as Number).toFloat()
                        if (abs(reqScale - 0.25f) < 0.01f) {
                            val buf = args[1] as HardwareBuffer
                            val rect = args[4] as android.graphics.Rect
                            if (!buf.isClosed && buf.width > 0 && buf.height > 0 && rect.width() > 0 && rect.height() > 0) {
                                val bw = buf.width
                                val bh = buf.height
                                val rw = rect.width()
                                val rh = rect.height()
                                if ((bw == rw && bh == rh) || (bw == rh && bh == rw)) {
                                    val newArgs = args.toTypedArray()
                                    newArgs[3] = 1.0f
                                    return@hookReplace chain.proceed(newArgs)
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }

        env.findClass(CLS_BASE_DRAWABLE)?.let { bdCls ->
            bdCls.findMethodsNamed("preDrawContent").forEach { m ->
                env.hookAfter(m, "$id:BaseDrawable.preDrawContent") { chain ->
                    val bd = chain.thisObject ?: return@hookAfter
                    val ds = runCatching { fBaseDrawableShader?.get(bd) }.getOrNull() ?: return@hookAfter
                    val info = runCatching { fBaseDrawingImageInfo?.get(bd) }.getOrNull()
                    val scale = if (info != null) {
                        runCatching { fImageInfoScale?.getFloat(info) ?: 0.25f }.getOrDefault(0.25f)
                    } else {
                        0.25f
                    }
                    val factor = scale.coerceIn(0.25f, 2.0f)
                    shaderSourceScale[ds] = factor
                    val shader = runCatching { fDsShader?.get(ds) }.getOrNull()
                    NativeGlassShader.pushSourceScale(shader, factor)
                }
            }
        }
    }

    // ─────────────────── 4. 模糊半径倍率同步（保留原生下拉缩放不截断） ───────────────────

    private fun blurScaleForDrawable(drawable: Any?): Float {
        if (PopupGlassHook.isOwnedDrawable(drawable)) return 1.0f
        maybeReloadConfig()
        val surface = if (drawable != null) {
            drawableToSurface[drawable]
                ?: drawableToView[drawable]?.get()?.let(::classifyViewSurface)
                ?: SurfaceKind.OTHER
        } else {
            SurfaceKind.OTHER
        }
        if (!isSurfaceAllowed(surface)) return 1.0f
        val p = paramsForSurface(surface)
        if (!p.enabled) return 1.0f
        return p.blurScale
    }

    private fun hookBlurRadiusPipeline(env: HookEnv) {
        val bpCls = env.findClass(CLS_BLUR_PARAM)
        val fgCls = env.findClass(CLS_FOREGROUND_BLUR_PARAM)
        val cbCls = env.findClass(CLS_CONTINUOUS_BLUR)

        val mToFloatArray = bpCls?.findMethod("toFloatArray", Boolean::class.javaPrimitiveType!!)
        val mSetInternal = if (cbCls != null && bpCls != null && fgCls != null) {
            cbCls.findMethod("setBlurParamsInternal", bpCls, fgCls, Boolean::class.javaPrimitiveType!!)
        } else {
            null
        }
        val mSyncParams = cbCls?.findMethod(
            "syncBlurParams",
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        )
        mSyncBlurParams = mSyncParams

        if (mToFloatArray != null && mSetInternal != null && mSyncParams != null) {
            val hookedSerialize = env.hookReplace(mToFloatArray, "$id:BlurParam.toFloatArray") { chain ->
                val raw = chain.proceed() as? FloatArray ?: return@hookReplace null
                val drawable = activeContinuousBlur.get()?.peekFirst()
                val factor = blurScaleForDrawable(drawable)
                if (drawable != null) {
                    blurScaleCache[drawable] = factor
                }
                if (raw.size < 3 || raw[2] <= 0f || abs(factor - 1.0f) < 0.01f) {
                    return@hookReplace raw
                }
                val scaled = raw.clone()
                scaled[2] = (raw[2] * factor).roundToInt().coerceAtLeast(1).toFloat()
                scaled
            }

            if (hookedSerialize) {
                env.hookReplace(mSetInternal, "$id:ContinuousBlurDrawable.setBlurParamsInternal") { chain ->
                    val drawable = chain.thisObject
                    if (drawable != null) {
                        var stack = activeContinuousBlur.get()
                        if (stack == null) {
                            stack = ArrayDeque()
                            activeContinuousBlur.set(stack)
                        }
                        stack.push(drawable)
                    }
                    try {
                        chain.proceed()
                    } finally {
                        activeContinuousBlur.get()?.let { stack ->
                            if (stack.isNotEmpty()) stack.pop()
                            if (stack.isEmpty()) activeContinuousBlur.remove()
                        }
                    }
                }
                val resyncHook = { chain: io.github.libxposed.api.XposedInterface.Chain ->
                    val drawable = chain.thisObject
                    if (drawable != null) {
                        var stack = activeContinuousBlur.get()
                        if (stack == null) {
                            stack = ArrayDeque()
                            activeContinuousBlur.set(stack)
                        }
                        stack.push(drawable)
                    }
                    try {
                        chain.proceed()
                    } finally {
                        activeContinuousBlur.get()?.let { stack ->
                            if (stack.isNotEmpty()) stack.pop()
                            if (stack.isEmpty()) activeContinuousBlur.remove()
                        }
                    }
                }
                env.hookReplace(mSyncParams, "$id:ContinuousBlurDrawable.syncBlurParams", resyncHook)
                cbCls.findMethodsNamed("addBlurDrawable").forEach { m ->
                    env.hookReplace(m, "$id:ContinuousBlurDrawable.addBlurDrawable", resyncHook)
                }
                toFloatArrayBlurInstalled = true
                return
            }
        }

        // 回落方案：若上述原生签名变更，回退直接缩放 BlurParam.blurRadius 与 blurBuffers
        hookBlurParamRadiusFallback(env)
    }

    private fun hookBlurParamRadiusFallback(env: HookEnv) {
        fun scaleBlurArgs(args: List<Any?>, owner: Any?) {
            maybeReloadConfig()
            val scale = blurScaleForDrawable(owner ?: activeContinuousBlur.get()?.peekFirst())
            if (!anySystemUiSurfaceEnabled() || abs(scale - 1.0f) < 0.01f) return
            val radiusField = fBlurRadius ?: return
            for (arg in args) {
                if (arg != null && arg.javaClass.name == CLS_BLUR_PARAM) {
                    val prev = scaledBlurParams[arg]
                    if (prev == null || !NativeGlassShader.approxEqual(prev, scale)) {
                        runCatching {
                            val raw = radiusField.getInt(arg)
                            if (raw > 0) {
                                val base = if (prev != null && prev > 0.01f) raw / prev else raw.toFloat()
                                val scaled = (base * scale).roundToInt().coerceAtLeast(1)
                                radiusField.setInt(arg, scaled)
                                scaledBlurParams[arg] = scale
                            }
                        }
                    }
                }
            }
        }

        listOf(
            CLS_BASE_DRAWABLE to "setDrawingParam",
            CLS_BLUR_DRAWABLE to "setBlurParams",
            CLS_BLUR_DRAWABLE to "setBlurParamsSync",
            CLS_BLUR_DRAWABLE to "setBlurParamsInternal",
            CLS_CONTINUOUS_BLUR to "setBlurParams",
            CLS_CONTINUOUS_BLUR to "setBlurParamsSync",
            CLS_CONTINUOUS_BLUR to "setBlurParamsInternal"
        ).forEach { (clsName, methodName) ->
            env.findClass(clsName)?.let { cls ->
                cls.findMethodsNamed(methodName).forEach { m ->
                    env.hookBefore(m, "$id:${cls.simpleName}.$methodName") { chain ->
                        scaleBlurArgs(chain.args, chain.thisObject)
                    }
                }
            }
        }

        env.findClass(CLS_BLUR_MANAGER)?.let { mgrCls ->
            mgrCls.findMethodsNamed("blurBuffers").forEach { m ->
                env.hookReplace(m, "$id:BlurDrawableManager.blurBuffers") { chain ->
                    maybeReloadConfig()
                    val scale = blurScaleForDrawable(activeContinuousBlur.get()?.peekFirst())
                    if (anySystemUiSurfaceEnabled() && abs(scale - 1.0f) >= 0.01f && chain.args.isNotEmpty()) {
                        val newArgs = chain.args.toTypedArray()
                        var changed = false
                        for (i in newArgs.indices) {
                            val a = newArgs[i]
                            if (a is Int && a in 4..400) {
                                newArgs[i] = (a * scale).roundToInt().coerceAtLeast(1)
                                changed = true
                            }
                        }
                        if (changed) {
                            return@hookReplace chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
        }
    }

    // ─────────────────── 7. 控制中心磁贴/滑块/Paint 饱和度 + 通知中心归属追踪 ───────────────────

    private fun currentSaturationFilter(params: NativeGlassShader.GlassShaderParams): ColorFilter? {
        if (!params.enabled || abs(params.paintSaturation - 1.0f) < 0.01f) return null
        val targetSat = params.paintSaturation
        if (!NativeGlassShader.approxEqual(cachedSatValue, targetSat) || cachedSatFilter == null) {
            val cm = ColorMatrix()
            cm.setSaturation(targetSat)
            cachedSatFilter = ColorMatrixColorFilter(cm)
            cachedSatValue = targetSat
        }
        return cachedSatFilter
    }

    private fun applyPaintSaturation(paint: Paint?, record: PaintFilterRecord, filter: ColorFilter?) {
        if (paint == null) return
        if (!record.captured) {
            record.original = paint.colorFilter
            record.captured = true
        }
        val target = filter ?: record.original
        if (paint.colorFilter !== target) {
            paint.colorFilter = target
        }
    }

    private fun resolveAutoBlurProxy(autoBlurDrawable: Any?): Any? {
        if (autoBlurDrawable == null) return null
        val cls = autoBlurDrawable.javaClass
        val method = autoBlurGetProxyMethods[cls]
            ?: cls.findMethod("getViewBlurProxy")?.also { autoBlurGetProxyMethods[cls] = it }
            ?: return null
        return runCatching { method.invoke(autoBlurDrawable) }.getOrNull()
    }

    private fun resolveStaticInnerBlur(staticDrawable: Any?): Any? {
        if (staticDrawable == null) return null
        val cls = staticDrawable.javaClass
        val method = staticBlurGetDrawableMethods[cls]
            ?: cls.findMethod("getBlurDrawable")?.also { staticBlurGetDrawableMethods[cls] = it }
            ?: return null
        return runCatching { method.invoke(staticDrawable) }.getOrNull()
    }

    private fun markQsProxyAndBindBlur(autoBlurDrawable: Any?) {
        val proxy = resolveAutoBlurProxy(autoBlurDrawable) ?: return
        qsOwnedProxies[proxy] = true
        val staticDrawable = runCatching { mProxyGetStaticBlurDrawable?.invoke(proxy) }.getOrNull()
        val blurDrawable = resolveStaticInnerBlur(staticDrawable)
            ?: runCatching { mProxyGetBlurDrawable?.invoke(proxy) }.getOrNull()
            ?: return
        val record = synchronized(qsOwnedDrawables) {
            qsOwnedDrawables.getOrPut(blurDrawable) { BlurPaintRecord() }
        }
        drawableToSurface[blurDrawable] = SurfaceKind.QS
        applyQsBlurPaintAndShader(blurDrawable, record)
    }

    private fun applyQsBlurPaintAndShader(blurDrawable: Any, record: BlurPaintRecord? = qsOwnedDrawables[blurDrawable]) {
        val p = shaderParams
        if (toFloatArrayBlurInstalled) {
            val prevScale = blurScaleCache[blurDrawable]
            val targetScale = blurScaleForDrawable(blurDrawable)
            if (prevScale != null && !NativeGlassShader.approxEqual(prevScale, targetScale)) {
                runCatching { mSyncBlurParams?.invoke(blurDrawable, true, false) }
            }
        }
        val filter = if (controlCenterEnabled) currentSaturationFilter(p) else null
        val effSat = if (p.enabled && controlCenterEnabled) p.paintSaturation else 1.0f
        if (record != null) {
            val shaderPaint = record.cachedShaderPaintRef?.get()
                ?: (runCatching { mGetDrawableShaderPaint?.invoke(blurDrawable) as? Paint }.getOrNull())
                    ?.also { record.cachedShaderPaintRef = WeakReference(it) }
            val bitmapPaint = record.cachedBitmapPaintRef?.get()
                ?: (runCatching { mGetPaint?.invoke(blurDrawable) as? Paint }.getOrNull())
                    ?.also { record.cachedBitmapPaintRef = WeakReference(it) }
            applyPaintSaturation(shaderPaint, record.shaderPaint, filter)
            applyPaintSaturation(bitmapPaint, record.bitmapPaint, filter)
            if (!NativeGlassShader.approxEqual(record.lastSaturation, effSat)) {
                runCatching { mSetContentDirty?.invoke(blurDrawable, true) }
                record.lastSaturation = effSat
            }
        }
        val ds = runCatching { mGetDrawableShader?.invoke(blurDrawable) ?: fBaseDrawableShader?.get(blurDrawable) }.getOrNull()
            ?: return
        shaderToSurface[ds] = SurfaceKind.QS
        val runtimeShader = runCatching { fDsShader?.get(ds) }.getOrNull()
        if (runtimeShader != null) {
            paintOwnedShaders[runtimeShader] = true
        }
        refreshDrawableShaderUniforms(ds)
    }

    private fun hookQsAndNotificationSurfaces(env: HookEnv) {
        // ViewBlurProxy：建立 View ↔ DrawableShader 映射，并对齐 QS Paint 饱和度通道
        env.findClass(CLS_VIEW_BLUR_PROXY)?.let { proxyCls ->
            val nonQsSentinel = Any()
            val proxyDepth = ThreadLocal<ArrayDeque<Any>>()
            listOf("getBlurDrawable", "applyBlurConfig", "applyConfigToPlatformBlur").forEach { name ->
                proxyCls.findMethodsNamed(name).forEach { m ->
                    env.hookReplace(m, "$id:ViewBlurProxy.$name") { chain ->
                        val proxy = chain.thisObject
                        var dq = proxyDepth.get()
                        if (dq == null) {
                            dq = ArrayDeque()
                            proxyDepth.set(dq)
                        }
                        dq.push(if (proxy != null && qsOwnedProxies.containsKey(proxy)) proxy else nonQsSentinel)
                        val result = try {
                            chain.proceed()
                        } finally {
                            proxyDepth.get()?.let { q ->
                                if (q.isNotEmpty()) q.pop()
                                if (q.isEmpty()) proxyDepth.remove()
                            }
                        }
                        runCatching {
                            if (proxy == null) return@runCatching
                            val view = fProxyView?.get(proxy) as? View
                            var blurDrawable = (result as? Drawable) ?: mProxyGetBlurDrawable?.invoke(proxy)
                            if (view != null) {
                                bindOwnerView(blurDrawable, view, 0)
                            }
                            if (qsOwnedProxies.containsKey(proxy)) {
                                val staticBlur = mProxyGetStaticBlurDrawable?.invoke(proxy)
                                val innerBlur = resolveStaticInnerBlur(staticBlur)
                                    ?: blurDrawable
                                if (innerBlur != null) {
                                    val rec = synchronized(qsOwnedDrawables) {
                                        qsOwnedDrawables.getOrPut(innerBlur) { BlurPaintRecord() }
                                    }
                                    drawableToSurface[innerBlur] = SurfaceKind.QS
                                    applyQsBlurPaintAndShader(innerBlur, rec)
                                }
                            } else if (name == "getBlurDrawable" && view != null) {
                                val intercepted = PopupGlassHook.interceptBlurDrawable(proxy, view, result)
                                if (intercepted !== result) {
                                    return@hookReplace intercepted
                                }
                            }
                        }
                        result
                    }
                }
            }

            env.findClass(CLS_PLATFORM_BLUR_DRAWABLE)?.let { pbdCls ->
                pbdCls.findMethodsNamed("applyBlurConfig").forEach { m ->
                    env.hookBefore(m, "$id:PlatformBlurDrawable.applyBlurConfig") { chain ->
                        val top = proxyDepth.get()?.peekFirst() ?: return@hookBefore
                        if (top !== nonQsSentinel) {
                            val pbd = chain.thisObject ?: return@hookBefore
                            val inner = runCatching { fPbdBlurDrawable?.get(pbd) }.getOrNull() ?: return@hookBefore
                            synchronized(qsOwnedDrawables) {
                                qsOwnedDrawables.getOrPut(inner) { BlurPaintRecord() }
                            }
                            drawableToSurface[inner] = SurfaceKind.QS
                        }
                    }
                }
            }

            // BlurTypeMotion <-> BlurTypePlatformStatic 切换后实时重绑激活态 BlurDrawable（消息弹窗核心路径）
            proxyCls.findMethodsNamed("setBlurType").forEach { m ->
                env.hookAfter(m, "$id:ViewBlurProxy.setBlurType") { chain ->
                    val proxy = chain.thisObject ?: return@hookAfter
                    val view = runCatching { fProxyView?.get(proxy) as? View }.getOrNull() ?: return@hookAfter
                    refreshViewSurfaceBinding(view, proxy)
                    PopupGlassHook.onBlurTypeChanged(proxy, view)
                }
            }
        }

        // 消息弹窗 / 横幅通知（HeadsUp / FullScreenBanner）：归属「全局生效」分组并实时同步动效模糊
        env.findClass(CLS_VIEW_BLUR_MANAGER)?.let { mgrCls ->
            val mGetProxy = mgrCls.findMethod("getBlurProxyForView", View::class.java)
            mgrCls.findMethodsNamed("blurForHeadsUp").forEach { m ->
                env.hookAfter(m, "$id:ViewBlurManager.blurForHeadsUp") { chain ->
                    val bgView = chain.args.firstOrNull { it is View } as? View ?: return@hookAfter
                    headsUpBannerViews[bgView] = true
                    val proxy = runCatching { mGetProxy?.invoke(chain.thisObject, bgView) }.getOrNull()
                    refreshViewSurfaceBinding(bgView, proxy)
                    if (headsUpLogged.compareAndSet(false, true)) {
                        HookLog.ok("[$id] 已为消息弹窗（${bgView.javaClass.simpleName}）挂载全局生效液态玻璃")
                    }
                }
            }
            mgrCls.findMethodsNamed("cancelBlurForHeadsUp").forEach { m ->
                env.hookAfter(m, "$id:ViewBlurManager.cancelBlurForHeadsUp") { chain ->
                    val bgView = chain.args.firstOrNull { it is View } as? View ?: return@hookAfter
                    headsUpBannerViews.remove(bgView)
                    val proxy = runCatching { mGetProxy?.invoke(chain.thisObject, bgView) }.getOrNull()
                    refreshViewSurfaceBinding(bgView, proxy)
                }
            }
        }

        env.findClass(CLS_FULLSCREEN_BANNER_CONTAINER)?.let { fsCls ->
            val fBanner = fsCls.findField("mFullScreenBanner")
            fsCls.findMethodsNamed("getFullScreenBannerDrawable").forEach { m ->
                env.hookReplace(m, "$id:FullScreenBannerContainer.getDrawable") { chain ->
                    val d = chain.proceed() ?: return@hookReplace null
                    runCatching {
                        val bannerView = fBanner?.get(chain.thisObject) as? View ?: return@runCatching
                        headsUpBannerViews[bannerView] = true
                        bindOwnerView(d, bannerView, 0)
                    }
                    d
                }
            }
        }

        // MixColorTileDrawable：构造与绘制时绑定 QS 磁贴饱和度 Filter（按 configRevision 短路免每帧反射）
        env.findClass(CLS_MIX_TILE)?.let { mixCls ->
            mixCls.declaredConstructors.forEach { c ->
                env.hookAfter(c, "$id:MixColorTileDrawable.satInit") { chain ->
                    val mix = chain.thisObject ?: return@hookAfter
                    val autoBlur = runCatching { fMixAutoBlurDrawable?.get(mix) }.getOrNull()
                    markQsProxyAndBindBlur(autoBlur)
                    mixTileBoundRev[mix] = configRevision
                }
            }
            mixCls.findMethodsNamed("draw").forEach { m ->
                env.hookBefore(m, "$id:MixColorTileDrawable.satDraw") { chain ->
                    val mix = chain.thisObject ?: return@hookBefore
                    maybeReloadConfig()
                    val rev = configRevision
                    if (mixTileBoundRev[mix] != rev) {
                        val autoBlur = runCatching { fMixAutoBlurDrawable?.get(mix) }.getOrNull()
                        markQsProxyAndBindBlur(autoBlur)
                        mixTileBoundRev[mix] = rev
                    }
                    val paint = mixTilePaintCache[mix]?.get()
                        ?: (runCatching { fMixPaint?.get(mix) as? Paint }.getOrNull())
                            ?.also { mixTilePaintCache[mix] = WeakReference(it) }
                        ?: return@hookBefore
                    val rec = synchronized(mixTileFilters) {
                        mixTileFilters.getOrPut(mix) { PaintFilterRecord() }
                    }
                    applyPaintSaturation(
                        paint,
                        rec,
                        if (controlCenterEnabled) currentSaturationFilter(shaderParams) else null
                    )
                }
            }
        }

        // QS 亮度/音量滑块
        env.findClass(CLS_QS_SEEKBAR_MANAGER)?.let { mgrCls ->
            mgrCls.findMethodsNamed("createSeekBarBlurDrawable").forEach { m ->
                env.hookReplace(m, "$id:QsSeekBarBlurManager.createSeekBarBlurDrawable") { chain ->
                    val d = chain.proceed()
                    if (d != null) {
                        runCatching {
                            val view = chain.args.firstOrNull { it is View } as? View
                            bindOwnerView(d, view, 0)
                            markQsProxyAndBindBlur(d)
                        }
                    }
                    d
                }
            }
            mgrCls.findMethodsNamed("applyBlurConfig").forEach { m ->
                env.hookBefore(m, "$id:QsSeekBarBlurManager.applyBlurConfig") { chain ->
                    val target = chain.args.getOrNull(3) ?: return@hookBefore
                    markQsProxyAndBindBlur(target)
                }
            }
        }
        env.findClass(CLS_QS_SEEKBAR)?.let { seekCls ->
            val fBaseMix = seekCls.findField("baseMixColorDrawable")
            val fActiveMix = seekCls.findField("activeMixColorDrawable")
            seekCls.findMethodsNamed("onDraw").forEach { m ->
                env.hookBefore(m, "$id:OplusQsVerticalSeekBar.onDraw") { chain ->
                    val view = chain.thisObject as? View ?: return@hookBefore
                    fBaseMix?.let { runCatching { val d = it.get(view); bindOwnerView(d, view, 0); markQsProxyAndBindBlur(d) } }
                    fActiveMix?.let { runCatching { val d = it.get(view); bindOwnerView(d, view, 0); markQsProxyAndBindBlur(d) } }
                }
            }
        }

        // 侧边音量条（OplusVolumeSeekBar）：绑定其内部模糊 Drawable 到 SurfaceKind.VOLUME 以支持独立开关与滑块热重载
        env.findClass(CLS_VOLUME_SEEKBAR)?.let { volCls ->
            val drawableFields = volumeDrawableFieldsCache.getOrPut(volCls) {
                generateSequence<Class<*>>(volCls) { it.superclass }
                    .takeWhile { it != View::class.java && it != Any::class.java }
                    .flatMap { c ->
                        c.declaredFields.asSequence().filter { f ->
                            Drawable::class.java.isAssignableFrom(f.type) ||
                                f.type.name.startsWith("com.oplus.posteffect")
                        }.onEach { runCatching { it.isAccessible = true } }
                    }
                    .toList()
            }
            listOf("onDraw", "draw").forEach { methodName ->
                volCls.findMethodsNamed(methodName).forEach { m ->
                    env.hookBefore(m, "$id:OplusVolumeSeekBar.$methodName") { chain ->
                        val view = chain.thisObject as? View ?: return@hookBefore
                        view.background?.let { bindOwnerView(it, view, 0) }
                        drawableFields.forEach { f ->
                            runCatching { f.get(view) }.getOrNull()?.let { d ->
                                bindOwnerView(d, view, 0)
                            }
                        }
                    }
                }
            }
        }

        // 通知背景视图关联（确保 NotificationBackgroundViewExtImp 下的卡片绑定到 Notification 分组）
        env.findClass(CLS_NOTIF_BG_EXT)?.let { notifCls ->
            notifCls.findMethodsNamed("getViewBlurProxy").forEach { m ->
                env.hookReplace(m, "$id:NotifBgExt.getViewBlurProxy") { chain ->
                    val proxy = chain.proceed() ?: return@hookReplace null
                    runCatching {
                        val view = fProxyView?.get(proxy) as? View ?: return@runCatching
                        val drawable = mProxyGetBlurDrawable?.invoke(proxy)
                        bindOwnerView(drawable, view, 0)
                    }
                    proxy
                }
            }
        }

        // BaseDrawable / ContinuousBlurDrawable / BlendDrawable：绑定 ShaderOwner、QS Paint 饱和度及全量模糊/参数热重载
        listOf(CLS_BASE_DRAWABLE, CLS_CONTINUOUS_BLUR, CLS_BLEND_DRAWABLE).forEach { clsName ->
            env.findClass(clsName)?.let { cls ->
                val targetName = if (clsName == CLS_BLEND_DRAWABLE) "onDrawContent" else "preDrawContent"
                if (clsName == CLS_CONTINUOUS_BLUR && targetName == "preDrawContent") {
                    cls.findMethodsNamed(targetName).forEach { m ->
                        env.hookAfter(m, "$id:${cls.simpleName}.$targetName") { chain ->
                            chain.thisObject?.let(::syncDrawableBeforeDraw)
                        }
                    }
                } else {
                    cls.findMethodsNamed(targetName).forEach { m ->
                        env.hookBefore(m, "$id:${cls.simpleName}.$targetName") { chain ->
                            chain.thisObject?.let(::syncDrawableBeforeDraw)
                        }
                    }
                }
            }
        }

        // 通知平台 mirrorScale 兼容 hook
        env.findClass(CLS_NOTIF_BLUR_MANAGER)?.let { cls ->
            cls.findMethodsNamed("updateMirrorScale").forEach { m ->
                env.hookBefore(m, "$id:NotifBlurParamsMgr.updateMirrorScale") { _ ->
                    // Mirror scale 由 PluginDrawable.getMirrorScaleValue 与 applyPanelMirrorScale 统一钳制为 1.0f
                }
            }
        }
    }

    private fun hookMixColorTileWideActive(env: HookEnv) {
        val mixCls = env.findClass(CLS_MIX_TILE) ?: return
        val providerCls = env.findClass(CLS_QS_BLUR_PROVIDER) ?: return
        val mGetSepActive = providerCls.findMethodsNamed("getSepActiveBlurConfig")
            .firstOrNull { it.parameterCount == 2 } ?: return

        val tileFlagCls = env.findClass(CLS_TILE_FLAG)
        val activeAttr = tileFlagCls?.let { cls ->
            runCatching {
                val activeObj = cls.findField("Active")?.get(null)
                activeObj?.javaClass?.findMethod("getAttr")?.invoke(activeObj) as? Int
            }.getOrNull()
        } ?: return

        val fStateList = mixCls.findField("stateListConfig") ?: return
        val mUpdateStateList = mixCls.findMethod("updateStateList", LinkedHashMap::class.java) ?: return
        val wideOwners = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
        val transToWideOwner = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
        val originalTransitionAlpha = Collections.synchronizedMap(WeakHashMap<Any, Int>())
        val twoXOneCls = env.findClass(CLS_TILE_RESIZEABLE_TWO_ONE)
        val resizeBaseCls = env.findClass(CLS_TILE_RESIZEABLE)
        val highlightBaseCls = env.findClass(CLS_QS_HIGHLIGHT_TILE)
        val mGetTileState = resizeBaseCls?.findMethod("getTileState")
        val mGetBgDrawable = resizeBaseCls?.findMethod("getBgDrawable")
        val mGetTransitionDrawable = resizeBaseCls?.findMethod("getTransitionDrawable")

        fun isWideTileView(v: View?): Boolean {
            if (v == null) return false
            if (resizeBaseCls != null && resizeBaseCls.isInstance(v)) {
                return twoXOneCls != null && twoXOneCls.isInstance(v)
            }
            return highlightBaseCls != null && highlightBaseCls.isInstance(v) &&
                v.height > 0 && v.width > v.height * 1.35f
        }

        fun tileViewState(v: View): Int {
            if (resizeBaseCls != null && resizeBaseCls.isInstance(v)) {
                val stObj = runCatching { mGetTileState?.invoke(v) }.getOrNull()
                if (stObj != null) {
                    val fState = stateFieldCache[stObj.javaClass]
                        ?: stObj.javaClass.findField("state")?.also { stateFieldCache[stObj.javaClass] = it }
                    if (fState != null) return runCatching { fState.getInt(stObj) }.getOrDefault(-1)
                }
            }
            val cls = v.javaClass
            val fTemp = tempStateFieldCache[cls]
                ?: cls.findField("mTempState")?.also { tempStateFieldCache[cls] = it }
            val tempObj = runCatching { fTemp?.get(v) }.getOrNull()
            if (tempObj != null) {
                val fState = stateFieldCache[tempObj.javaClass]
                    ?: tempObj.javaClass.findField("state")?.also { stateFieldCache[tempObj.javaClass] = it }
                if (fState != null) return runCatching { fState.getInt(tempObj) }.getOrDefault(-1)
            }
            val fState = directStateFieldCache[cls]
                ?: cls.findField("mState")?.also { directStateFieldCache[cls] = it }
            return runCatching { fState?.getInt(v) ?: -1 }.getOrDefault(-1)
        }

        fun resolveWideOwnerForDrawable(mixDrawable: Any): View? {
            wideOwners[mixDrawable]?.get()?.let { return it }
            val viewFromProxy = runCatching {
                val auto = fMixAutoBlurDrawable?.get(mixDrawable)
                val proxy = resolveAutoBlurProxy(auto)
                fProxyView?.get(proxy) as? View
            }.getOrNull() ?: drawableToView[mixDrawable]?.get()
            var cur: View? = viewFromProxy
            while (cur != null) {
                if (isWideTileView(cur)) {
                    wideOwners[mixDrawable] = WeakReference(cur)
                    return cur
                }
                cur = cur.parent as? View
            }
            return null
        }

        fun normalizeMixTileActiveState(mixDrawable: Any, ownerView: View? = resolveWideOwnerForDrawable(mixDrawable)) {
            if (wideTileInternal.get() == true || !shaderParams.enabled || !controlCenterEnabled) return
            if (!isWideTileView(ownerView)) return
            val isNight = (Resources.getSystem().configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val normKey = (configRevision shl 1) or (if (isNight) 1L else 0L)
            if (mixTileNormalizedKey[mixDrawable] == normKey) return

            @Suppress("UNCHECKED_CAST")
            val stateMap = runCatching { fStateList.get(mixDrawable) as? LinkedHashMap<Any, Any> }.getOrNull()
                ?: return
            var changed = false
            val rebuilt = LinkedHashMap<Any, Any>(stateMap.size)
            for ((k, v) in stateMap) {
                val specs = runCatching { k.javaClass.findMethod("getStateSpec")?.invoke(k) as? IntArray }.getOrNull()
                if (specs != null && specs.contains(activeAttr)) {
                    val first = runCatching { v.javaClass.findMethod("getFirst")?.invoke(v) }.getOrNull()
                    val second = runCatching { (v.javaClass.findMethod("getSecond")?.invoke(v) as? Number)?.toInt() }.getOrNull()
                    val mirrorScale = runCatching {
                        (first?.javaClass?.findMethod("getMirrorScale")?.invoke(first) as? Number)?.toFloat()
                    }.getOrNull()
                    if ((mirrorScale != null && abs(mirrorScale - 1.0f) > 0.001f) || (second != null && second != 0)) {
                        val fixedActive = runCatching {
                            val newFirst = mGetSepActive.invoke(null, isNight, 1.0f)
                            val third = v.javaClass.findMethod("getThird")?.invoke(v)
                            val tripleCtor: Constructor<*> = v.javaClass.getConstructor(
                                Any::class.java,
                                Any::class.java,
                                Any::class.java
                            )
                            tripleCtor.newInstance(newFirst, 0, third)
                        }.getOrNull()
                        if (fixedActive != null) {
                            rebuilt[k] = fixedActive
                            changed = true
                            continue
                        }
                    }
                }
                rebuilt[k] = v
            }
            if (changed) {
                wideTileInternal.set(true)
                try {
                    runCatching { mUpdateStateList.invoke(mixDrawable, rebuilt) }
                } finally {
                    wideTileInternal.set(false)
                }
            }
            mixTileNormalizedKey[mixDrawable] = normKey
        }

        mixCls.declaredConstructors.forEach { c ->
            env.hookAfter(c, "$id:MixColorTileDrawable.<init>") { chain ->
                chain.thisObject?.let { normalizeMixTileActiveState(it) }
            }
        }
        mixCls.findMethodsNamed("updateStateList").forEach { m ->
            env.hookAfter(m, "$id:MixColorTileDrawable.updateStateList") { chain ->
                chain.thisObject?.let {
                    mixTileNormalizedKey.remove(it)
                    normalizeMixTileActiveState(it)
                }
            }
        }
        mixCls.findMethodsNamed("draw").forEach { m ->
            env.hookBefore(m, "$id:MixColorTileDrawable.wideDraw") { chain ->
                chain.thisObject?.let { normalizeMixTileActiveState(it) }
            }
        }
        env.findClass(CLS_MIX_TILE_BUILDER)?.findMethodsNamed("build")?.forEach { m ->
            env.hookReplace(m, "$id:MixColorTileDrawable.Builder.build") { chain ->
                val built = chain.proceed()
                if (built != null) runCatching { normalizeMixTileActiveState(built) }
                built
            }
        }

        // TileTransitionDrawable：宽磁贴处于激活态时保持 TransitionAlpha=255 让玻璃底可见
        env.findClass(CLS_TILE_TRANSITION)?.findMethodsNamed("setTransitionAlpha")?.forEach { m ->
            env.hookReplace(m, "$id:TileTransitionDrawable.setTransitionAlpha") { chain ->
                val trans = chain.thisObject
                val alphaArg = chain.args.firstOrNull() as? Int
                if (wideTileInternal.get() != true && trans != null && alphaArg != null && originalTransitionAlpha.containsKey(trans)) {
                    originalTransitionAlpha[trans] = alphaArg
                    val owner = transToWideOwner[trans]?.get()
                    if (owner != null && shaderParams.enabled && controlCenterEnabled && tileViewState(owner) == 2 && alphaArg != 255) {
                        val newArgs = chain.args.toTypedArray()
                        newArgs[0] = 255
                        return@hookReplace chain.proceed(newArgs)
                    }
                }
                chain.proceed()
            }
        }

        val syncResizeableWideTile = { v: View ->
            val bg = runCatching { mGetBgDrawable?.invoke(v) ?: v.background }.getOrNull()
            if (bg != null) {
                if (drawableToView[bg]?.get() !== v) {
                    bindOwnerView(bg, v, 0)
                }
                if (bg.javaClass.name == CLS_MIX_TILE) {
                    wideOwners[bg] = WeakReference(v)
                    normalizeMixTileActiveState(bg, v)
                }
            }
            val trans = runCatching { mGetTransitionDrawable?.invoke(v) }.getOrNull()
            if (trans != null) {
                transToWideOwner[trans] = WeakReference(v)
                val tCls = trans.javaClass
                val mSet = transSetAlphaMethods[tCls]
                    ?: tCls.findMethod("setTransitionAlpha", Int::class.javaPrimitiveType!!)?.also { transSetAlphaMethods[tCls] = it }
                val mGet = transGetAlphaMethods[tCls]
                    ?: tCls.findMethod("getTransitionAlpha")?.also { transGetAlphaMethods[tCls] = it }
                if (shaderParams.enabled && controlCenterEnabled && tileViewState(v) == 2) {
                    if (!originalTransitionAlpha.containsKey(trans)) {
                        val curAlpha = runCatching { mGet?.invoke(trans) as? Int }.getOrNull() ?: 255
                        originalTransitionAlpha[trans] = curAlpha
                    }
                    wideTileInternal.set(true)
                    try {
                        runCatching { mSet?.invoke(trans, 255) }
                    } finally {
                        wideTileInternal.set(false)
                    }
                } else {
                    val prev = originalTransitionAlpha.remove(trans)
                    if (prev != null) {
                        wideTileInternal.set(true)
                        try {
                            runCatching { mSet?.invoke(trans, prev) }
                        } finally {
                            wideTileInternal.set(false)
                        }
                    }
                }
            }
        }

        listOf(CLS_TILE_RESIZEABLE, CLS_QS_HIGHLIGHT_TILE).forEach { clsName ->
            env.findClass(clsName)?.let { tileCls ->
                if (clsName == CLS_TILE_RESIZEABLE) {
                    tileCls.findMethodsNamed("access\$handleStateChanged").forEach { m ->
                        env.hookAfter(m, "$id:ResizeableTile.handleStateChanged") { chain ->
                            val v = chain.args.firstOrNull { it is View } as? View ?: chain.thisObject as? View
                            if (v != null && isWideTileView(v)) runCatching { syncResizeableWideTile(v) }
                        }
                    }
                    tileCls.findMethodsNamed("onFinishInflate").forEach { m ->
                        env.hookAfter(m, "$id:ResizeableTile.onFinishInflate") { chain ->
                            val v = chain.thisObject as? View ?: return@hookAfter
                            val bg = runCatching { tileCls.findMethod("getBgDrawable")?.invoke(v) }.getOrNull() ?: v.background
                            bindOwnerView(bg, v, 0)
                        }
                    }
                }
                tileCls.findMethodsNamed("dispatchDraw").forEach { m ->
                    env.hookBefore(m, "$id:${tileCls.simpleName}.dispatchDraw") { chain ->
                        val v = chain.thisObject as? View ?: return@hookBefore
                        if (isWideTileView(v)) runCatching { syncResizeableWideTile(v) }
                    }
                }
            }
        }
    }

    // ─────────────────── 8. 亮度滑块手动模式暖金太阳图标 ───────────────────

    private fun hookBrightnessSunIcon(env: HookEnv) {
        val clipCls = env.findClass(CLS_CLIP_BRIGHTNESS) ?: return
        val colorUtilCls = env.findClass(CLS_QS_COLOR_UTIL) ?: return
        val mGetAuto = clipCls.findMethod("getAutoMode") ?: return
        val mGetIcon = clipCls.findMethod("getBrightnessIcon") ?: return
        val fHigh = clipCls.findField("isHighBrightnessState") ?: return
        val fGold = colorUtilCls.findField("BRIGHTNESS_ICON_COLOR") ?: return
        clipCls.findMethodsNamed("updateIconColor").forEach { m ->
            env.hookAfter(m, "$id:ClipBrightnessView.updateIconColor") { chain ->
                if (!shaderParams.enabled || !controlCenterEnabled) return@hookAfter
                val view = chain.thisObject as? View ?: return@hookAfter
                if (mGetAuto.invoke(view) == true) return@hookAfter
                val highObj = fHigh.get(view)
                val isHigh = highObj?.let { obj ->
                    val getter = mAtomicBoolGet ?: obj.javaClass.findMethod("get")?.also { mAtomicBoolGet = it }
                    runCatching { getter?.invoke(obj) == true }.getOrDefault(false)
                } ?: false
                if (isHigh) return@hookAfter
                val icon = mGetIcon.invoke(view) as? Drawable ?: return@hookAfter
                val gold = fGold.getInt(null)
                icon.setColorFilter(gold, PorterDuff.Mode.SRC_IN)
                view.invalidate()
                if (sunColorLogged.compareAndSet(false, true)) {
                    HookLog.ok("[fluid-glass] 手动亮度太阳图标已应用暖金色=#${Integer.toHexString(gold)}")
                }
            }
        }
    }

    private fun bindOwnerView(target: Any?, view: View?, depth: Int) {
        if (target == null || view == null || depth > 12) return
        if (PopupGlassHook.isOwnedDrawable(target)) return
        view.context?.let { configWatcher?.bindContext(it) }
        when (target) {
            is LayerDrawable -> {
                for (i in 0 until target.numberOfLayers) {
                    bindOwnerView(target.getDrawable(i), view, depth + 1)
                }
                return
            }
            is DrawableWrapper -> {
                bindOwnerView(target.drawable, view, depth + 1)
                return
            }
        }
        if (target.javaClass.name.endsWith("PlatformBlurDrawable")) {
            val inner = runCatching { fPbdBlurDrawable?.get(target) }.getOrNull()
            if (inner != null) bindOwnerView(inner, view, depth + 1)
            return
        }
        if (target.javaClass.name.startsWith("com.oplus.posteffect")) {
            drawableToView[target] = WeakReference(view)
            attachShaderOwner(target, view)
        }
        if (target is Drawable && target.current !== target) {
            bindOwnerView(target.current, view, depth + 1)
        }
    }

    private fun attachShaderOwner(drawable: Any, view: View) {
        if (PopupGlassHook.isOwnedDrawable(drawable)) return
        val kind = classifyViewSurface(view)
        drawableToSurface[drawable] = kind
        if (kind == SurfaceKind.QS) {
            synchronized(qsOwnedDrawables) {
                qsOwnedDrawables.getOrPut(drawable) { BlurPaintRecord() }
            }
        }
        val ds = runCatching { mGetDrawableShader?.invoke(drawable) ?: fBaseDrawableShader?.get(drawable) }.getOrNull()
            ?: return
        dsSyncedStateKey.remove(ds)
        dsExcludedCache.remove(ds)
        shaderToView[ds] = WeakReference(view)
        shaderToSurface[ds] = kind
        refreshDrawableShaderUniforms(ds)
    }

    private fun syncDrawableBeforeDraw(drawable: Any) {
        if (PopupGlassHook.isOwnedDrawable(drawable)) return
        maybeReloadConfig()
        val rev = configRevision
        val qsRec = qsOwnedDrawables[drawable]
        if (drawableSyncRev[drawable] == rev) {
            if (qsRec != null) {
                val filter = if (controlCenterEnabled) currentSaturationFilter(shaderParams) else null
                val sp = qsRec.cachedShaderPaintRef?.get()
                val bp = qsRec.cachedBitmapPaintRef?.get()
                if (sp != null && bp != null) {
                    applyPaintSaturation(sp, qsRec.shaderPaint, filter)
                    applyPaintSaturation(bp, qsRec.bitmapPaint, filter)
                    return
                }
            } else {
                return
            }
        }

        val view = drawableToView[drawable]?.get()
        if (view != null) {
            attachShaderOwner(drawable, view)
        }
        if (qsRec != null) {
            applyQsBlurPaintAndShader(drawable, qsRec)
            drawableSyncRev[drawable] = rev
            return
        }
        if (toFloatArrayBlurInstalled) {
            val prevScale = blurScaleCache[drawable]
            val targetScale = blurScaleForDrawable(drawable)
            if (prevScale != null && !NativeGlassShader.approxEqual(prevScale, targetScale)) {
                runCatching {
                    mSyncBlurParams?.invoke(drawable, true, false)
                    mSetContentDirty?.invoke(drawable, true)
                }
            }
        }
        val ds = runCatching { mGetDrawableShader?.invoke(drawable) ?: fBaseDrawableShader?.get(drawable) }.getOrNull()
        if (ds != null) {
            refreshDrawableShaderUniforms(ds)
        }
        drawableSyncRev[drawable] = rev
    }

    private fun maybeReloadConfig(context: Context? = null) {
        configWatcher?.scheduleCheck(context)
    }

    private fun clearShaderCachesOnConfigChange() {
        shaderToSurface.clear()
        drawableToSurface.clear()
        dsSyncedStateKey.clear()
        dsExcludedCache.clear()
        blurScaleCache.clear()
        drawableSyncRev.clear()
    }

    private fun applyReloadedConfig(cfg: HookConfig?, disabled: Boolean) {
        if (disabled || cfg == null) {
            if (anySystemUiSurfaceEnabled()) {
                controlCenterEnabled = false
                notificationsEnabled = false
                volumeEnabled = false
                applyGloballyEnabled = false
                shaderParams = NativeGlassShader.GlassShaderParams.from(HookConfig(null), surfaceEnabled = false)
                volumeShaderParams = NativeGlassShader.GlassShaderParams.fromVolume(HookConfig(null), surfaceEnabled = false)
                clearShaderCachesOnConfigChange()
                configRevision++
                PopupGlassHook.onConfigChanged()
            }
            return
        }
        controlCenterEnabled = cfg.glassControlCenter
        notificationsEnabled = cfg.glassNotifications
        volumeEnabled = cfg.glassVolume
        applyGloballyEnabled = cfg.applyGlobally
        rawBlurRadiusDp = cfg.blurRadius
        shaderParams = NativeGlassShader.GlassShaderParams.from(
            cfg,
            surfaceEnabled = cfg.glassControlCenter || cfg.glassNotifications || cfg.applyGlobally
        )
        volumeShaderParams = NativeGlassShader.GlassShaderParams.fromVolume(cfg)
        clearShaderCachesOnConfigChange()
        configRevision++
        PopupGlassHook.onConfigChanged()
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            HookLog.error("[$id:$name] 安装失败（已隔离，不影响其它能力）", t)
        }
    }
}
