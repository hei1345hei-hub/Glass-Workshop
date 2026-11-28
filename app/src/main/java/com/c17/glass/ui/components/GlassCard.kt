package com.c17.glass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.ui.theme.Glass
import com.c17.glass.ui.theme.GlassColors
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule

/** 纯净卡片：浅色模式使用硬件轻量投影与白色底面，避免在实心卡片下空跑整屏 RenderEffect 模糊。 */
@Composable
fun GlassCard(
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = Glass.colors
    val shape = AbsoluteRoundedCornerShape(corner)
    val surface = if (colors.isDark) {
        Modifier.background(colors.cardSurface, shape)
    } else {
        Modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .background(colors.cardSurface, shape)
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(surface)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    val colors = Glass.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = colors.inkMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )
        )
        if (trailing != null) {
            BasicText(
                trailing,
                style = TextStyle(color = colors.inkDim, fontSize = 12.sp)
            )
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier, verticalPadding: Dp = 9.dp) {
    val colors = Glass.colors
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .height(1.dp)
    ) {
        drawRect(colors.separator)
    }
}

/** 胶囊筛选项：以轻量化描边与纯色底面替代重着色器，避免列表横向滚动掉帧。 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp
) {
    val colors: GlassColors = Glass.colors
    val shape = Capsule()
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(
                if (selected) colors.accent.copy(alpha = 0.92f) else colors.chipSurface,
                shape
            )
            .then(
                if (!selected) Modifier.border(0.8.dp, colors.separator, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (selected) colors.onAccent else colors.inkMuted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

/** 左侧标题 + 右侧控件的设置行。 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    control: @Composable RowScope.() -> Unit
) {
    val colors = Glass.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.padding(end = 16.dp).weight(1f, fill = false)) {
            BasicText(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (description != null) {
                BasicText(
                    description,
                    Modifier.padding(top = 3.dp),
                    style = TextStyle(color = colors.inkDim, fontSize = 12.sp, lineHeight = 16.sp)
                )
            }
        }
        control()
    }
}
