package com.c17.glass.xposed.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.c17.glass.xposed.statusbar.HookConfig
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.findMethodsNamed
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * ColorOS 17 负一屏（Breeno 速览，`com.coloros.assistantscreen`）顶栏液态玻璃 Hook。
 * 仅顶栏搜索框胶囊与环形按钮替换为低模糊高折射液态玻璃；卡片、堆叠背景与浮窗保持原生。
 */
internal object AssistantGlassHook {

    private const val TAG = "assistant-glass"

    private const val CLS_OPLUS_BG_RENDER_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect"
    private const val CLS_ASSISTANT_CONTAINER = "com.oplus.card.ui.AssistantScreenContainer"
    private const val CLS_ASSISTANT_HEAD = "com.oplus.card.ui.header.AssistantScreenHeadView"

    private const val BOX_GLASS_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uRadius;
uniform float uBand;
uniform float uAmount;
uniform float uRim;
uniform float4 uTint;
uniform float uSat;
uniform float uPill;

float2 safeNorm(float2 v) {
    float l = length(v);
    return l > 1e-5 ? v / l : float2(0.0);
}

float lp25(float2 v) {
    v = max(v, float2(0.0));
    return pow(max(dot(v * v, sqrt(v)), 0.00000001), 0.4);
}

float3 boxGeom(float2 p) {
    float2 halfSize = uSize * 0.5;
    float r = clamp(uRadius, 2.0, min(halfSize.x, halfSize.y));
    if (uPill > 0.5 || r >= min(halfSize.x, halfSize.y) * 0.85) {
        float2 d = p - halfSize;
        float2 q = abs(d) - (halfSize - float2(r));
        float dist = min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
        float2 m = max(q, float2(0.0));
        float2 grad = (m.x > 1e-4 || m.y > 1e-4)
            ? sign(d) * safeNorm(m)
            : sign(d) * (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
        return float3(dist, grad);
    }
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
    float2 normal = safeNorm(z * sqrt(z));
    float distance = length(q - v) * (inside < 0.0 ? -1.0 : 1.0);
    return float3(distance, normal * s);
}

float circleMap(float x) {
    float t = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, (1.0 - t) * (1.0 + t)));
}

half4 sampleLens(float2 q, float2 halfSize, float band) {
    float3 geom = boxGeom(q);
    float depth = max(-geom.x, 0.0);
    float normPos = band > 0.5 ? clamp(1.0 - depth / band, 0.0, 1.0) : 0.0;
    float slope = circleMap(normPos);
    float mn = min(halfSize.x, halfSize.y);
    float shift = min(slope * uAmount, max(mn * 0.90, 14.0));
    float2 n = geom.yz;
    float2 maxCoord = max(uSize - float2(1.0), float2(1.0));
    float2 samplePos = clamp(q - shift * n, float2(0.0), maxCoord);
    half4 base = content.eval(samplePos);
    if (shift > 2.5 && base.a > half(0.01)) {
        float disp = min(shift * 0.085, 7.0);
        float2 pR = clamp(samplePos - disp * n, float2(0.0), maxCoord);
        float2 pB = clamp(samplePos + disp * n, float2(0.0), maxCoord);
        half rCh = content.eval(pR).r;
        half bCh = content.eval(pB).b;
        base = half4(mix(base.r, rCh, half(0.50)), base.g, mix(base.b, bCh, half(0.50)), base.a);
    }
    return base;
}

