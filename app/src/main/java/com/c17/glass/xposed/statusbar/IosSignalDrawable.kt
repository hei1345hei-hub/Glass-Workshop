package com.c17.glass.xposed.statusbar

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * 单层 iOS 信号格：4 根等宽圆角柱，按资源名里的 0~4 级填充。
 * 双层（stacked）时本模块把副卡资源返回透明，主卡仍画单排，
 * 整体就是 iPhone 单排信号的样子；双层模式则保持系统原生堆叠。
 */
class IosSignalDrawable(
    private val bars: Int,
    private val fallbackInk: Int,
    private val intrinsicW: Int,
    private val intrinsicH: Int,
    private val density: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tintColor: Int? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val ink = tintColor ?: HookRuntime.iconColor.takeIf { it != Color.TRANSPARENT } ?: fallbackInk
        paint.color = ink
        paint.alpha = 255

        val count = 4
        val h = b.height().toFloat()
        val w = b.width().toFloat()
        val gap = max(density * 1.0f, w * 0.10f)
        val barW = (w - gap * (count - 1)) / count
        if (barW <= 0f) return
        val maxBarH = h * 0.92f
        val baseY = b.bottom.toFloat()
        val radius = barW * 0.42f

        for (i in 0 until count) {
            val left = b.left + i * (barW + gap)
            val ratio = if (bars <= 0) 0f else (i + 1).toFloat() / count
            val active = i < bars
            val barH = maxBarH * max(0.22f, ratio)
            paint.color = if (active) ink else withAlpha(ink, 0.28f)
            canvas.drawRoundRect(
                left, baseY - barH, left + barW, baseY,
                radius, radius, paint
            )
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        tintColor = tint?.defaultColor
        invalidateSelf()
    }

    override fun setTint(tintColor: Int) {
        this.tintColor = tintColor
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH

    companion object {
        /** 信号资源名的电量级别：_0~_4，noservice 记 0 级。 */
        private val LEVEL = Regex("_([0-4]|noservice)(?:_os17)?(?:_fully)?$")

        fun levelOf(resourceName: String): Int? {
            if (resourceName.contains("noservice") || resourceName.contains("_null")) {
                return 0
            }
            val m = LEVEL.find(resourceName) ?: return null
            val value = m.groupValues[1]
            return value.toIntOrNull() ?: 0
        }

        fun isMobileSignal(name: String): Boolean =
            name.startsWith("stat_signal_lte_signal") ||
                name.startsWith("stat_signal_soft_signal") ||
                name.startsWith("stat_signal_signal_lte") ||
                name.startsWith("stat_signal_signal_novoice") ||
                name.startsWith("stat_signal_single_novoice") ||
                name.startsWith("stat_signal_noservice") ||
                name.startsWith("stat_signal_soft_noservice") ||
                name.startsWith("stat_sys_signal_")

        fun isSecondary(name: String): Boolean =
            name.contains("stacked_secondary") || name.contains("_secondary_")
    }
}

/** 仅用于让副卡图标“消失”但仍占位。 */
fun hiddenSignalDrawable(native: Drawable): Drawable =
    object : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT

        override fun getIntrinsicWidth(): Int = native.intrinsicWidth
        override fun getIntrinsicHeight(): Int = native.intrinsicHeight
    }

/** 小工具：按 density 把 dp 转 px 时至少 1px。 */
fun dp(density: Float, value: Float): Int = max(1, (value * density).toInt())
