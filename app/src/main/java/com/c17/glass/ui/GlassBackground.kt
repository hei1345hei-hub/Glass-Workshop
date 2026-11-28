package com.c17.glass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.c17.glass.ui.theme.Glass

/**
 * 背景层：浅色主题是中性灰渐变；深色主题是纯黑，不带灰雾或蓝色光斑。
 *
 * 注意：这一层会被 layerBackdrop 录制成折射源，必须保持静态。
 * 如果在这里跑无限动画，backdrop 层会每帧重录整屏，叠加多张玻璃卡片的
 * blur/lens 后 RenderThread 压力会非常大。
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val colors = Glass.colors

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to colors.bgTop,
                0.55f to colors.bgBottom,
                1f to colors.bgBottom
            )
        )

        if (!colors.isDark) {
            val w = size.width
            // 浅色：顶部压一层几乎看不见的白色，让灰底有轻微层次，不改变“纯灰”观感。
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(w * 0.5f, 0f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.5f, 0f)
            )
        }
    }
}
