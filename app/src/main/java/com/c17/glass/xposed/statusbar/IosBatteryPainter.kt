package com.c17.glass.xposed.statusbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface

/**
 * 仿 iOS 电池（对齐 Iconify 的 Landscape iOS 16 样式）。
 *
 * 轮廓来自 Iconify 的 config_landscapeBatteryPerimeteriOS16：
 *   24×12 的圆角胶囊（0.08,0.36 → 21.76,11.64，圆角 3.04）+ 右侧极头。
 * 画法也照抄：半透明底槽 → 按电量裁切填充 → 电量数字从图形里挖空（DST_OUT），
 * 充电时填色变绿、数字与闪电用实色盖上去；低于 20% 变红。
 */
object IosBatteryPainter {

    private const val W = 24f
    private const val H = 12f

    private val bodyPath = Path().apply {
        addRoundRect(0.08f, 0.36f, 21.76f, 11.64f, 3.04f, 3.04f, Path.Direction.CW)
    }

    /** 正极极头（Iconify 路径 M22.61,7.72 C24.35,7.19 24.35,4.81 22.61,4.28 L22.61,7.72 z）。 */
    private val nubPath = Path().apply {
        moveTo(22.61f, 7.72f)
        cubicTo(24.35f, 7.19f, 24.35f, 4.81f, 22.61f, 4.28f)
        lineTo(22.61f, 7.72f)
        close()
    }

    /** 充电闪电（Iconify config_landscapeBatteryBoltiOS16）。 */
    private val boltPath = Path().apply {
        moveTo(17.96f, 5.25f)
        lineTo(19.91f, 5.25f)
        quadTo(20.43f, 5.31f, 20.10f, 5.77f)
        lineTo(16.69f, 9.77f)
        cubicTo(16.39f, 10.06f, 15.81f, 9.87f, 16.10f, 9.18f)
        lineTo(17.04f, 6.75f)
        lineTo(15.09f, 6.75f)
        quadTo(14.57f, 6.69f, 14.90f, 6.23f)
        lineTo(18.31f, 2.23f)
        cubicTo(18.61f, 1.94f, 19.19f, 2.13f, 18.90f, 2.82f)
        lineTo(17.96f, 5.25f)
        close()
    }

    private val shape = Path()
    private val bolt = Path()
    private val matrix = Matrix()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        color = Color.BLACK
    }

    private var fontLoaded = false

    private const val COLOR_CHARGING = 0xFF34C759.toInt()
    private const val COLOR_LOW = 0xFFFF3B30.toInt()
    private const val LOW_LEVEL = 20

    fun draw(
        canvas: Canvas,
        bounds: Rect,
        level: Int,
        charging: Boolean,
        showPercent: Boolean,
        foreground: Int,
        background: Int
    ) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        loadFont(HookRuntime.moduleContext)

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        matrix.setScale(w / W, h / H)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        shape.reset()
        shape.addPath(bodyPath)
        shape.addPath(nubPath)
        shape.transform(matrix)
        bolt.reset()
        bolt.set(boltPath)
        if (!showPercent && charging && level < 100) {
            // 没有百分比时，闪电居中显示在电池体正中
            val centerShift = -6.6f * (w / W)
            matrix.postTranslate(centerShift, 0f)
            bolt.transform(matrix)
            matrix.postTranslate(-centerShift, 0f)
        } else {
            bolt.transform(matrix)
        }

        val lvl = level.coerceIn(0, 100)
        val low = lvl <= LOW_LEVEL
        val fillColor = when {
            charging -> COLOR_CHARGING
            low -> COLOR_LOW
            else -> foreground
        }
        // 底槽：优先用系统给的背景色，没有就按前景色 32% 透明 —— iOS 的未填充部分就是这个观感。
        val trackColor = if (Color.alpha(background) >= 0x30) background else withAlpha(foreground, 0.32f)
        val ink = if (Color.luminance(foreground) > 0.6f) Color.WHITE else Color.BLACK

        canvas.saveLayer(null, null)
        trackPaint.color = trackColor
        canvas.drawPath(shape, trackPaint)

        val fraction = lvl / 100f
        if (fraction > 0f) {
            canvas.save()
            canvas.clipRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + w * fraction, bounds.bottom.toFloat())
            fillPaint.color = fillColor
            canvas.drawPath(shape, fillPaint)
            canvas.restore()
        }

        val textSize = when {
            lvl >= 100 -> w * 0.34f
            lvl >= 10 -> w * 0.40f
            else -> w * 0.42f
        }
        textPaint.textSize = textSize
        holePaint.textSize = textSize
        holePaint.typeface = textPaint.typeface

        // 先设字号再取 ascent，否则拿到的是默认 0 号字的度量，数字会跑偏。
        val ascent = textPaint.fontMetrics.ascent
        val baseline = bounds.top + h * 0.8f

        var textX = bounds.left + (w + ascent) * 0.75f
        if (showPercent && charging && lvl < 100) {
            textX = bounds.left + (w + ascent) * 0.56f
        }

        if (showPercent) {
            // 充电变绿或低电变红时用实色，正常电量从电池体镂空（DST_OUT）
            val paint = if (charging || low) textPaint.apply { color = ink } else holePaint
            canvas.drawText(lvl.toString(), textX, baseline, paint)
        }

        if (charging && lvl < 100) {
            val boltPaint = fillPaint
            boltPaint.color = ink
            canvas.drawPath(bolt, boltPaint)
        }

        canvas.restore()
    }

    fun loadFont(context: Context? = null) {
        if (fontLoaded) return
        val ctx = context ?: HookRuntime.moduleContext ?: HookRuntime.context() ?: return
        val tf = runCatching {
            val moduleCtx = if (ctx.packageName == "com.c17.glass") ctx
            else ctx.createPackageContext("com.c17.glass", Context.CONTEXT_IGNORE_SECURITY)
            Typeface.createFromAsset(moduleCtx.assets, "fonts/SFUITextCondensed-Bold.otf")
        }.getOrNull()
        val typeface = tf ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
        fontLoaded = tf != null
        textPaint.typeface = typeface
        holePaint.typeface = typeface
        if (tf != null) {
            HookLog.ok("[battery-style] 成功加载 SFUITextCondensed-Bold 字体")
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
