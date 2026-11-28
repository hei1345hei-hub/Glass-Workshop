package com.c17.glass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.ui.theme.Glass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    val colors = Glass.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            label,
            Modifier.weight(1f),
            style = TextStyle(
                color = colors.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        )
        BasicText(
            value,
            Modifier
                .padding(start = 16.dp)
                .weight(1.4f),
            style = TextStyle(
                color = valueColor ?: colors.inkMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.End
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 7) {
    Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun TagPill(text: String, color: Color = Glass.colors.accent) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}
