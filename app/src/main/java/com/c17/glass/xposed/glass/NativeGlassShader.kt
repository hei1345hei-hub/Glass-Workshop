package com.c17.glass.xposed.glass

import com.c17.glass.xposed.statusbar.HookConfig
import com.c17.glass.xposed.statusbar.HookLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * ColorOS 17 SystemUI AGSL (`com.oplus.posteffect`) 原生液态玻璃着色器注入与 Uniform 参数桥接。
 *
 *  1. 拦截 `BlurDrawableShaderBaseStringKt.buildShaderString` 与 `RuntimeShader` 构造，注入曲面内向折射与边缘彩色高光；
 *  2. 保留原生 `inputColor.rgb` 色相与暗色卡片，将不透明激活态混色上限设为 `0.7882353`（透出 ~21% 边缘玻璃光感）；
 *  3. 当系统源码含 `smoothSDF_D` 时，对大卡片启用 ColorOS G2 连续曲率边缘；
 *  4. 所有采样偏移统一在 View 空间计算后再经 `c17BackdropMap` 转换至 `0.25x` 背景纹理坐标。
 */
internal object NativeGlassShader {

    data class GlassShaderParams(
        val enabled: Boolean,
        val bandRaw: Float,
        val refractionAmt: Float,
        val reach: Float,
        val rimStrength: Float,
        val whiteMix: Float,
        val satOffset: Float,
        val paintSaturation: Float,
        val blurScale: Float,
        val cornerScale: Float
    ) {
        companion object {
            /** 按液态玻璃滑块计算采样、边缘高光、Paint 饱和度、模糊与圆角缩放参数（50% ≈ 旧版 100%）。 */
            fun fromValues(
                surfaceEnabled: Boolean,
                refraction: Float,
                blurRadius: Float,
                highlight: Boolean,
                cornerRadius: Float
            ): GlassShaderParams {
                if (!surfaceEnabled) {
                    return GlassShaderParams(
                        enabled = false,
                        bandRaw = 0f,
                        refractionAmt = 0f,
                        reach = 0.5f,
                        rimStrength = 0f,
                        whiteMix = 0f,
                        satOffset = 0f,
                        paintSaturation = 1.0f,
                        blurScale = 1f,
                        cornerScale = 1f
                    )
                }

                val rimBase = 0.72f
                val blurBase = 0.65f
                val whiteMix = 0.08f
                val satOff = 0.25f
                val paintSat = 1.50f

                val clampedRef = refraction.coerceIn(0f, 1f)
                // 50% 折射强度映射为原 100% 档（ratio≈1.667, amt≈113），100% 继续加强至 ~226
                val ratio = clampedRef * 3.3333f
                val refractionAmt = if (clampedRef < 0.005f) {
                    0f
                } else {
                    (68f * ratio).coerceIn(0f, 240f)
                }
                val bandRaw = if (refractionAmt <= 0.5f) {
                    0f
                } else {
                    (51f * (0.65f + 0.35f * minOf(ratio, 2.2f))).coerceIn(16f, 100f)
                }
                val rimStrength = if (highlight) rimBase else 0f
                val blurRatio = blurRadius.coerceIn(2f, 64f) / 24f
                val blurScale = (blurBase * blurRatio).coerceIn(0.08f, 2.20f)
                val cornerScale = (cornerRadius.coerceIn(8f, 48f) / 26f).coerceIn(0.30f, 1.85f)

                return GlassShaderParams(
                    enabled = true,
                    bandRaw = bandRaw,
                    refractionAmt = refractionAmt,
                    reach = 0.5f,
                    rimStrength = rimStrength,
                    whiteMix = whiteMix,
                    satOffset = satOff,
                    paintSaturation = paintSat,
                    blurScale = blurScale,
                    cornerScale = cornerScale
                )
            }

            fun from(config: HookConfig, surfaceEnabled: Boolean = config.hasSystemUiGlass): GlassShaderParams =
                fromValues(
                    surfaceEnabled = surfaceEnabled,
                    refraction = config.refraction,
                    blurRadius = config.blurRadius,
                    highlight = config.highlight,
                    cornerRadius = config.cornerRadius
                )

            fun fromLauncher(config: HookConfig, surfaceEnabled: Boolean = config.hasLauncherGlass): GlassShaderParams =
                fromValues(
                    surfaceEnabled = surfaceEnabled,
                    refraction = config.launcherRefraction,
                    blurRadius = config.launcherBlurRadius,
                    highlight = config.launcherHighlight,
                    cornerRadius = config.launcherCornerRadius
                )

            fun fromVolume(config: HookConfig, surfaceEnabled: Boolean = config.glassVolume): GlassShaderParams =
                fromValues(
                    surfaceEnabled = surfaceEnabled,
                    refraction = config.volumeRefraction,
                    blurRadius = config.volumeBlurRadius,
                    highlight = config.volumeHighlight,
                    cornerRadius = config.volumeCornerRadius
                )
        }
    }

