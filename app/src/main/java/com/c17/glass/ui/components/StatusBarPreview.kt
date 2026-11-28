package com.c17.glass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.core.Ios5gPosition
import com.c17.glass.core.TweakState
import com.c17.glass.ui.theme.Glass

/**
 * 仿 iPhone 状态栏预览：左侧时间与静音，右侧信号 / 5G / WiFi / 蓝牙 / 电池。
 * 所有参数直接读取 TweakState，便于在应用前确认效果。
 */
@Composable
fun StatusBarPreview(modifier: Modifier = Modifier) {
    val colors = Glass.colors
    val iconColor = if (TweakState.iconTint) colors.accent else Color.White
    val timeText = buildString {
        append(if (TweakState.iosTime24Hour) "09:41" else "9:41")
        if (TweakState.clockSeconds) append(":26")
    }
    val wallpaper = Brush.linearGradient(
        listOf(
            Color(0xFF0B1220),
            Color(0xFF172554),
            Color(0xFF334155)
        )
    )
    val barAlpha = (0.03f + TweakState.statusBarAlpha * 0.30f).coerceIn(0f, 0.28f)

    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(wallpaper)
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.minDimension * 0.48f,
                center = Offset(size.width * 0.86f, size.height * 0.94f)
            )
            drawCircle(
                color = Color(0xFF60A5FA).copy(alpha = 0.10f),
                radius = size.minDimension * 0.36f,
                center = Offset(size.width * 0.16f, size.height * 0.96f)
            )
        }

        // iOS 状态栏本身是透明覆盖层；这里用一层很薄的黑纱回显“状态栏透明度”。
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color.Black.copy(alpha = barAlpha))
        )

        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                timeText,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            )
            if (TweakState.iosMuteIcon) {
                Spacer(Modifier.width(6.dp))
                GlassIcon(GlassIcons.Silent, iconColor, 13.dp)
            }
            Spacer(Modifier.weight(1f))

            SignalBars(
                double = TweakState.iosDoubleSignal,
                tint = iconColor
            )
            if (TweakState.ios5gPosition == Ios5gPosition.DEFAULT) {
                Spacer(Modifier.width(4.dp))
                NetworkBadge(iconColor)
            }
            Spacer(Modifier.width(6.dp))
            GlassIcon(GlassIcons.Wifi, iconColor, 14.dp)

            if (TweakState.iosBluetoothIcon) {
                Spacer(Modifier.width(6.dp))
                GlassIcon(GlassIcons.Bluetooth, iconColor, 12.dp)
            }

            if (TweakState.ios5gPosition == Ios5gPosition.BATTERY) {
                Spacer(Modifier.width(6.dp))
                NetworkBadge(iconColor)
            }

            Spacer(Modifier.width(6.dp))
            BatteryGlyph(
                tint = iconColor,
                iosStyle = TweakState.iosBatteryStyle
            )
        }
    }
}

@Composable
private fun NetworkBadge(tint: Color) {
    BasicText(
        "5G",
        style = TextStyle(
            color = tint.copy(alpha = 0.92f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

/**
 * iOS 风格四格信号柱；双卡时上方一排柱子表示数据卡，
 * 下方一排圆点表示另一张卡（最右柱 + 圆点组合起来像感叹号）。
 */
@Composable
private fun SignalBars(
    double: Boolean,
    tint: Color
) {
    Canvas(
        Modifier.size(
            width = 18.dp,
            height = if (double) 19.dp else 12.dp
        )
    ) {
        val barWidth = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val rowHeight = 12.dp.toPx()
        val heights = listOf(4.dp, 6.5.dp, 9.dp, 12.dp).map { it.toPx() }

        heights.forEachIndexed { index, height ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(
                    x = index * (barWidth + gap),
                    y = rowHeight - height
                ),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        if (double) {
            val dotRadius = 1.5.dp.toPx()
            val dotY = rowHeight + 3.dp.toPx() + dotRadius
            repeat(4) { index ->
                drawCircle(
                    color = tint,
                    radius = dotRadius,
                    center = Offset(
                        x = index * (barWidth + gap) + barWidth / 2f,
                        y = dotY
                    )
                )
            }
        }
    }
}

@Composable
private fun BatteryGlyph(
    tint: Color,
    iosStyle: Boolean
) {
    Canvas(Modifier.size(width = if (iosStyle) 25.dp else 24.dp, height = 12.dp)) {
        val stroke = 1.2f.dp.toPx()
        val bodyWidth = size.width - 2.5f.dp.toPx()
        val bodyHeight = size.height

        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, 0f),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(
                x = if (iosStyle) 5.5.dp.toPx() else 1.5.dp.toPx(),
                y = if (iosStyle) 5.5.dp.toPx() else 1.5.dp.toPx()
            ),
            style = Stroke(width = stroke)
        )

        val capWidth = 1.8f.dp.toPx()
        val capHeight = 4.5f.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyWidth + 0.5f.dp.toPx(), (bodyHeight - capHeight) / 2f),
            size = Size(capWidth, capHeight),
            cornerRadius = CornerRadius(1f.dp.toPx(), 1f.dp.toPx())
        )

        val inset = stroke + 1.2f.dp.toPx()
        val fillWidth = (bodyWidth - inset * 2) * 0.86f
        val fillHeight = bodyHeight - inset * 2
        drawRoundRect(
            color = tint,
            topLeft = Offset(inset, inset),
            size = Size(fillWidth, fillHeight),
            cornerRadius = CornerRadius(
                x = if (iosStyle) 3.5.dp.toPx() else 1.dp.toPx(),
                y = if (iosStyle) 3.5.dp.toPx() else 1.dp.toPx()
            )
        )
    }
}
