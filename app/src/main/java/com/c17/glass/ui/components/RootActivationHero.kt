package com.c17.glass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.core.RootState
import com.c17.glass.ui.theme.Glass

/**
 * 首页「已激活」大卡，结构对齐 Status C17 的 ActivationHero：
 * 左侧浅绿状态卡 + 右下角 128dp 圆环勾，右侧两张小卡。
 * 左下角大卡表示 root 状态，右下角小卡单独显示 LSPosed 是否运行。
 */
@Composable
fun RootActivationHero(
    deviceModel: String,
    lsposedActive: Boolean?,
    onRefresh: () -> Unit,
    onRefreshLsposed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Glass.colors
    val granted = RootState.granted == true
    val checking = RootState.checking

    val cardColor = when {
        checking -> if (colors.isDark) Color(0xFF3B2D16) else Color(0xFFFFF2D9)
        granted -> if (colors.isDark) Color(0xFF173820) else Color(0xFFDFFBE4)
        else -> if (colors.isDark) Color(0xFF282B31) else Color(0xFFEDF1F7)
    }
    val accent = when {
        checking -> if (colors.isDark) Color(0xFFF3B24A) else Color(0xFFD79E25)
        granted -> if (colors.isDark) Color(0xFF46D970) else Color(0xFF2FCE60)
        else -> if (colors.isDark) Color(0xFF98A2B3) else Color(0xFF8A98AA)
    }
    val foreground = when {
        granted -> if (colors.isDark) Color(0xFFE9F4EF) else Color(0xFF173C32)
        else -> colors.ink
    }
    val secondary = when {
        granted -> if (colors.isDark) Color(0xFF8FD8B4) else Color(0xFF2F8F6B)
        checking -> if (colors.isDark) Color(0xFFF3B24A) else Color(0xFF9A6A12)
        else -> colors.inkMuted
    }

    val title = when {
        checking -> "检测中"
        granted -> "已激活"
        else -> "未激活"
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(180.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(cardColor)
                .clickable { onRefresh() }
        ) {
            // 与 Status C17 相同的 128dp 圆环 + 勾，从右下角探出并被卡片裁切。
            Canvas(
                Modifier
                    .size(128.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 24.dp, y = 35.dp)
            ) {
                val stroke = size.width * 0.075f
                drawCircle(
                    color = accent,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke)
                )
                if (granted) {
                    val tick = Path().apply {
                        moveTo(size.width * 0.31f, size.height * 0.54f)
                        lineTo(size.width * 0.45f, size.height * 0.68f)
                        lineTo(size.width * 0.77f, size.height * 0.35f)
                    }
                    drawPath(
                        tick,
                        accent,
                        style = Stroke(
                            width = stroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            if (!granted) {
                GlassIcon(
                    icon = GlassIcons.Shield,
                    tint = accent,
                    size = 54.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 30.dp, bottom = 42.dp)
                )
            }

            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                BasicText(
                    "玻璃工坊",
                    style = TextStyle(
                        color = foreground,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    )
                )
                if (!granted) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        if (checking) "检测中…" else "$title · 点击刷新",
                        style = TextStyle(
                            color = secondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            BasicText(
                if (checking) "检测中" else "刷新",
                Modifier
                    .align(Alignment.TopEnd)
                    .clickable(enabled = !checking) { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = TextStyle(
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallStatusCard(
                label = "当前设备",
                title = deviceModel.ifBlank { "—" },
                background = if (colors.isDark) Color(0xFF242424) else Color(0xFFFFFFFF),
                titleColor = colors.ink,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            SmallStatusCard(
                label = "LSPosed",
                title = when (lsposedActive) {
                    true -> "已启用"
                    false -> "未启用"
                    null -> "检测中"
                },
                icon = GlassIcons.Shield,
                background = if (colors.isDark) Color(0xFF242424) else Color(0xFFFFFFFF),
                titleColor = when (lsposedActive) {
                    true -> colors.success
                    false -> colors.warn
                    null -> colors.inkMuted
                },
                onClick = onRefreshLsposed,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SmallStatusCard(
    label: String,
    title: String,
    background: Color,
    titleColor: Color,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = Glass.colors
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicText(
                label,
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    GlassIcon(icon, titleColor, 16.dp)
                    Spacer(Modifier.width(5.dp))
                }
                BasicText(
                    title,
                    style = TextStyle(
                        color = titleColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 把 `su -v` 的 "4.3.0-r2:KernelSU" 转成 "KernelSU · 4.3.0-r2"。 */
private fun suChannelLabel(raw: String): String {
    if (raw.isBlank()) return "root 通道已连通"
    val parts = raw.split(":", limit = 2)
    return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        "${parts[1].trim()} · ${parts[0].trim()}"
    } else {
        raw
    }
}