    private const val ANCHOR_UNIFORM_BDF = "uniform shader uniBDFBitmap;"
    private const val ANCHOR_MAIN = "half4 main(float2 position)"
    private const val TARGET_SAMPLE = "half4 outputCol = half4(uniBDFBitmap.eval(mapCoords).rgb, 1.0);"
    private const val PATCHED_SAMPLE = "half4 outputCol = c17Sample(mapCoords, position);"
    private const val TARGET_MIX = "outputCol = mix(outputCol, half4(inputColor.rgb, 1.0), inputColor.a);"
    private const val PATCHED_MIX =
        "outputCol = mix(outputCol, half4(inputColor.rgb, 1.0), min(inputColor.a, 0.7882353));"

    private const val UNIFORM_DECLS =
        "uniform float u_c17[6];\n" +
            "uniform float u_c17_reach;\n" +
            "uniform float u_c17_rim;\n" +
            "uniform float2 u_c17_tone;\n" +
            "uniform float u_c17_source_scale;\n" +
            "uniform float u_c17_force_g2;\n"

    private val AGSL_HELPERS = """
float c17LensAtten(float d,float b){float t=clamp((b-d)/max(b,1e-3),0.0,1.0);return 1.0-sqrt(max(0.0,(1.0-t)*(1.0+t)));}
float2 c17LensGrad(float2 p,float2 b,float r){float2 q=abs(p)-b+float2(r);float2 g;if(q.x>=0.0||q.y>=0.0){g=max(q,float2(0.0));float l=length(g);g=l>1e-5?g/l:float2(0.0);}else{float x=step(q.y,q.x);g=float2(x,1.0-x);}return sign(p)*g;}
float c17CircleMap(float x) {
    float y = max(0.0, (1.0 - x) * (1.0 + x));
    return 1.0 - sqrt(y);
}
float2 c17SafeNorm(float2 v) {
    float l = length(v);
    if (l < 1e-5) return float2(0.0);
    return v / l;
}
float c17RBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}
float2 c17GradRBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    float2 m = max(q, float2(0.0));
    if (m.x > 1e-5 || m.y > 1e-5) {
        return sign(p) * c17SafeNorm(m);
    }
    if (q.x > q.y) return sign(p) * float2(1.0, 0.0);
    return sign(p) * float2(0.0, 1.0);
}
float c17LargeCardFlag(float2 b) {
    float mn = min(b.x, b.y);
    float mx = max(b.x, b.y);
    float aspect = mx / max(mn, 1.0);
    float sizeOk = step(170.0, mn) * (1.0 - step(520.0, mx));
    float shapeOk = 1.0 - step(1.55, aspect);
    return sizeOk * shapeOk;
}
float2 c17BackdropMap(float2 position) {
    float2 scale = max(float2(0.0000001), uMatrixParams.xy);
    return (uMatrix * float3((position - uMatrixParams.zw) / scale / scale, 0.0)).xy;
}
half3 c17BodyTone(half3 c) {
  float3 f=clamp(float3(c.r,c.g,c.b),0.0,1.0);
  float lum=dot(f,float3(0.2126,0.7152,0.0722));
  float hi=max(f.r,max(f.g,f.b)); float lo=min(f.r,min(f.g,f.b));
  float sat=clamp((hi-lo)/max(hi,0.06),0.0,1.0);
  float keep=clamp(0.96+0.20*sat,0.96,1.12);
  f=clamp(mix(float3(lum,lum,lum),f,keep),0.0,1.0);
  return half3(f.r,f.g,f.b);
}
half3 c17EdgeTone(half3 c) {
  half3 b=c17BodyTone(c); float3 f=float3(b.r,b.g,b.b);
  float hi=max(f.r,max(f.g,f.b)); float lo=min(f.r,min(f.g,f.b));
  float sat=clamp((hi-lo)/max(hi,0.06),0.0,1.0);
  float gain=1.035+0.16*sat;
  float3 lifted=clamp(f*gain+float3(0.006,0.006,0.006),0.0,1.0);
  return half3(lifted.r,lifted.g,lifted.b);
}
half3 c17BodyToneSample(float2 p,float2 outward,float stepPx) {
  float s=clamp(stepPx,10.0,56.0);
  return c17BodyTone(uniBDFBitmap.eval(c17BackdropMap(p-outward*s)).rgb);
}
half3 c17ApplyRimLight(half3 col,float2 p,float2 outward,float coreBand,float glassMn,float t) {
  float strength=clamp(u_c17_rim,0.0,1.0);
  if(strength<=0.001) return col;
  float rimGate=smoothstep(0.68,0.88,t);
  if(rimGate<=0.01) return col;
  float soft=smoothstep(0.68,0.96,t);
  float line=smoothstep(0.84,1.0,t);
  float toneStep=clamp(max(coreBand*2.1,glassMn*0.24),12.0,96.0);
  half3 tone=c17BodyToneSample(p,outward,toneStep);
  half3 edgeTone=c17EdgeTone(tone);
  float mask=clamp(strength*rimGate*(0.24*soft+0.58*line),0.0,0.78);
  half3 mapped=clamp(edgeTone*1.16,half3(0.0,0.0,0.0),half3(1.0,1.0,1.0));
  col=mix(col,mix(col,mapped,0.76),mask);
  col=clamp(col+edgeTone*(0.028*strength*rimGate),half3(0.0,0.0,0.0),half3(1.0,1.0,1.0));
  return col;
}
half4 c17GlassTone(half4 color) {
    if (abs(u_c17_tone.x) < 0.00001 && abs(u_c17_tone.y) < 0.00001) return color;
    float alpha = float(color.a);
    if (alpha <= 0.00001) return color;
    float3 rgb = float3(color.rgb) / alpha;
    float luminance = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = mix(float3(luminance), rgb, clamp(1.0 + u_c17_tone.y, 0.0, 2.0));
    rgb = mix(rgb, float3(1.0), clamp(u_c17_tone.x, 0.0, 1.0));
    return half4(half3(clamp(rgb, 0.0, 1.0) * alpha), color.a);
}
half4 c17GlassSample(float2 mapCoords, float2 position);
half4 c17Sample(float2 mapCoords, float2 position) {
    return c17GlassTone(c17GlassSample(mapCoords, position));
}
half4 c17GlassSample(float2 mapCoords, float2 position) {
    half4 plain = half4(uniBDFBitmap.eval(mapCoords).rgb, 1.0);
    if (u_c17[5] > 0.5) return plain;
    float h = u_c17[0] * 0.85 * 0.75;
    float amt = u_c17[1];
    if (h < 0.5 || amt < 0.5) return plain;
    float2 c = float2(position.x, u_commonArray[1] * 2.0 - position.y);
    CommonDataRect rd = getCommonDataRect(c);
    float mn = min(rd.halfSize.x, rd.halfSize.y);
    if (mn < 1.0) return plain;
    float rr = min(rd.coner, mn);
    if (rr < 0.0) rr = 0.0;
    float cardGrow = 0.0;
    float2 glassHalf = rd.halfSize + float2(cardGrow);
    float glassMn = min(glassHalf.x, glassHalf.y);
    float glassRadius = min(glassMn, max(0.0, rr + cardGrow * 0.35));
    float sdf = c17RBox(rd.pos, glassHalf, glassRadius);
    if (sdf >= 0.0) return plain;
    if (-sdf >= h) {
        return half4(plain.rgb, 1.0);
    }
    float depth = max(-sdf, 0.0);
    float bufferBand = clamp(h * 0.36, 2.0, 18.0);
    float bufferStart = max(h - bufferBand, 0.0);
    float bufferProgress = clamp((h - depth) / max(bufferBand, 1.0), 0.0, 1.0);
    float bufferAnchorPos = clamp(1.0 - bufferStart / max(h, 1.0), 0.0, 1.0);
    float bufferAnchorT = c17CircleMap(bufferAnchorPos);
    float linearBufferT = bufferAnchorT * bufferProgress;
    float t = c17CircleMap(1.0 + sdf / h);
    float blendHalf = clamp(bufferBand * 0.24, 2.5, 9.0);
    float blendLo = max(0.0, bufferStart - blendHalf);
    float blendHi = min(h, bufferStart + blendHalf);
    float blendRange = max(blendHi - blendLo, 1.0);
    float blendX = clamp((depth - blendLo) / blendRange, 0.0, 1.0);
    float blendW = blendX * blendX * blendX * (blendX * (blendX * 6.0 - 15.0) + 10.0);
    float effectT = mix(t, linearBufferT, blendW);
    float reach = clamp(u_c17_reach, 0.25, 1.0);
    float d = c17LensAtten(depth,h) * amt;
    float gr = min(glassRadius, glassMn);
    float2 g = c17GradRBox(rd.pos, glassHalf, gr);
    float dep = u_c17[3];
    if (dep > 0.001) g = c17SafeNorm(g + dep * c17SafeNorm(rd.pos));
    g.y = -g.y;
    float2 sampleNormal=c17LensGrad(rd.pos,glassHalf,min(glassRadius*1.5,glassMn));sampleNormal=c17SafeNorm(sampleNormal+u_c17[3]*c17SafeNorm(rd.pos));sampleNormal.y=-sampleNormal.y;
    float safeD=min(d,max(glassMn*0.85,18.0));
    float2 rc=c17BackdropMap(position-safeD*sampleNormal);
    half3 col=uniBDFBitmap.eval(rc).rgb;
    col=c17ApplyRimLight(col,position,g,h,glassMn,effectT);
    return half4(col, 1.0);
}
""".trimIndent() + "\n"

