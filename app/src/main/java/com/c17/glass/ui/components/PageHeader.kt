package com.c17.glass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.ui.theme.Glass

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = Glass.colors
    Column(modifier.fillMaxWidth()) {
        // 眉标题已移除；用占位高度把标题留在原来的纵向位置。
        if (trailing != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing
                )
            }
        } else {
            Spacer(Modifier.height(13.dp))
        }
        BasicText(
            title,
            Modifier.padding(top = 10.dp),
            style = TextStyle(
                color = colors.ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
        )
        if (subtitle != null) {
            BasicText(
                subtitle,
                Modifier.padding(top = 6.dp),
                style = TextStyle(color = colors.inkMuted, fontSize = 13.sp, lineHeight = 18.sp)
            )
        }
    }
}
