package com.c17.glass.xposed.glass

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.c17.glass.xposed.statusbar.HookConfig
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.findField
import com.c17.glass.xposed.statusbar.findMethod
import com.c17.glass.xposed.statusbar.findMethodsNamed
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * ColorOS 17 系统桌面（com.android.launcher）桌面文件夹与 Dock 栏液态玻璃 Hook。
 *
 * 实机管线适配（已在 OplusLauncher 17.5.4 + UniRender 实机结构上核实）：
 *  1. 桌面文件夹（FlexibleFolderIcon / OplusPreviewBackground）与 Dock（OplusHotseat）均由
 *     `RenderEffectDrawable` -> `hlb` (`mUniRenderDrawable`) -> `com.oplus.posteffect.agsl.b` 驱动；
 *  2. 注入 `com.oplus.posteffect.agsl.a` + `OplusRuntimeShader` / `RuntimeShader` 构造，复用
 *     `NativeGlassShader`（启用 `u_c17_force_g2 = 1.0`，对齐 ColorOS 桌面 G2 连续曲率轮廓）；
 *  3. 按目标角色分流：桌面文件夹受 `glassFolders` 控制，Dock 栏受 `glassDock` 控制，
 *     全屏背景模糊与未知层固定走 bypass（保持桌面抽屉/多任务背景原生表现）；
 *  4. Hook `OplusHotseat`（`setDockerBackground` / `onLayout` / `updateDockerBgIfNecessary` /
 *     `onWallpaperBrightnessChanged`），在直板机型上启用原生 `BlurBaseFactory.prepareBlurBase`
 *     生成 Dock 背景胶囊并叠加液态玻璃折射，且支持在返回桌面时热重载参数。
 */
object LauncherGlassHook {

    private const val TAG = "launcher-glass"

    private const val CLS_AGSL_BUILDER = "com.oplus.posteffect.agsl.a"
    private const val CLS_BASE_SHADER_KT = "com.oplus.posteffect.agsl.BlurDrawableShaderBaseStringKt"
    private const val CLS_AGSL_SHADER = "com.oplus.posteffect.agsl.b"
    private const val CLS_DRAWABLE_SHADER = "com.oplus.posteffect.agsl.DrawableShader"
    private const val CLS_OPLUS_RUNTIME_SHADER = "com.oplus.graphics.OplusRuntimeShader"
    private const val CLS_RUNTIME_SHADER = "android.graphics.RuntimeShader"

    private const val CLS_RENDER_EFFECT_DRAWABLE =
        "com.android.launcher3.uioverrides.states.blurdrawable.RenderEffectDrawable"
    private const val CLS_RENDER_EFFECT_MANAGER =
        "com.android.launcher3.uioverrides.states.blurdrawable.RenderEffectManager"
    private const val CLS_BLUR_BASE_FACTORY =
        "com.android.launcher3.uioverrides.states.blurdrawable.BlurBaseFactory"
    private const val CLS_BLUR_TRANSITION =
        "com.android.launcher3.uioverrides.states.blurdrawable.BlurTransitionDrawable"
    private const val CLS_OPLUS_BLUR_COMPANION =
        "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties\$Companion"
    private const val CLS_OPLUS_BLUR_PROPS =
        "com.android.launcher3.uioverrides.states.blurdrawable.OplusBlurProperties"
    private const val CLS_EFFECT_UTILS = "com.android.launcher3.util.LauncherMaterialEffectUtils"
    private const val CLS_OPLUS_HOTSEAT = "com.android.launcher3.OplusHotseat"

    private enum class SurfaceRole {
        DOCK,
        FOLDER,
        OTHER
    }

    @Volatile
    private var glassFoldersEnabled: Boolean = true

    @Volatile
    private var glassDockEnabled: Boolean = true

    @Volatile
    private var applyGloballyEnabled: Boolean = true

    @Volatile
    private var shaderParams: NativeGlassShader.GlassShaderParams =
        NativeGlassShader.GlassShaderParams.fromLauncher(HookConfig(null), surfaceEnabled = false)

    @Volatile
    private var dockParams: NativeGlassShader.GlassShaderParams = shaderParams

    @Volatile
    private var folderParams: NativeGlassShader.GlassShaderParams = shaderParams

    @Volatile
    private var configRevision: Long = 1L

    @Volatile
    private var lastGlobalBlurRevision: Long = 0L

    @Volatile
    private var configWatcher: GlassConfigWatcher? = null