    private class PushedUniformState(
        var bandRaw: Float = Float.NaN,
        var refractionAmt: Float = Float.NaN,
        var reach: Float = Float.NaN,
        var rim: Float = Float.NaN,
        var whiteMix: Float = Float.NaN,
        var satOffset: Float = Float.NaN,
        var sourceScale: Float = Float.NaN,
        var bypass: Boolean = false,
        var forceG2: Boolean = false,
        val c17Array: FloatArray = FloatArray(6)
    ) {
        fun matches(
            bandRaw: Float,
            refractionAmt: Float,
            reach: Float,
            rim: Float,
            whiteMix: Float,
            satOffset: Float,
            sourceScale: Float,
            bypass: Boolean,
            forceG2: Boolean
        ): Boolean =
            this.bandRaw == bandRaw &&
                this.refractionAmt == refractionAmt &&
                this.reach == reach &&
                this.rim == rim &&
                this.whiteMix == whiteMix &&
                this.satOffset == satOffset &&
                this.sourceScale == sourceScale &&
                this.bypass == bypass &&
                this.forceG2 == forceG2
    }

    private fun createBoundedCache(): MutableMap<String, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 64
        })

    private val injectCache = createBoundedCache()
    private val g2PatchCache = createBoundedCache()

    private val patchedShaders = WeakIdentitySet()
    private val pushedStates = WeakIdentityMap<PushedUniformState>()
    private val loggedInject = AtomicBoolean(false)
    private val loggedUniform = AtomicBoolean(false)

    private const val G2_MARKER = "// C17_G2_CURVE"
    private const val G2_GRAD_FN =
        "$G2_MARKER\n" +
            "float2 c17G2Grad(float2 p,float2 b,float r,float weight){\n" +
            " float2 q=abs(p)-b+float2(r);float2 g;\n" +
            " if(weight>=2.0){if(q.x>0.0&&q.y>0.0){g=pow(max(q,float2(1e-5)),float2(max(weight-1.0,1.0)));}else g=q.x>q.y?float2(1,0):float2(0,1);return sign(p)*c17SafeNorm(g);}\n" +
            " float e=.05;return c17SafeNorm(float2(smoothSDF_D(p+float2(e,0),b,r,weight)-smoothSDF_D(p-float2(e,0),b,r,weight),smoothSDF_D(p+float2(0,e),b,r,weight)-smoothSDF_D(p-float2(0,e),b,r,weight)));}\n"

    @Volatile
    private var mSetFloatUniformArray: Method? = null

    @Volatile
    private var mSetFloatUniform1: Method? = null

    @Volatile
    private var mSetFloatUniform2: Method? = null

    fun isTargetSource(source: String): Boolean =
        source.contains(ANCHOR_UNIFORM_BDF) &&
            source.contains(ANCHOR_MAIN) &&
            source.contains(TARGET_SAMPLE)

    fun isPatchedSource(source: String): Boolean =
        source.contains("uniform float u_c17[6]") && source.contains("c17Sample")

    fun markPatchedShader(shader: Any?) {
        if (shader != null) patchedShaders.add(shader)
    }

    fun isPatchedShader(shader: Any?): Boolean =
        shader != null && patchedShaders.contains(shader)

    /** 若 RuntimeShader 从已知已修补源码创建，记录该实例以便 setBaseUniform 快速放行。 */
    fun trackIfPatchedArgs(shader: Any?, args: List<Any?>) {
        if (shader == null) return
        for (arg in args) {
            if (arg is String && isPatchedSource(arg)) {
                patchedShaders.add(shader)
                return
            }
        }
    }

    fun inject(source: String): String {
        injectCache[source]?.let { return it }
        if (!isTargetSource(source)) return source

        var out = source.replace(TARGET_SAMPLE, PATCHED_SAMPLE)
        if (out == source) return source

        val bdfIdx = out.indexOf(ANCHOR_UNIFORM_BDF)
        if (bdfIdx < 0) return source
        out = out.substring(0, bdfIdx) + UNIFORM_DECLS + out.substring(bdfIdx)

        val mainIdx = out.indexOf(ANCHOR_MAIN)
        if (mainIdx < 0) return source

        // 若前置 helper 语句末行以 ')' 结尾而缺少分号，补齐分号避免 AGSL 编译错误。
        var prefix = out.substring(0, mainIdx)
        val trimmed = prefix.trimEnd()
        if (trimmed.isNotEmpty() && trimmed.last() == ')') {
            prefix = trimmed + ";"
        }
        out = prefix + "\n" + AGSL_HELPERS + out.substring(mainIdx)

        if (out.contains(TARGET_MIX)) {
            out = out.replace(TARGET_MIX, PATCHED_MIX)
        }
        out = patchLargeCardG2(out)

        injectCache[source] = out
        if (loggedInject.compareAndSet(false, true)) {
            HookLog.ok("[fluid-glass] AGSL 着色器首帧注入成功 (${source.length} -> ${out.length} chars, g2=${out.contains(G2_MARKER)})")
        }
        return out
    }

    /** 若宿主着色器定义了 smoothSDF_D，对大方形卡片启用 ColorOS G2 连续曲率轮廓。 */
    fun patchLargeCardG2(source: String): String {
        g2PatchCache[source]?.let { return it }
        if (source.contains(G2_MARKER) ||
            !source.contains("float smoothSDF_D(") ||
            !source.contains(".weight") ||
            !source.contains("half4 c17Sample(") ||
            !source.contains("c17LargeCardFlag")
        ) {
            return source
        }
        val patched = source
            .replace("half4 c17Sample(float2 mapCoords, float2 position) {", "${G2_GRAD_FN}half4 c17Sample(float2 mapCoords, float2 position) {")
            .replace("    float cardGrow = 0.0;", "    float c17UseG2=max(c17LargeCardFlag(rd.halfSize),u_c17_force_g2);\n    if(c17UseG2>.5)rr=min(rd.coner,mn);\n    float cardGrow = 0.0;")
            .replace("float sdf = c17RBox(rd.pos, glassHalf, glassRadius);", "float sdf = c17UseG2>.5?smoothSDF_D(rd.pos,rd.halfSize,rd.coner,rd.weight):c17RBox(rd.pos, glassHalf, glassRadius);")
            .replace("float2 g = c17GradRBox(rd.pos, glassHalf, gr);", "float2 g = c17UseG2>.5?c17G2Grad(rd.pos,rd.halfSize,rd.coner,rd.weight):c17GradRBox(rd.pos, glassHalf, gr);")
            .replace("float2 sampleNormal=c17LensGrad(rd.pos,glassHalf,min(glassRadius*1.5,glassMn));", "float2 sampleNormal=c17UseG2>.5?c17G2Grad(rd.pos,rd.halfSize,rd.coner,rd.weight):c17LensGrad(rd.pos,glassHalf,min(glassRadius*1.5,glassMn));")
        g2PatchCache[source] = patched
        return patched
    }

    fun pushUniforms(
        shader: Any,
        params: GlassShaderParams,
        bypass: Boolean,
        sourceScale: Float,
        usePaintSaturation: Boolean = false,
        forceG2: Boolean = false,
        refractionScale: Float = 1f
    ) {
        if (!patchedShaders.contains(shader)) return
        resolveUniformMethods(shader.javaClass)

        val effectiveBypass = bypass || !params.enabled
        val rim = if (effectiveBypass) 0f else params.rimStrength
        val safeSourceScale = sourceScale.coerceIn(0.25f, 2.0f)
        val whiteMix = if (effectiveBypass) 0f else params.whiteMix
        val satOffset = if (effectiveBypass || usePaintSaturation) 0f else params.satOffset
        // refractionAmt 是像素级采样偏移（fromValues() 已限制在 0..240），这里只做比例缩放，
        // 不能按 0..1 的比例量纲钳制，否则会把折射直接归零。
        val refractionAmt = (params.refractionAmt * refractionScale).coerceIn(0f, 260f)

        val prev = pushedStates.get(shader)
        if (prev != null && prev.matches(
                params.bandRaw,
                refractionAmt,
                params.reach,
                rim,
                whiteMix,
                satOffset,
                safeSourceScale,
                effectiveBypass,
                forceG2
            )
        ) {
            return
        }

        val state = prev ?: PushedUniformState().also { pushedStates.put(shader, it) }
        val c17Array = state.c17Array
        c17Array[0] = params.bandRaw
        c17Array[1] = refractionAmt
        c17Array[2] = 0f
        c17Array[3] = 0f
        c17Array[4] = 1f
        c17Array[5] = if (effectiveBypass) 1f else 0f

        runCatching {
            mSetFloatUniformArray?.invoke(shader, "u_c17", c17Array)
            mSetFloatUniform1?.invoke(shader, "u_c17_reach", params.reach)
            mSetFloatUniform1?.invoke(shader, "u_c17_rim", rim)
            mSetFloatUniform2?.invoke(shader, "u_c17_tone", whiteMix, satOffset)
            mSetFloatUniform1?.invoke(shader, "u_c17_source_scale", safeSourceScale)
            runCatching {
                mSetFloatUniform1?.invoke(shader, "u_c17_force_g2", if (forceG2) 1f else 0f)
            }
            state.bandRaw = params.bandRaw
            state.refractionAmt = refractionAmt
            state.reach = params.reach
            state.rim = rim
            state.whiteMix = whiteMix
            state.satOffset = satOffset
            state.sourceScale = safeSourceScale
            state.bypass = effectiveBypass
            state.forceG2 = forceG2
            if (loggedUniform.compareAndSet(false, true)) {
                HookLog.ok(
                    "[fluid-glass] 首次下发 Uniform 成功 (h=${params.bandRaw}, amt=$refractionAmt, " +
                        "rim=$rim, white=$whiteMix, satOff=$satOffset, blurScale=${params.blurScale}, paintSat=$usePaintSaturation)"
                )
            }
        }.onFailure {
            HookLog.warn("[fluid-glass] 写入 AGSL Uniform 失败：${it.message}")
        }
    }

    fun pushSourceScale(shader: Any?, sourceScale: Float) {
        if (shader == null || !patchedShaders.contains(shader)) return
        resolveUniformMethods(shader.javaClass)
        val safeScale = sourceScale.coerceIn(0.25f, 2.0f)
        val prev = pushedStates.get(shader)
        if (prev != null && approxEqual(prev.sourceScale, safeScale)) return
        runCatching {
            mSetFloatUniform1?.invoke(shader, "u_c17_source_scale", safeScale)
            if (prev != null) {
                prev.sourceScale = safeScale
            }
        }
    }

    private fun resolveUniformMethods(cls: Class<*>) {
        if (mSetFloatUniformArray != null && mSetFloatUniform1 != null && mSetFloatUniform2 != null) return
        synchronized(this) {
            if (mSetFloatUniformArray == null) {
                mSetFloatUniformArray = cls.getMethod("setFloatUniform", String::class.java, FloatArray::class.java)
            }
            if (mSetFloatUniform1 == null) {
                mSetFloatUniform1 = cls.getMethod("setFloatUniform", String::class.java, Float::class.javaPrimitiveType)
            }
            if (mSetFloatUniform2 == null) {
                mSetFloatUniform2 = cls.getMethod(
                    "setFloatUniform",
                    String::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
            }
        }
    }

    fun approxEqual(a: Float, b: Float): Boolean = abs(a - b) < 1e-4f

    private class WeakIdentitySet {
        private val buckets = HashMap<Int, ArrayList<WeakReference<Any>>>(64)
        private var ops = 0

        @Synchronized
        fun add(obj: Any) {
            if (containsLocked(obj)) return
            val id = System.identityHashCode(obj)
            buckets.getOrPut(id) { ArrayList(1) }.add(WeakReference(obj))
            if (++ops and 127 == 0) purgeLocked()
        }

        @Synchronized
        fun contains(obj: Any): Boolean = containsLocked(obj)

        private fun containsLocked(obj: Any): Boolean {
            val list = buckets[System.identityHashCode(obj)] ?: return false
            for (i in list.indices) {
                if (list[i].get() === obj) return true
            }
            return false
        }

        private fun purgeLocked() {
            val it = buckets.entries.iterator()
            while (it.hasNext()) {
                val list = it.next().value
                list.removeAll { it.get() == null }
                if (list.isEmpty()) it.remove()
            }
        }
    }

    private class WeakIdentityMap<V> {
        private class Entry<V>(val key: WeakReference<Any>, var value: V)
        private val buckets = HashMap<Int, ArrayList<Entry<V>>>(64)
        private var ops = 0

        @Synchronized
        fun get(obj: Any): V? {
            val list = buckets[System.identityHashCode(obj)] ?: return null
            for (i in list.indices) {
                if (list[i].key.get() === obj) return list[i].value
            }
            return null
        }

        @Synchronized
        fun put(obj: Any, value: V) {
            val id = System.identityHashCode(obj)
            val list = buckets.getOrPut(id) { ArrayList(1) }
            for (i in list.indices) {
                if (list[i].key.get() === obj) {
                    list[i].value = value
                    return
                }
            }
            list.add(Entry(WeakReference(obj), value))
            if (++ops and 127 == 0) {
                val it = buckets.entries.iterator()
                while (it.hasNext()) {
                    val bucket = it.next().value
                    bucket.removeAll { it.key.get() == null }
                    if (bucket.isEmpty()) it.remove()
                }
            }
        }
    }
}