half4 main(float2 pos) {
    float2 halfSize = uSize * 0.5;
    float3 geom = boxGeom(pos);
    float sdf = geom.x;
    if (sdf >= 0.75) return half4(0.0);

    float depth = max(-sdf, 0.0);
    float band = max(uBand, 1.0);
    float normPos = clamp(1.0 - depth / band, 0.0, 1.0);
    float slope = circleMap(normPos);
    float2 maxCoord = max(uSize - float2(1.0), float2(1.0));

    half4 sampled;
    if (uAmount < 0.5 || depth >= band + 1.0) {
        sampled = content.eval(clamp(pos, float2(0.0), maxCoord));
    } else {
        half4 acc = half4(0.0);
        for (int yy = 0; yy < 2; yy++) {
            for (int xx = 0; xx < 2; xx++) {
                float2 q = pos + (float2(float(xx), float(yy)) + 0.5) * 0.5 - 0.5;
                acc += sampleLens(q, halfSize, band);
            }
        }
        sampled = acc * half(0.25);
    }

    float bgVis = clamp(float(sampled.a), 0.0, 1.0);
    float3 col = bgVis > 0.01 ? float3(sampled.rgb) / max(bgVis, 0.01) : uTint.rgb;
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(float3(luma), col, uSat), 0.0, 1.0);

    float veilAlpha = uTint.a * (1.0 - 0.38 * smoothstep(0.55, 1.0, slope));
    col = mix(col, uTint.rgb, veilAlpha);

    float rimAdd = 0.0;
    if (uRim > 0.005) {
        float edgeBand = smoothstep(0.42, 0.90, slope);
        float bright = smoothstep(0.76, 1.0, slope);
        float directional = 0.55 + 0.45 * max(dot(geom.yz, normalize(float2(-0.45, -0.89))), -0.3);
        float mask = clamp(uRim * edgeBand * (0.28 + 0.56 * bright) * directional, 0.0, 0.80);
        col = mix(col, min(col * 1.24 + float3(0.14), float3(1.0)), mask);
        rimAdd = mask * 0.45;
    }

    float edgeAlpha = 1.0 - smoothstep(-0.85, 0.50, sdf);
    float outAlpha = clamp(max(bgVis, max(uTint.a, rimAdd)) * edgeAlpha, 0.0, 1.0);
    return half4(half3(clamp(col, float3(0.0), float3(1.0)) * outAlpha), half(outAlpha));
}
"""

    private data class TrackedBox(
        val originalEffect: RenderEffect?,
        var lastW: Int = 0,
        var lastH: Int = 0,
        var lastRevision: Long = -1L
    )

    @Volatile
    private var glassAssistantEnabled: Boolean = true

    @Volatile
    private var shaderParams: NativeGlassShader.GlassShaderParams =
        NativeGlassShader.GlassShaderParams.fromAssistant(HookConfig(null))

    @Volatile
    private var blurRadiusDp: Float = 6f

    @Volatile
    private var cornerRadiusDp: Float = 24f

    @Volatile
    private var configRevision: Long = 1L

    @Volatile
    private var configWatcher: GlassConfigWatcher? = null

    @Volatile
    private var lastKnownContext: WeakReference<Context>? = null

    private var mSetBgRenderEffect: Method? = null

    private val trackedBoxes = Collections.synchronizedMap(WeakHashMap<View, TrackedBox>())
    private val layoutHooked = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val applyingEffectGuard = ThreadLocal<Boolean>()
    private val loggedFirstApply = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var headRef: WeakReference<View>? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { healTopBar() }
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    private val sharedLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val record = trackedBoxes[v] ?: return@OnLayoutChangeListener
        if (v.width <= 0 || v.height <= 0) return@OnLayoutChangeListener
        if (record.lastW != v.width || record.lastH != v.height || record.lastRevision != configRevision) {
            applyOrRestoreBox(v, record)
        }
    }

    fun install(env: HookEnv) {
        val cfg = env.config
        glassAssistantEnabled = cfg.glassAssistant
        shaderParams = NativeGlassShader.GlassShaderParams.fromAssistant(cfg)
        blurRadiusDp = cfg.assistantBlurRadius
        cornerRadiusDp = cfg.assistantCornerRadius

        configWatcher = GlassConfigWatcher(HookConfig.stamp(), minIntervalMs = 1_500L) { ctx, fresh, disabled ->
            applyReloadedConfig(ctx, fresh, disabled)
        }

        resolveReflectHandles(env)
        step("bg-render-effect") { hookBackgroundRenderEffect(env) }
        step("head-lifecycle") { hookHeadLifecycle(env) }
        mainHandler.postDelayed(heartbeat, 2_000L)

        val p = shaderParams
        HookLog.ok(
            "[$TAG] 负一屏顶栏液态玻璃已挂载 " +
                "(enabled=$glassAssistantEnabled, blur=${blurRadiusDp}dp, amt=${p.refractionAmt}, band=${p.bandRaw}, rim=${p.rimStrength})"
        )
    }

    private inline fun step(name: String, block: () -> Unit) {
        runCatching { block() }.onFailure { HookLog.error("[$TAG:$name] 安装失败", it) }
    }

    private fun resolveReflectHandles(env: HookEnv) {
        env.findClass(CLS_OPLUS_BG_RENDER_EFFECT)?.let { cls ->
            mSetBgRenderEffect = cls.findMethodsNamed("setBackgroundRenderEffect")
                .firstOrNull { it.parameterTypes.size == 2 }
                ?.apply { isAccessible = true }
        }
    }

    private fun hookBackgroundRenderEffect(env: HookEnv) {
        val method = mSetBgRenderEffect ?: return
        env.hookReplace(method, "$TAG:OplusViewBackgroundRenderEffect.setBackgroundRenderEffect") { chain ->
            if (applyingEffectGuard.get() == true) {
                return@hookReplace chain.proceed()
            }
            val rawEffect = chain.args.getOrNull(0) as? RenderEffect
            val view = chain.args.getOrNull(1) as? View
            if (view == null) {
                return@hookReplace chain.proceed()
            }
            maybeScheduleConfig(view.context)

            if (rawEffect == null) {
                trackedBoxes.remove(view)
                return@hookReplace chain.proceed()
            }
            if (!isTopBarView(view) || shouldBypassView(view)) {
                return@hookReplace chain.proceed()
            }

            ensureLayoutListener(view)
            val entry = TrackedBox(originalEffect = rawEffect)
            trackedBoxes[view] = entry

            if (!isGlassActive()) {
                entry.lastW = view.width
                entry.lastH = view.height
                entry.lastRevision = configRevision
                return@hookReplace chain.proceed()
            }

            val custom = buildGlassEffect(view)
            if (custom != null) {
                entry.lastW = view.width
                entry.lastH = view.height
                entry.lastRevision = configRevision
                chain.args[0] = custom
                if (loggedFirstApply.compareAndSet(false, true)) {
                    HookLog.ok("[$TAG] 已拦截负一屏顶栏毛玻璃 ${view.javaClass.simpleName} (${view.width}x${view.height}) -> 低模糊高折射液态玻璃")
                }
            }
            chain.proceed()
        }
    }

    private fun hookHeadLifecycle(env: HookEnv) {
        listOf(CLS_ASSISTANT_CONTAINER, CLS_ASSISTANT_HEAD).forEach { clsName ->
            env.findClass(clsName)?.findMethodsNamed("onAttachedToWindow")?.forEach { m ->
                env.hookAfter(m, "$TAG:${clsName.substringAfterLast('.')}.onAttachedToWindow") { chain ->
                    val view = chain.thisObject as? View ?: return@hookAfter
                    if (clsName == CLS_ASSISTANT_HEAD) headRef = WeakReference(view)
                    maybeScheduleConfig(view.context, force = true)
                    refreshTrackedViews()
                }
            }
        }
    }

    private fun isTopBarView(view: View): Boolean {
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < 14) {
            val n = cur.javaClass.name
            if ("AssistantScreenHeadView" in n || n.startsWith("com.oplus.card.ui.header")) return true
            cur = cur.parent as? View
            depth++
        }
        val entry = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id).lowercase() else ""
        }.getOrDefault("")
        if ("search" in entry || "head" in entry || "pill" in entry || "ring" in entry) {
            val dm = view.resources?.displayMetrics ?: return false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return loc[1] < dm.heightPixels * 0.25f
        }
        return false
    }

    private fun applyOrRestoreBox(view: View, record: TrackedBox) {
        val setter = mSetBgRenderEffect ?: return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return

        val targetEffect = if (isGlassActive()) buildGlassEffect(view) else record.originalEffect
        applyingEffectGuard.set(true)
        try {
            setter.invoke(null, targetEffect, view)
            record.lastW = w
            record.lastH = h
            record.lastRevision = configRevision
        } catch (_: Throwable) {
        } finally {
            applyingEffectGuard.set(false)
        }
    }

    private fun buildGlassEffect(view: View): RenderEffect? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val res = view.resources ?: return null
        val density = res.displayMetrics.density.coerceAtLeast(1f)
        val params = shaderParams

        val lowBlurPx = (blurRadiusDp * density).coerceIn(1f, 36f)
        val blurEffect = RenderEffect.createBlurEffect(lowBlurPx, lowBlurPx, Shader.TileMode.CLAMP)

        val minHalf = min(w, h) * 0.5f
        val detectedRadius = resolveCornerRadiusPx(view, w, h, density)
        val radiusPx = detectedRadius.coerceIn(2f, minHalf)
        val isPill = detectedRadius >= minHalf * 0.85f || (min(w, h) <= 72f * density && detectedRadius <= 0f)

        val bandPx = max(params.bandRaw * (density / 3f), 14f).coerceAtMost(max(minHalf * 0.92f, 16f))
        val amountPx = (params.refractionAmt * (density / 3f)).coerceIn(0f, 280f)
        val nightMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val tintColor = if (nightMode) {
            Color.argb(62, 34, 38, 46)
        } else {
            Color.argb(38, 255, 255, 255)
        }
        val tintR = Color.red(tintColor) / 255f
        val tintG = Color.green(tintColor) / 255f
        val tintB = Color.blue(tintColor) / 255f
        val tintA = Color.alpha(tintColor) / 255f

        val shader = runCatching {
            RuntimeShader(BOX_GLASS_AGSL).apply {
                setFloatUniform("uSize", w.toFloat(), h.toFloat())
                setFloatUniform("uRadius", radiusPx)
                setFloatUniform("uBand", bandPx)
                setFloatUniform("uAmount", amountPx)
                setFloatUniform("uRim", params.rimStrength)
                setFloatUniform("uTint", tintR, tintG, tintB, tintA)
                setFloatUniform("uSat", 1.38f)
                setFloatUniform("uPill", if (isPill) 1f else 0f)
            }
        }.getOrNull() ?: return blurEffect

        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blurEffect
        )
    }

    private fun resolveCornerRadiusPx(view: View, w: Int, h: Int, density: Float): Float {
        val minHalf = min(w, h) * 0.5f
        if (kotlin.math.abs(w - h) <= 2) return minHalf

        val provider: ViewOutlineProvider? = view.outlineProvider
        if (provider != null && provider !== ViewOutlineProvider.BACKGROUND) {
            val outline = Outline()
            val outlinedRadius = runCatching {
                provider.getOutline(view, outline)
                outline.radius
            }.getOrDefault(-1f)
            if (outlinedRadius > 1f) {
                return (outlinedRadius * shaderParams.cornerScale).coerceIn(2f, minHalf)
            }
        }

        val entryName = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id).lowercase() else ""
        }.getOrDefault("")
        if ("search" in entryName || "pill" in entryName || "ring" in entryName || "button" in entryName) {
            return minHalf
        }

        return (cornerRadiusDp * density).coerceIn(2f, minHalf)
    }

    private fun ensureLayoutListener(view: View) {
        if (layoutHooked.put(view, true) != true) {
            view.addOnLayoutChangeListener(sharedLayoutListener)
        }
    }

    private fun shouldBypassView(view: View): Boolean {
        val clsName = view.javaClass.name
        if (clsName.startsWith("em0.") || "GradientBlur" in clsName || "AppBar" in clsName) {
            return true
        }
        val entryName = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        if ("GradientBlur" in entryName || "gradient_blur" in entryName) {
            return true
        }
        return isFullscreenView(view)
    }

    private fun isFullscreenView(view: View): Boolean {
        val dm = view.resources?.displayMetrics ?: return false
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return false
        return w >= dm.widthPixels * 0.88f && h >= dm.heightPixels * 0.28f
    }

    private fun isGlassActive(): Boolean = glassAssistantEnabled && shaderParams.enabled

    private fun maybeScheduleConfig(context: Context?, force: Boolean = false) {
        if (context != null) {
            lastKnownContext = WeakReference(context.applicationContext ?: context)
        }
        val ctx = context ?: lastKnownContext?.get()
        if (force) {
            configWatcher?.requestCheck(ctx, force = true)
        } else {
            configWatcher?.scheduleCheck(ctx)
        }
    }

    private fun applyReloadedConfig(context: Context?, fresh: HookConfig?, disabled: Boolean) {
        if (context != null) {
            lastKnownContext = WeakReference(context.applicationContext ?: context)
        }
        if (disabled || fresh == null) {
            if (glassAssistantEnabled || shaderParams.enabled) {
                glassAssistantEnabled = false
                shaderParams = NativeGlassShader.GlassShaderParams.fromAssistant(HookConfig(null), surfaceEnabled = false)
                configRevision++
                refreshTrackedViews()
                HookLog.warn("[$TAG] 已禁用/还原，恢复负一屏顶栏原生毛玻璃")
            }
            return
        }

        val nextEnabled = fresh.glassAssistant
        val nextParams = NativeGlassShader.GlassShaderParams.fromAssistant(fresh)
        val nextBlur = fresh.assistantBlurRadius
        val nextCorner = fresh.assistantCornerRadius
        if (nextEnabled != glassAssistantEnabled ||
            nextParams != shaderParams ||
            nextBlur != blurRadiusDp ||
            nextCorner != cornerRadiusDp
        ) {
            glassAssistantEnabled = nextEnabled
            shaderParams = nextParams
            blurRadiusDp = nextBlur
            cornerRadiusDp = nextCorner
            configRevision++
            refreshTrackedViews()
            HookLog.info(
                "[$TAG] 热重载负一屏顶栏参数：enabled=$nextEnabled, blur=${blurRadiusDp}dp, amt=${nextParams.refractionAmt}, rim=${nextParams.rimStrength}"
            )
        }
    }

    private fun healTopBar() {
        if (!isGlassActive()) return
        val head = headRef?.get() ?: return
        if (!head.isAttachedToWindow) return
        collectBlurViews(head, 0)
        val boxes = synchronized(trackedBoxes) { trackedBoxes.entries.map { it.key to it.value } }
        boxes.forEach { (view, record) ->
            if (view.isAttachedToWindow && view.visibility == View.VISIBLE && view.width > 0 && view.height > 0) {
                applyOrRestoreBox(view, record)
            }
        }
    }

    private fun collectBlurViews(view: View, depth: Int) {
        if (depth > 6) return
        val entry = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id).lowercase() else ""
        }.getOrDefault("")
        if ("blur" in entry && !shouldBypassView(view)) {
            ensureLayoutListener(view)
            trackedBoxes.getOrPut(view) { TrackedBox(originalEffect = null) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectBlurViews(view.getChildAt(i), depth + 1)
            }
        }
    }

    private fun refreshTrackedViews() {
        val boxes = synchronized(trackedBoxes) { trackedBoxes.entries.map { it.key to it.value } }
        boxes.forEach { (view, record) ->
            if (view.isAttachedToWindow) {
                view.post { applyOrRestoreBox(view, record) }
            }
        }
    }
}