    private val mainHandler by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Handler(Looper.getMainLooper())
    }

    private val dsToEffectDrawable = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val dsToRole = Collections.synchronizedMap(WeakHashMap<Any, SurfaceRole>())
    private val trackedEffectDrawables = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val redLastRevision = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val trackedHotseats = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val hotseatLastRevision = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val hotseatLastHeight = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val dockUpdatingGuard = ThreadLocal<Boolean>()
    private val settingCornerGuard = ThreadLocal<Boolean>()
    private val rebakingEffectGuard = ThreadLocal<Boolean>()

    private data class CornerCallRecord(val baseRadius: Float, val extraArgs: Array<Any?>)

    private val redBaseCornerCalls = Collections.synchronizedMap(WeakHashMap<Any, CornerCallRecord>())

    // Reflection caches
    private var fDsShader: Field? = null
    private var fDsTextBuilder: Field? = null
    private var fDsWidth: Field? = null
    private var fDsHeight: Field? = null
    private var fDsCornerHolder: Field? = null
    private var fCornerHolderRadius: Field? = null
    private var mDsCornerApply: Method? = null

    private var launcherBlurRadius: Float = 10f
    private val updatingGlobalEffectGuard = ThreadLocal<Boolean>()
    private val globalParamBaseRadius = Collections.synchronizedMap(WeakHashMap<Any, Float>())
    @Volatile
    private var lastGlobalParamRef: WeakReference<Any>? = null
    private var mGlobalSetEffectParam: Method? = null
    private var clsGlobalBlurParam: Class<*>? = null
    private var fGlobalBlurRadius: Field? = null
    private var fGlobalBlendMode: Field? = null
    private var fGlobalBlendColor0: Field? = null
    private var fGlobalBlendColor1: Field? = null
    private var fGlobalBlurThrottleMs: Field? = null
    private var fRedUni: Field? = null
    private var fRedView: Field? = null
    private var fRedIsFolder: Field? = null
    private var fRedType: Field? = null
    private var fRedBackdropMode: Field? = null
    private var fRedBlurNode: Field? = null
    private var fRedRenderEffect: Field? = null
    private var fRedIsRecycled: Field? = null
    private var clsRenderEffectDrawable: Class<*>? = null
    private var clsBlurTransition: Class<*>? = null
    private var fUniDs: Field? = null
    private var mUniGetEffect: Method? = null
    private var mRedSetBackdropEffect: Method? = null
    private var mRedSetCornerRadius: Method? = null
    private var mRedApply: Method? = null
    private var mRenderNodeSetBackdropEffect: Method? = null

    private var fHotseatShortcuts: Field? = null
    private var fHotseatBlurProp: Field? = null
    private var fHotseatDockerBg: Field? = null
    private var fHotseatRadiusFactor: Field? = null
    private var mHotseatUpdateBlurParams: Method? = null
    private var mHotseatUpdateBounds: Method? = null
    private var mHotseatSetBgAlpha: Method? = null
    private var mHotseatSetBgVisible: Method? = null
    private var mHotseatSetDockerBg: Method? = null
    private var mCtrlGetBlurDrawable: Method? = null
    private var mCtrlRecycle: Method? = null
    private var mBlurSetMultiBlend: Method? = null
    private var mBlurSetBlurAlpha: Method? = null
    private var mPrepareBlurBase: Method? = null
    private var mPrepareRenderEffectBlur: Method? = null
    private var remCompanionObj: Any? = null
    private var mIsUniRenderSettingOn: Method? = null
    private var mIsUniRenderOpen: Method? = null
    private var mReadUniRenderCache: Method? = null
    private var blurPropsCompanionObj: Any? = null
    private var mIsSupportNewBlur: Method? = null
    private var mIsLauncherBlurEnabled: Method? = null
    @Volatile
    private var lastKnownContext: WeakReference<Context>? = null

    fun install(env: HookEnv) {
        val cfg = env.config
        glassFoldersEnabled = cfg.glassFolders
        glassDockEnabled = cfg.glassDock
        applyGloballyEnabled = cfg.applyGlobally
        updateDerivedParams(NativeGlassShader.GlassShaderParams.fromLauncher(cfg))
        launcherBlurRadius = cfg.launcherBlurRadius
        val initialStamp = HookConfig.stamp()
        configWatcher = GlassConfigWatcher(initialStamp, minIntervalMs = 1_500L) { ctx, reloadedCfg, disabled ->
            applyReloadedConfig(ctx, reloadedCfg, disabled)
        }

        resolveHandles(env)

        step("shader-injection") { hookShaderInjection(env) }
        step("shader-uniforms") { hookShaderUniforms(env) }
        step("render-effect-drawable") { hookRenderEffectDrawable(env) }
        step("hotseat-dock") { hookHotseatDock(env) }
        step("popup-glass") {
            LauncherPopupGlassHook.install(
                env = env,
                globalEnabled = { applyGloballyEnabled },
                params = { shaderParams },
                launcherBlurRadius = { launcherBlurRadius }
            )
        }
        step("global-blur-radius") { hookGlobalBlurRadius(env) }
        step("focus-hot-reload") { hookLauncherFocusReload(env) }
        ensureAndApplyGlobalBlurRadius(forceCreate = true)

        val p = shaderParams
        HookLog.ok(
            "[$TAG] 系统桌面液态玻璃已挂载 " +
                "(folders=$glassFoldersEnabled, dock=$glassDockEnabled, global=$applyGloballyEnabled, " +
                "hRaw=${p.bandRaw}, amt=${p.refractionAmt}, blur=$launcherBlurRadius, rim=${p.rimStrength})"
        )
    }

    private inline fun step(name: String, block: () -> Unit) {
        runCatching { block() }.onFailure { HookLog.error("[$TAG:$name] 安装失败", it) }
    }

    private fun resolveHandles(env: HookEnv) {
        val dsCls = env.findClass(CLS_AGSL_SHADER) ?: env.findClass(CLS_DRAWABLE_SHADER)
        if (dsCls != null) {
            fDsShader = dsCls.findField("v") ?: dsCls.findField("shader")
            fDsTextBuilder = dsCls.findField("t")
            fDsWidth = dsCls.findField("m") ?: dsCls.findField("width")
            fDsHeight = dsCls.findField("n") ?: dsCls.findField("height")
            fDsCornerHolder = dsCls.findField("k")
            fCornerHolderRadius = fDsCornerHolder?.type?.findField("b")
            mDsCornerApply = dsCls.findMethod("f", RuntimeShader::class.java)
        }

        env.findClass(CLS_RENDER_EFFECT_MANAGER)?.let { remCls ->
            val remCompanionCls = env.findClass("$CLS_RENDER_EFFECT_MANAGER\$Companion")
            remCompanionObj = runCatching { remCls.findField("Companion")?.get(null) }.getOrNull()
            mPrepareRenderEffectBlur = remCls.findMethod(
                "prepareBlur",
                View::class.java,
                Context::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ) ?: remCompanionCls?.findMethod(
                "prepareBlur",
                View::class.java,
                Context::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            mIsUniRenderSettingOn = remCls.findMethod("isUniRenderSettingOn")
                ?: remCompanionCls?.findMethod("isUniRenderSettingOn")
                ?: remCls.findMethod("isUiRenderEnable")
                ?: remCompanionCls?.findMethod("isUiRenderEnable")
            mIsUniRenderOpen = remCls.findMethod("isUniRenderOpen")
                ?: remCompanionCls?.findMethod("isUniRenderOpen")
            mReadUniRenderCache = remCls.findMethod("readUniRenderPropertyCacheAtStartup")
                ?: remCompanionCls?.findMethod("readUniRenderPropertyCacheAtStartup")
        }

        mRenderNodeSetBackdropEffect = runCatching {
            RenderNode::class.java.findMethod(
                "setBackdropRenderEffect",
                RenderEffect::class.java,
                Int::class.javaPrimitiveType!!
            )
        }.getOrNull()

        env.findClass(CLS_RENDER_EFFECT_DRAWABLE)?.let { redCls ->
            clsRenderEffectDrawable = redCls
            fRedUni = redCls.findField("mUniRenderDrawable")
            fRedView = redCls.findField("view")
            fRedIsFolder = redCls.findField("mIsFolder")
            fRedType = redCls.findField("type")
            fRedBackdropMode = redCls.findField("mBackdropMode")
            fRedBlurNode = redCls.findField("mBlurNode")
            fRedRenderEffect = redCls.findField("mRenderEffect")
            fRedIsRecycled = redCls.findField("mIsRecycled")
            mRedSetBackdropEffect = redCls.findMethod("setBackdropRenderEffect", RenderEffect::class.java, Int::class.javaPrimitiveType!!)
            mRedSetCornerRadius = redCls.findMethodsNamed("setCornerRadius")
                .firstOrNull { it.parameterTypes.firstOrNull() == Float::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
            mRedApply = redCls.findMethod("apply")

            val uniCls = fRedUni?.type
            if (uniCls != null) {
                fUniDs = uniCls.findField("c")
                    ?: dsCls?.let { dc ->
                        runCatching { uniCls.declaredFields.firstOrNull { dc.isAssignableFrom(it.type) }?.apply { isAccessible = true } }.getOrNull()
                    }
                mUniGetEffect = runCatching {
                    uniCls.declaredMethods.firstOrNull {
                        it.parameterCount == 0 && RenderEffect::class.java.isAssignableFrom(it.returnType)
                    }?.apply { isAccessible = true }
                }.getOrNull()
            }
        }

        env.findClass(CLS_OPLUS_HOTSEAT)?.let { hsCls ->
            fHotseatShortcuts = hsCls.findField("mShortcutsAndWidgets")
            fHotseatBlurProp = hsCls.findField("mBlurProp")
            fHotseatDockerBg = hsCls.findField("mDockerBackgroundDrawable")
            fHotseatRadiusFactor = hsCls.findField("BACKGROUND_SMOOTH_CORNER_RADIUS_FACTOR")
            mHotseatUpdateBlurParams = hsCls.findMethod("updateBlurParams", Float::class.javaPrimitiveType!!)
            mHotseatUpdateBounds = hsCls.findMethod("updateDockerBackgroundBounds")
            mHotseatSetBgAlpha = hsCls.findMethod("setBackgroundAlpha", Float::class.javaPrimitiveType!!)
            mHotseatSetBgVisible = hsCls.findMethod("setBackgroundVisible", Boolean::class.javaPrimitiveType!!)
            mHotseatSetDockerBg = hsCls.findMethod("setDockerBackground")
        }

        env.findClass(CLS_BLUR_BASE_FACTORY)?.let { factoryCls ->
            mPrepareBlurBase = factoryCls.findMethod(
                "prepareBlurBase",
                View::class.java,
                Context::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
        }

        env.findClass(CLS_BLUR_TRANSITION)?.let { btCls ->
            clsBlurTransition = btCls
            mBlurSetMultiBlend = btCls.findMethod(
                "setMultiBlendColor",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            mBlurSetBlurAlpha = btCls.findMethod("setBlurAlpha", Float::class.javaPrimitiveType!!)
        }

        val propCls = env.findClass(CLS_OPLUS_BLUR_PROPS)
        val companionCls = env.findClass(CLS_OPLUS_BLUR_COMPANION)
        if (propCls != null) {
            blurPropsCompanionObj = runCatching { propCls.findField("Companion")?.get(null) }.getOrNull()
            mIsSupportNewBlur = propCls.findMethod("isSupportNewBlur", Context::class.java)
                ?: companionCls?.findMethod("isSupportNewBlur", Context::class.java)
        }
        mIsLauncherBlurEnabled = env.findClass(CLS_EFFECT_UTILS)?.findMethod("isBlurEnabled")

        val iBlurCtrlCls = env.findClass("com.android.launcher3.uioverrides.states.blurdrawable.IBlurBaseController")
        if (iBlurCtrlCls != null) {
            mCtrlGetBlurDrawable = iBlurCtrlCls.findMethod("getBlurDrawable")
            mCtrlRecycle = iBlurCtrlCls.findMethod("recycle")
        }
    }

    private fun updateDerivedParams(base: NativeGlassShader.GlassShaderParams) {
        shaderParams = base
        dockParams = if (!base.enabled) {
            base
        } else {
            base.copy(
                refractionAmt = (base.refractionAmt * 1.15f).coerceIn(0f, 260f),
                rimStrength = (base.rimStrength * 0.85f).coerceIn(0f, 1f)
            )
        }
        folderParams = if (!base.enabled) {
            base
        } else {
            base.copy(
                bandRaw = (base.bandRaw * 0.90f).coerceIn(0f, 100f),
                refractionAmt = (base.refractionAmt * 1.12f).coerceIn(0f, 260f),
                rimStrength = (base.rimStrength * 0.65f).coerceIn(0f, 1f)
            )
        }
    }

    private fun maybeReloadConfig(force: Boolean = false, context: Context? = null): Boolean {
        if (context != null) {
            lastKnownContext = WeakReference(context.applicationContext ?: context)
        }
        val effectiveContext = context ?: lastKnownContext?.get()
        if (force) {
            configWatcher?.requestCheck(effectiveContext, force = true)
        } else {
            configWatcher?.scheduleCheck(effectiveContext)
        }
        return false
    }

    private fun applyReloadedConfig(context: Context?, fresh: HookConfig?, disabled: Boolean) {
        if (disabled || fresh == null) {
            if (glassFoldersEnabled || glassDockEnabled || applyGloballyEnabled || shaderParams.enabled) {
                glassFoldersEnabled = false
                glassDockEnabled = false
                applyGloballyEnabled = false
                launcherBlurRadius = 24f
                updateDerivedParams(
                    NativeGlassShader.GlassShaderParams.fromLauncher(
                        HookConfig(null),
                        surfaceEnabled = false
                    )
                )
                configRevision++
                LauncherPopupGlassHook.onConfigChanged()
                postHotReloadRefresh(context)
            }
            return
        }
        glassFoldersEnabled = fresh.glassFolders
        glassDockEnabled = fresh.glassDock
        applyGloballyEnabled = fresh.applyGlobally
        launcherBlurRadius = fresh.launcherBlurRadius
        updateDerivedParams(NativeGlassShader.GlassShaderParams.fromLauncher(fresh))
        configRevision++
        LauncherPopupGlassHook.onConfigChanged()
        postHotReloadRefresh(context)
    }

    private fun postHotReloadRefresh(context: Context?) {
        mainHandler.post {
            ensureAndApplyGlobalBlurRadius(forceCreate = glassFoldersEnabled || glassDockEnabled || applyGloballyEnabled)
            refreshAllEffectDrawables()
            val hsList = synchronized(trackedHotseats) { trackedHotseats.keys.toList() }
            hsList.forEach { hs ->
                (hs as? View)?.let { syncHotseatDockState(it) }
            }
            if (context != null && lastKnownContext?.get() == null) {
                lastKnownContext = WeakReference(context.applicationContext ?: context)
            }
        }
    }

    // ─────────────────── 1. AGSL 着色器注入 ───────────────────

    private fun hookShaderInjection(env: HookEnv) {
        val builderTargets = listOfNotNull(
            env.findClass(CLS_AGSL_BUILDER),
            env.findClass(CLS_BASE_SHADER_KT)
        ).distinct()

        builderTargets.forEach { cls ->
            cls.declaredMethods.forEach { m ->
                val p = m.parameterTypes
                if (p.isNotEmpty() && p[0] == StringBuilder::class.java &&
                    (m.name == "a" || m.name == "buildShaderString")
                ) {
                    env.hookAfter(m, "$TAG:${cls.simpleName}.${m.name}") { chain ->
                        val sb = chain.args.firstOrNull() as? StringBuilder ?: return@hookAfter
                        val raw = sb.toString()
                        if (!NativeGlassShader.isPatchedSource(raw) && NativeGlassShader.isTargetSource(raw)) {
                            val patched = NativeGlassShader.inject(raw)
                            if (patched != raw) {
                                sb.setLength(0)
                                sb.append(patched)
                            }
                        }
                    }
                }
            }
        }

        // OplusRuntimeShader.createRuntimeShader(String, boolean)
        env.findClass(CLS_OPLUS_RUNTIME_SHADER)?.findMethodsNamed("createRuntimeShader")?.forEach { m ->
            env.hookReplace(m, "$TAG:OplusRuntimeShader.createRuntimeShader") { chain ->
                val args = chain.args
                var usedArgs: Array<Any?>? = null
                val src = args.firstOrNull() as? String
                if (src != null && !NativeGlassShader.isPatchedSource(src) && NativeGlassShader.isTargetSource(src)) {
                    val patched = NativeGlassShader.inject(src)
                    if (patched != src) {
                        usedArgs = args.toTypedArray()
                        usedArgs[0] = patched
                    }
                }
                val result = if (usedArgs != null) chain.proceed(usedArgs) else chain.proceed()
                NativeGlassShader.trackIfPatchedArgs(result, usedArgs?.toList() ?: args)
                result
            }
        }

        // RuntimeShader 构造兜底
        env.findClass(CLS_RUNTIME_SHADER)?.declaredConstructors?.forEach { ctor ->
            env.hookReplace(ctor, "$TAG:RuntimeShader.ctor") { chain ->
                val args = chain.args
                var newArgs: Array<Any?>? = null
                for (i in args.indices) {
                    val v = args[i]
                    if (v is String && !NativeGlassShader.isPatchedSource(v) && NativeGlassShader.isTargetSource(v)) {
                        val patched = NativeGlassShader.inject(v)
                        if (patched != v) {
                            if (newArgs == null) newArgs = args.toTypedArray()
                            newArgs[i] = patched
                        }
                    }
                }
                val res = if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                val checkArgs = newArgs?.toList() ?: args
                NativeGlassShader.trackIfPatchedArgs(chain.thisObject ?: res, checkArgs)
                res
            }
        }
    }

    // ─────────────────── 2. Uniform 下发与角色判定 ───────────────────

    private fun hookShaderUniforms(env: HookEnv) {
        val dsCls = env.findClass(CLS_AGSL_SHADER) ?: env.findClass(CLS_DRAWABLE_SHADER) ?: return
        listOf("c", "f", "i", "k", "setBaseUniform", "setSize").forEach { name ->
            dsCls.findMethodsNamed(name).forEach { m ->
                env.hookAfter(m, "$TAG:${dsCls.simpleName}.$name") { chain ->
                    val ds = chain.thisObject ?: return@hookAfter
                    val argShader = chain.args.firstOrNull() as? RuntimeShader
                    pushUniformsForDs(ds, argShader)
                }
            }
        }
    }

    private fun pushUniformsForDs(ds: Any, explicitShader: RuntimeShader? = null) {
        maybeReloadConfig()
        val shader = explicitShader
            ?: runCatching { fDsShader?.get(ds) as? RuntimeShader }.getOrNull()
            ?: return
        if (!NativeGlassShader.isPatchedShader(shader)) {
            val builderText = runCatching { fDsTextBuilder?.get(ds)?.toString() }.getOrNull()
            if (builderText != null && NativeGlassShader.isPatchedSource(builderText)) {
                NativeGlassShader.markPatchedShader(shader)
            } else {
                return
            }
        }

        val role = resolveRoleForDs(ds)
        val fullScreen = isFullScreenLayer(ds)
        val bypass = fullScreen || when (role) {
            SurfaceRole.DOCK -> !glassDockEnabled
            SurfaceRole.FOLDER -> !glassFoldersEnabled
            SurfaceRole.OTHER -> !applyGloballyEnabled || isExcludedLauncherOther(ds)
        }
        val tunedParams = when {
            bypass -> shaderParams
            role == SurfaceRole.DOCK -> dockParams
            role == SurfaceRole.FOLDER -> folderParams
            else -> shaderParams
        }
        NativeGlassShader.pushUniforms(
            shader = shader,
            params = tunedParams,
            bypass = bypass,
            sourceScale = 1.0f,
            usePaintSaturation = false,
            forceG2 = !bypass
        )
    }

    private fun resolveRoleForDs(ds: Any): SurfaceRole {
        val red = dsToEffectDrawable[ds]?.get()
        if (red != null) {
            val role = classifyEffectDrawable(red)
            if (role != SurfaceRole.OTHER) {
                dsToRole[ds] = role
                return role
            }
        }
        return dsToRole[ds] ?: SurfaceRole.OTHER
    }

    private fun classifyEffectDrawable(red: Any): SurfaceRole {
        val type = runCatching { fRedType?.getInt(red) ?: -1 }.getOrDefault(-1)
        val isFolder = runCatching { fRedIsFolder?.getBoolean(red) ?: false }.getOrDefault(false)
        val view = runCatching { fRedView?.get(red) as? View }.getOrNull()

        if (type == 4 || hasAncestorMatching(view) { it.contains("Hotseat") || it.contains("hotseat") }) {
            return SurfaceRole.DOCK
        }
        if (isFolder || type == 1 || type == 2 || type == 9 || type == 15 || type == 16 ||
            hasAncestorMatching(view) {
                it.contains("FolderIcon") ||
                    it.contains("FlexibleFolderIcon") ||
                    it.contains("FolderRoundImageView") ||
                    it.contains("OplusPreviewBackground") ||
                    it.contains(".folder.")
            }
        ) {
            return SurfaceRole.FOLDER
        }
        return SurfaceRole.OTHER
    }

    private fun hasAncestorMatching(view: View?, predicate: (String) -> Boolean): Boolean {
        var cur: Any? = view
        var depth = 0
        while (cur != null && depth < 10) {
            if (predicate(cur.javaClass.name)) return true
            cur = (cur as? View)?.parent
            depth++
        }
        return false
    }

    private fun isExcludedLauncherOther(ds: Any): Boolean {
        val red = dsToEffectDrawable[ds]?.get() ?: return true
        val type = runCatching { fRedType?.getInt(red) ?: -1 }.getOrDefault(-1)
        // 13=抽屉分类背景、14=预览全屏过渡、17=抽屉推荐底板：保持原生；卡片/微件/指示器/菜单按钮放行
        return type == 13 || type == 14 || type == 17
    }

    private fun isFullScreenLayer(ds: Any): Boolean {
        val w = runCatching { (fDsWidth?.get(ds) as? Number)?.toFloat() ?: 0f }.getOrDefault(0f)
        val h = runCatching { (fDsHeight?.get(ds) as? Number)?.toFloat() ?: 0f }.getOrDefault(0f)
        if (w <= 1f || h <= 1f) return false
        val dm = Resources.getSystem().displayMetrics
        val maxScreen = max(dm.widthPixels, dm.heightPixels).toFloat()
        val minScreen = min(dm.widthPixels, dm.heightPixels).toFloat()
        return max(w, h) >= maxScreen * 0.96f && min(w, h) >= minScreen * 0.92f
    }

    // ─────────────────── 3. RenderEffectDrawable 绑定与刷新 ───────────────────

    private fun hookRenderEffectDrawable(env: HookEnv) {
        val redCls = env.findClass(CLS_RENDER_EFFECT_DRAWABLE) ?: return
        redCls.declaredConstructors.forEach { c ->
            env.hookAfter(c, "$TAG:RenderEffectDrawable.init") { chain ->
                bindEffectDrawable(chain.thisObject, rebake = false)
            }
        }
        redCls.findMethodsNamed("setBackdropRenderEffect").forEach { m ->
            env.hookAfter(m, "$TAG:RenderEffectDrawable.setBackdropRenderEffect") { chain ->
                val red = chain.thisObject ?: return@hookAfter
                val effectArg = chain.args.getOrNull(0) as? RenderEffect
                val modeArg = (chain.args.getOrNull(1) as? Int) ?: 1
                val role = classifyEffectDrawable(red)
                if (effectArg != null && !isUniRenderOpenNow() && isManagedSurfaceActive(role)) {
                    ensureRenderNodeBackdropEffect(red, effectArg, modeArg)
                }
                bindEffectDrawable(red, rebake = false)
            }
        }
        listOf("apply", "updateBackdropEffectConfig").forEach { name ->
            redCls.findMethodsNamed(name).forEach { m ->
                env.hookAfter(m, "$TAG:RenderEffectDrawable.$name") { chain ->
                    bindEffectDrawable(chain.thisObject, rebake = true)
                }
            }
        }
        redCls.findMethodsNamed("draw").forEach { m ->
            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Canvas::class.java) {
                env.hookBefore(m, "$TAG:RenderEffectDrawable.draw") { chain ->
                    val red = chain.thisObject ?: return@hookBefore
                    val viewCtx = runCatching { (fRedView?.get(red) as? View)?.context }.getOrNull()
                    maybeReloadConfig(context = viewCtx)
                    val rev = configRevision
                    val role = classifyEffectDrawable(red)
                    if (redLastRevision[red] != rev) {
                        redLastRevision[red] = rev
                        syncEffectDrawableCornerAndUniforms(red)
                    } else if (isManagedSurfaceActive(role) && !isUniRenderOpenNow()) {
                        ensureRenderNodeBackdropEffect(red)
                    }
                }
            }
        }
        redCls.findMethodsNamed("setCornerRadius").forEach { m ->
            env.hookReplace(m, "$TAG:RenderEffectDrawable.setCornerRadius") { chain ->
                val red = chain.thisObject
                if (settingCornerGuard.get() == true || red == null) {
                    val res = chain.proceed()
                    bindEffectDrawable(red, rebake = true)
                    return@hookReplace res
                }
                maybeReloadConfig()
                val args = chain.args
                val rawRadius = (args.getOrNull(0) as? Number)?.toFloat()
                if (rawRadius != null && rawRadius > 0f && args.isNotEmpty()) {
                    val tail = if (args.size > 1) args.drop(1).toTypedArray() else emptyArray<Any?>()
                    redBaseCornerCalls[red] = CornerCallRecord(rawRadius, tail)
                    val scale = cornerScaleForEffectDrawable(red)
                    if (kotlin.math.abs(scale - 1.0f) >= 0.01f) {
                        val newArgs = args.toTypedArray()
                        newArgs[0] = (rawRadius * scale).coerceAtLeast(4f)
                        val res = chain.proceed(newArgs)
                        bindEffectDrawable(red, rebake = true)
                        return@hookReplace res
                    }
                }
                val res = chain.proceed()
                bindEffectDrawable(red, rebake = true)
                res
            }
        }
        redCls.findMethodsNamed("copy").forEach { m ->
            env.hookReplace(m, "$TAG:RenderEffectDrawable.copy") { chain ->
                val res = chain.proceed()
                bindEffectDrawable(res, rebake = true)
                res
            }
        }
    }

    private fun cornerScaleForEffectDrawable(red: Any): Float {
        if (!shaderParams.enabled) return 1.0f
        return when (classifyEffectDrawable(red)) {
            SurfaceRole.FOLDER -> if (glassFoldersEnabled) shaderParams.cornerScale else 1.0f
            SurfaceRole.DOCK -> if (glassDockEnabled) shaderParams.cornerScale else 1.0f
            SurfaceRole.OTHER -> {
                val type = runCatching { fRedType?.getInt(red) ?: -1 }.getOrDefault(-1)
                if (applyGloballyEnabled && type != 13 && type != 14 && type != 17) shaderParams.cornerScale else 1.0f
            }
        }
    }

    private fun bindEffectDrawable(red: Any?, rebake: Boolean = false) {
        if (red == null) return
        trackedEffectDrawables[red] = true
        val uni = runCatching { fRedUni?.get(red) }.getOrNull() ?: return
        val ds = runCatching { fUniDs?.get(uni) }.getOrNull() ?: return
        dsToEffectDrawable[ds] = WeakReference(red)
        val role = classifyEffectDrawable(red)
        if (role != SurfaceRole.OTHER) {
            dsToRole[ds] = role
            if (lastGlobalBlurRevision != configRevision) {
                lastGlobalBlurRevision = configRevision
                ensureAndApplyGlobalBlurRadius(forceCreate = false)
            }
        }
        pushUniformsForDs(ds)
        redLastRevision[red] = configRevision
        if (rebake && role != SurfaceRole.OTHER && rebakingEffectGuard.get() != true) {
            rebakeBackdropEffect(red, uni)
        }
    }

    private fun rebakeBackdropEffect(red: Any, explicitUni: Any? = null) {
        if (rebakingEffectGuard.get() == true) return
        val uni = explicitUni ?: runCatching { fRedUni?.get(red) }.getOrNull() ?: return
        val mode = runCatching { fRedBackdropMode?.getInt(red) }.getOrNull() ?: 1
        val effect = runCatching { mUniGetEffect?.invoke(uni) as? RenderEffect }.getOrNull() ?: return
        rebakingEffectGuard.set(true)
        try {
            runCatching { mRedSetBackdropEffect?.invoke(red, effect, mode) }
            ensureRenderNodeBackdropEffect(red, effect, mode)
        } finally {
            rebakingEffectGuard.remove()
        }
    }

    private fun syncEffectDrawableCornerAndUniforms(red: Any) {
        runCatching {
            val uni = fRedUni?.get(red)
            val ds = uni?.let { fUniDs?.get(it) }
            val cornerRec = redBaseCornerCalls[red]
            val role = classifyEffectDrawable(red)
            if (role != SurfaceRole.OTHER && lastGlobalBlurRevision != configRevision) {
                lastGlobalBlurRevision = configRevision
                ensureAndApplyGlobalBlurRadius(forceCreate = false)
            }
            if (cornerRec != null) {
                val scale = cornerScaleForEffectDrawable(red)
                val targetRadius = (cornerRec.baseRadius * scale).coerceAtLeast(4f)
                val cornerMethod = mRedSetCornerRadius
                if (cornerMethod != null && cornerMethod.parameterCount == 1 + cornerRec.extraArgs.size) {
                    settingCornerGuard.set(true)
                    try {
                        val invokeArgs = Array<Any?>(1 + cornerRec.extraArgs.size) { idx ->
                            if (idx == 0) targetRadius else cornerRec.extraArgs[idx - 1]
                        }
                        cornerMethod.invoke(red, *invokeArgs)
                    } finally {
                        settingCornerGuard.remove()
                    }
                }
                if (ds != null) {
                    val holder = fDsCornerHolder?.get(ds)
                    val shader = fDsShader?.get(ds) as? RuntimeShader
                    if (holder != null && shader != null) {
                        fCornerHolderRadius?.setFloat(holder, targetRadius)
                        mDsCornerApply?.invoke(ds, shader)
                    }
                }
            }
            if (ds != null) {
                dsToEffectDrawable[ds] = WeakReference(red)
                if (role != SurfaceRole.OTHER) dsToRole[ds] = role
                pushUniformsForDs(ds)
            } else {
                bindEffectDrawable(red, rebake = false)
            }
            redLastRevision[red] = configRevision
            if (uni != null) {
                rebakeBackdropEffect(red, uni)
            }
        }
    }

    private fun refreshAllEffectDrawables() {
        val list = synchronized(trackedEffectDrawables) { trackedEffectDrawables.keys.toList() }
        list.forEach { red ->
            syncEffectDrawableCornerAndUniforms(red)
            runCatching { (red as? Drawable)?.invalidateSelf() }
        }
    }

    // ─────────────────── 4. Dock 栏背景激活与渲染 ───────────────────

    private fun hookHotseatDock(env: HookEnv) {
        // 当正在应用 Dock 玻璃效果时，临时放行桌面模糊总开关，避免 applyBlurParams / sb9.b 静默跳过
        val installDockBlurGate = { m: Method, name: String ->
            env.hookReplace(m, name) { chain ->
                val res = chain.proceed()
                if (res != true && glassDockEnabled && dockUpdatingGuard.get() == true) {
                    true
                } else {
                    res
                }
            }
        }
        listOf(CLS_OPLUS_BLUR_PROPS, CLS_OPLUS_BLUR_COMPANION).forEach { clsName ->
            env.findClass(clsName)?.findMethodsNamed("isSupportNewBlur")?.forEach { m ->
                installDockBlurGate(m, "$TAG:${m.declaringClass.simpleName}.isSupportNewBlur")
            }
        }
        env.findClass(CLS_EFFECT_UTILS)?.findMethodsNamed("isBlurEnabled")?.forEach { m ->
            installDockBlurGate(m, "$TAG:LauncherMaterialEffectUtils.isBlurEnabled")
        }
        listOf(CLS_RENDER_EFFECT_MANAGER, "$CLS_RENDER_EFFECT_MANAGER\$Companion").forEach { clsName ->
            env.findClass(clsName)?.findMethodsNamed("isUniRenderOpen")?.forEach { m ->
                installDockBlurGate(m, "$TAG:${m.declaringClass.simpleName}.isUniRenderOpen")
            }
        }

        val hsCls = env.findClass(CLS_OPLUS_HOTSEAT) ?: return

        hsCls.findMethodsNamed("setDockerBackground").forEach { m ->
            env.hookReplace(m, "$TAG:OplusHotseat.setDockerBackground") { chain ->
                val hotseat = chain.thisObject ?: return@hookReplace chain.proceed()
                trackedHotseats[hotseat] = true
                if (dockUpdatingGuard.get() == true) return@hookReplace chain.proceed()

                val viewCtx = (hotseat as? View)?.context
                maybeReloadConfig(context = viewCtx)
                if (!glassDockEnabled) {
                    val res = chain.proceed()
                    clearStaleBlurControllerIfNativeEmpty(hotseat)
                    return@hookReplace res
                }

                if (applyDockGlassBackground(hotseat)) {
                    Unit
                } else {
                    chain.proceed()
                }
            }
        }

        hsCls.findMethodsNamed("updateDockerBgIfNecessary").forEach { m ->
            env.hookReplace(m, "$TAG:OplusHotseat.updateDockerBgIfNecessary") { chain ->
                val hotseat = chain.thisObject ?: return@hookReplace chain.proceed()
                val view = hotseat as? View
                maybeReloadConfig(context = view?.context)
                if (glassDockEnabled) {
                    val cur = runCatching { fHotseatDockerBg?.get(hotseat) }.getOrNull()
                    if (!isDockDrawableReady(cur) && (view?.measuredHeight ?: 0) > 0) {
                        applyDockGlassBackground(hotseat)
                    }
                    return@hookReplace Unit
                }
                chain.proceed()
            }
        }

        hsCls.findMethodsNamed("onLayout").forEach { m ->
            env.hookAfter(m, "$TAG:OplusHotseat.onLayout") { chain ->
                val hotseat = chain.thisObject as? View ?: return@hookAfter
                trackedHotseats[hotseat] = true
                syncHotseatDockState(hotseat)
            }
        }

        hsCls.findMethodsNamed("onAttachedToWindow").forEach { m ->
            env.hookAfter(m, "$TAG:OplusHotseat.onAttachedToWindow") { chain ->
                val hotseat = chain.thisObject as? View ?: return@hookAfter
                trackedHotseats[hotseat] = true
                hotseat.post { syncHotseatDockState(hotseat) }
            }
        }

        hsCls.findMethodsNamed("onWindowVisibilityChanged").forEach { m ->
            env.hookAfter(m, "$TAG:OplusHotseat.onWindowVisibilityChanged") { chain ->
                val hotseat = chain.thisObject as? View ?: return@hookAfter
                val vis = (chain.args.firstOrNull() as? Int) ?: return@hookAfter
                if (vis == View.VISIBLE) {
                    trackedHotseats[hotseat] = true
                    syncHotseatDockState(hotseat)
                }
            }
        }

        hsCls.findMethodsNamed("onDraw").forEach { m ->
            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Canvas::class.java) {
                env.hookBefore(m, "$TAG:OplusHotseat.onDraw") { chain ->
                    val hotseat = chain.thisObject as? View ?: return@hookBefore
                    maybeReloadConfig(context = hotseat.context)
                    if (hotseatLastRevision[hotseat] != configRevision ||
                        (glassDockEnabled && !isDockDrawableReady(runCatching { fHotseatDockerBg?.get(hotseat) }.getOrNull()))
                    ) {
                        syncHotseatDockState(hotseat)
                    }
                }
            }
        }

        hsCls.findMethodsNamed("onWallpaperBrightnessChanged").forEach { m ->
            env.hookAfter(m, "$TAG:OplusHotseat.onWallpaperBrightnessChanged") { chain ->
                val hotseat = chain.thisObject as? View ?: return@hookAfter
                maybeReloadConfig(context = hotseat.context)
                if (glassDockEnabled) {
                    if (applyDockGlassBackground(hotseat)) {
                        hotseatLastRevision[hotseat] = configRevision
                        hotseatLastHeight[hotseat] = hotseat.measuredHeight
                    }
                }
            }
        }

        // 直板机在 OplusBlurProperties.setBlurEnable(true) 时原生只刷新折叠屏 Hotseat，这里补齐刷新
        listOf(CLS_OPLUS_BLUR_PROPS, CLS_OPLUS_BLUR_COMPANION).forEach { clsName ->
            env.findClass(clsName)?.findMethodsNamed("setBlurEnable")?.forEach { m ->
                env.hookAfter(m, "$TAG:OplusBlurProperties.setBlurEnable") { chain ->
                    val enable = chain.args.firstOrNull { it is Boolean } as? Boolean ?: return@hookAfter
                    if (enable && glassDockEnabled) {
                        val hsList = synchronized(trackedHotseats) { trackedHotseats.keys.toList() }
                        hsList.forEach { hs ->
                            (hs as? View)?.let { view ->
                                hotseatLastRevision.remove(view)
                                view.post { syncHotseatDockState(view) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isManagedSurfaceActive(role: SurfaceRole): Boolean = when (role) {
        SurfaceRole.DOCK -> glassDockEnabled
        SurfaceRole.FOLDER -> glassFoldersEnabled
        SurfaceRole.OTHER -> applyGloballyEnabled
    }

    private fun isDockDrawableReady(drawable: Any?): Boolean {
        if (drawable == null) return false
        val d = drawable as? Drawable
        if (d != null && d.bounds.isEmpty) return false
        if (clsRenderEffectDrawable?.isInstance(drawable) == true) {
            val recycled = runCatching { fRedIsRecycled?.getBoolean(drawable) == true }.getOrDefault(false)
            if (recycled) return false
            val effect = runCatching { fRedRenderEffect?.get(drawable) }.getOrNull()
            return effect != null
        }
        return clsBlurTransition?.isInstance(drawable) == true
    }

    private fun isUniRenderOpenNow(): Boolean = runCatching {
        val m = mIsUniRenderOpen ?: return@runCatching true
        val receiver = if (Modifier.isStatic(m.modifiers)) null else remCompanionObj
        (m.invoke(receiver) as? Boolean) ?: true
    }.getOrDefault(true)

    private fun ensureRenderNodeBackdropEffect(
        red: Any,
        explicitEffect: RenderEffect? = null,
        explicitMode: Int? = null
    ) {
        if (clsRenderEffectDrawable?.isInstance(red) != true) return
        val effect = explicitEffect ?: runCatching { fRedRenderEffect?.get(red) as? RenderEffect }.getOrNull() ?: return
        val mode = explicitMode ?: runCatching { fRedBackdropMode?.getInt(red) }.getOrNull() ?: 1
        runCatching {
            fRedRenderEffect?.set(red, effect)
            fRedIsRecycled?.setBoolean(red, false)
            val blurNode = fRedBlurNode?.get(red) ?: return@runCatching
            mRenderNodeSetBackdropEffect?.invoke(blurNode, effect, mode)
        }
    }

    private fun syncHotseatDockState(hotseat: View) {
        maybeReloadConfig(context = hotseat.context)
        val rev = configRevision
        val prevRev = hotseatLastRevision[hotseat]
        val prevH = hotseatLastHeight[hotseat] ?: -1
        val curH = hotseat.measuredHeight
        val curBg = runCatching { fHotseatDockerBg?.get(hotseat) }.getOrNull()
        val curBgReady = isDockDrawableReady(curBg)

        if (glassDockEnabled) {
            if (curH > 0 && (!curBgReady || prevRev != rev || prevH != curH)) {
                if (applyDockGlassBackground(hotseat)) {
                    hotseatLastRevision[hotseat] = rev
                    hotseatLastHeight[hotseat] = curH
                }
            }
        } else if (prevRev != rev) {
            hotseatLastRevision[hotseat] = rev
            runCatching { mHotseatSetDockerBg?.invoke(hotseat) }
            clearStaleBlurControllerIfNativeEmpty(hotseat)
        }
    }

    private fun clearStaleBlurControllerIfNativeEmpty(hotseat: Any) {
        runCatching {
            val prop = fHotseatBlurProp?.get(hotseat)
            if (prop != null && fHotseatDockerBg?.get(hotseat) == null) {
                mCtrlRecycle?.invoke(prop)
                fHotseatBlurProp?.set(hotseat, null)
            }
        }
    }

    private fun applyDockGlassBackground(hotseat: Any): Boolean {
        val view = hotseat as? View ?: return false
        val context = view.context ?: return false
        lastKnownContext = WeakReference(context.applicationContext ?: context)
        val shortcuts = runCatching { fHotseatShortcuts?.get(hotseat) as? ViewGroup }.getOrNull() ?: return false
        if (shortcuts.childCount == 0) return false
        val measuredHeight = view.measuredHeight
        if (measuredHeight <= 0) return false

        val uniSettingOn = runCatching {
            val m = mIsUniRenderSettingOn
            if (m != null) {
                val receiver = if (Modifier.isStatic(m.modifiers)) null else remCompanionObj
                (m.invoke(receiver) as? Boolean) ?: false
            } else {
                false
            }
        }.getOrNull() ?: false
        if (!uniSettingOn) {
            runCatching { mReadUniRenderCache?.invoke(remCompanionObj) }
        }

        val supportNewBlur = runCatching {
            val m = mIsSupportNewBlur
            if (m != null) {
                val receiver = if (Modifier.isStatic(m.modifiers)) null else blurPropsCompanionObj
                m.invoke(receiver, context) as? Boolean
            } else {
                true
            }
        }.getOrNull() ?: true

        dockUpdatingGuard.set(true)
        return try {
            val factor = runCatching { (fHotseatRadiusFactor?.get(null) as? Float) ?: 0.44f }.getOrDefault(0.44f)
            var prop = fHotseatBlurProp?.get(hotseat)
            val existingDrawable = prop?.let { mCtrlGetBlurDrawable?.invoke(it) }
            val existingRecycled = existingDrawable != null &&
                clsRenderEffectDrawable?.isInstance(existingDrawable) == true &&
                runCatching { fRedIsRecycled?.getBoolean(existingDrawable) == true }.getOrDefault(false)
            val staleNonEffect = existingDrawable != null &&
                clsRenderEffectDrawable?.isInstance(existingDrawable) != true &&
                mPrepareRenderEffectBlur != null
            val wasNull = existingDrawable == null || existingRecycled || staleNonEffect
            if (wasNull) {
                if (prop != null && (existingRecycled || staleNonEffect)) {
                    runCatching { mCtrlRecycle?.invoke(prop) }
                }
                val remReceiver = mPrepareRenderEffectBlur?.let { m ->
                    if (Modifier.isStatic(m.modifiers)) null else remCompanionObj
                }
                prop = if (supportNewBlur) {
                    mPrepareBlurBase?.invoke(null, shortcuts, context, false, false, 4, true)
                } else {
                    mPrepareRenderEffectBlur?.invoke(remReceiver, shortcuts, context, false, false, 4, true)
                        ?: mPrepareBlurBase?.invoke(null, shortcuts, context, false, false, 4, true)
                }
                fHotseatBlurProp?.set(hotseat, prop)
            }
            if (prop == null) {
                view.post { syncHotseatDockState(view) }
                return false
            }

            // 传未缩放的基础圆角给 updateBlurParams；setCornerRadius Hook 只会乘一次 cornerScale
            val baseRadius = (measuredHeight * factor).coerceIn(4f, measuredHeight * 0.5f)
            val bgDrawable = mHotseatUpdateBlurParams?.invoke(hotseat, baseRadius) as? Drawable ?: return false
            if (wasNull) {
                runCatching { mBlurSetMultiBlend?.invoke(bgDrawable, 0, 0, false) }
            }

            val oldBg = fHotseatDockerBg?.get(hotseat) as? Drawable
            if (oldBg !== bgDrawable) {
                oldBg?.callback = null
                fHotseatDockerBg?.set(hotseat, bgDrawable)
            }
            bgDrawable.callback = view
            shortcuts.background = null
            mHotseatUpdateBounds?.invoke(hotseat)
            val bounds = bgDrawable.bounds
            val fallbackW = shortcuts.width.takeIf { it > 0 } ?: shortcuts.measuredWidth
            val fallbackH = shortcuts.height.takeIf { it > 0 } ?: shortcuts.measuredHeight
            if ((bounds.isEmpty || bounds.width() < fallbackW / 2 || bounds.height() < fallbackH / 3) &&
                fallbackW > 0 && fallbackH > 0
            ) {
                bgDrawable.setBounds(0, 0, fallbackW, fallbackH)
            }
            runCatching { mHotseatSetBgVisible?.invoke(hotseat, true) }
            mHotseatSetBgAlpha?.invoke(hotseat, 1.0f)
            runCatching { mBlurSetBlurAlpha?.invoke(bgDrawable, 1.0f) }
            if (bgDrawable.alpha == 0) bgDrawable.alpha = 255
            runCatching { mRedApply?.invoke(bgDrawable) }
            view.setWillNotDraw(false)
            bindEffectDrawable(bgDrawable, rebake = false)
            syncEffectDrawableCornerAndUniforms(bgDrawable)
            ensureRenderNodeBackdropEffect(bgDrawable)
            view.invalidate()
            if (wasNull || oldBg !== bgDrawable) {
                HookLog.ok(
                    "[$TAG] Dock 玻璃背景已就绪 " +
                        "(bounds=${bgDrawable.bounds.toShortString()}, r=$baseRadius, scale=${shaderParams.cornerScale}, h=$measuredHeight)"
                )
            }
            !bgDrawable.bounds.isEmpty
        } catch (t: Throwable) {
            HookLog.error("[$TAG:applyDockGlassBackground] 设置 Dock 玻璃背景失败", t)
            false
        } finally {
            dockUpdatingGuard.remove()
        }
    }

    // ─────────────────── 5. 桌面与 Dock 模糊半径调节 (OplusGlobalEffect) ───────────────────

    private fun computeScaledLauncherBlur(baseRadius: Float): Float {
        if (!glassFoldersEnabled && !glassDockEnabled && !applyGloballyEnabled) return baseRadius
        val ratio = (launcherBlurRadius / 24f).coerceIn(0.05f, 1.5f)
        return (baseRadius * ratio * 0.85f).coerceIn(6f, 160f)
    }

    private fun hookGlobalBlurRadius(env: HookEnv) {
        val geCls = env.findClass("com.oplus.graphics.OplusGlobalEffect") ?: return
        val paramCls = env.findClass("com.oplus.graphics.OplusGlobalBlurEffectParam") ?: return
        clsGlobalBlurParam = paramCls
        fGlobalBlurRadius = paramCls.findField("blurRadius")
        fGlobalBlendMode = paramCls.findField("blendMode")
        fGlobalBlendColor0 = paramCls.findField("blendColor0")
        fGlobalBlendColor1 = paramCls.findField("blendColor1")
        fGlobalBlurThrottleMs = paramCls.findField("blurThrottleMs")

        val method = geCls.findMethodsNamed("setEffectParam").firstOrNull { it.parameterCount == 1 } ?: return
        mGlobalSetEffectParam = method

        env.hookBefore(method, "$TAG:OplusGlobalEffect.setEffectParam") { chain ->
            if (updatingGlobalEffectGuard.get() == true) return@hookBefore
            val param = chain.args.firstOrNull() ?: return@hookBefore
            if (!paramCls.isInstance(param)) return@hookBefore
            maybeReloadConfig()
            val radiusField = fGlobalBlurRadius ?: return@hookBefore
            val raw = runCatching { radiusField.getFloat(param) }.getOrNull() ?: return@hookBefore
            val base = globalParamBaseRadius.getOrPut(param) { raw }
            lastGlobalParamRef = WeakReference(param)
            val target = computeScaledLauncherBlur(base)
            runCatching { radiusField.setFloat(param, target) }
        }
    }

    private fun ensureAndApplyGlobalBlurRadius(forceCreate: Boolean) {
        val setter = mGlobalSetEffectParam ?: return
        val paramCls = clsGlobalBlurParam ?: return
        val radiusField = fGlobalBlurRadius ?: return

        val existing = lastGlobalParamRef?.get()
        if (existing != null) {
            val base = globalParamBaseRadius[existing] ?: 120f
            val target = computeScaledLauncherBlur(base)
            val cur = runCatching { radiusField.getFloat(existing) }.getOrDefault(-1f)
            if (forceCreate || kotlin.math.abs(cur - target) > 0.25f) {
                runCatching {
                    updatingGlobalEffectGuard.set(true)
                    radiusField.setFloat(existing, target)
                    setter.invoke(null, existing)
                }.also {
                    updatingGlobalEffectGuard.remove()
                }
            }
            return
        }

        if (!forceCreate || (!glassFoldersEnabled && !glassDockEnabled && !applyGloballyEnabled)) return
        runCatching {
            val param = paramCls.getDeclaredConstructor().newInstance()
            val target = computeScaledLauncherBlur(120f)
            radiusField.setFloat(param, target)
            fGlobalBlendMode?.setInt(param, 0)
            fGlobalBlendColor0?.set(param, floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f))
            fGlobalBlendColor1?.set(param, floatArrayOf(0.32156864f, 0.3882353f, 0.4f, 0.4f))
            fGlobalBlurThrottleMs?.setInt(param, 50)
            globalParamBaseRadius[param] = 120f
            lastGlobalParamRef = WeakReference(param)
            updatingGlobalEffectGuard.set(true)
            setter.invoke(null, param)
            lastGlobalBlurRevision = configRevision
            HookLog.ok("[$TAG] 已初始化桌面与 Dock 模糊半径 blurRadius=$target (slider=${launcherBlurRadius}dp)")
        }.also {
            updatingGlobalEffectGuard.remove()
        }
    }

    // ─────────────────── 6. 返回桌面时即时热重载配置 ───────────────────

    private fun hookLauncherFocusReload(env: HookEnv) {
        val launcherCls = env.findClass("com.android.launcher.Launcher")
            ?: env.findClass("com.android.launcher3.Launcher")
            ?: return
        launcherCls.findMethodsNamed("onResume").forEach { m ->
            env.hookAfter(m, "$TAG:Launcher.onResume") { chain ->
                val launcherCtx = chain.thisObject as? Context
                configWatcher?.requestCheck(launcherCtx, force = true)
                val hsList = synchronized(trackedHotseats) { trackedHotseats.keys.toList() }
                hsList.forEach { hs ->
                    (hs as? View)?.let { view ->
                        val curBg = runCatching { fHotseatDockerBg?.get(view) }.getOrNull()
                        if (glassDockEnabled && !isDockDrawableReady(curBg)) {
                            view.post { syncHotseatDockState(view) }
                        }
                    }
                }
            }
        }
    }
}
