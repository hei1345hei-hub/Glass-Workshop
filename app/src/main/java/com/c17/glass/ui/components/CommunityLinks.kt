package com.c17.glass.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.c17.glass.R
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.Backdrop

internal object C17Links {
    const val REPO_URL = "https://github.com/hei1345hei-hub/Glass-Workshop"
    const val ISSUE_URL = "$REPO_URL/issues/new"
    const val README_URL = "$REPO_URL/blob/main/README.md"

    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

/**
 * 首页底部的「开源免费 + 打赏入口」组合卡片：
 * 一个外层 GlassCard 容器，内部并排放置两枚风格与色彩区分明确的独立小卡片。
 */
@Composable
internal fun HomeOpenSourceSponsorCard(
    backdrop: Backdrop,
    onOpenRepo: () -> Unit,
    onOpenSponsor: () -> Unit
) {
    val colors = Glass.colors
    val openSourceBg = if (colors.isDark) colors.accent.copy(alpha = 0.14f) else Color(0xFFF1F5FF)
    val openSourceBorder = colors.accent.copy(alpha = if (colors.isDark) 0.28f else 0.18f)

    val sponsorAccent = if (colors.isDark) Color(0xFFFB7185) else Color(0xFFE11D48)
    val sponsorBg = if (colors.isDark) Color(0xFF2F1A22) else Color(0xFFFFF1F4)
    val sponsorBorder = sponsorAccent.copy(alpha = if (colors.isDark) 0.28f else 0.18f)

    GlassCard(
        backdrop = backdrop,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FooterMiniBox(
                modifier = Modifier.weight(1f),
                icon = GlassIcons.Code,
                accentColor = colors.accent,
                bgColor = openSourceBg,
                borderColor = openSourceBorder,
                tag = "开源免费",
                title = "本软件永久开源免费",
                subtitle = "GitHub 源码公开",
                onClick = onOpenRepo
            )
            FooterMiniBox(
                modifier = Modifier.weight(1f),
                icon = GlassIcons.Heart,
                accentColor = sponsorAccent,
                bgColor = sponsorBg,
                borderColor = sponsorBorder,
                tag = "发电支持",
                title = "打赏支持作者",
                subtitle = "微信赞赏码",
                onClick = onOpenSponsor
            )
        }
    }
}

@Composable
private fun FooterMiniBox(
    modifier: Modifier,
    icon: ImageVector,
    accentColor: Color,
    bgColor: Color,
    borderColor: Color,
    tag: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = Glass.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                GlassIcon(icon = icon, tint = accentColor, size = 15.dp)
            }
            Box(
                Modifier
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                BasicText(
                    tag,
                    style = TextStyle(
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                subtitle,
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 11.sp
                )
            )
        }
    }
}

@Composable
internal fun SponsorDialog(
    onDismiss: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val colors = Glass.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .background(colors.cardSurface, RoundedCornerShape(26.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(GlassIcons.Heart, colors.accent, 22.dp)
                BasicText(
                    "赞助作者",
                    Modifier.padding(start = 10.dp),
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            BasicText(
                "C17 玻璃工坊由个人维护，测试机、时间和后续适配都靠自愿支持。",
                Modifier.padding(top = 10.dp),
                style = TextStyle(color = colors.inkMuted, fontSize = 12.sp, lineHeight = 18.sp)
            )
            Image(
                painter = painterResource(id = R.drawable.sponsor_qr),
                contentDescription = "微信赞赏码",
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .height(310.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )
            BasicText(
                "微信扫码 · 金额随意 · 感谢支持",
                Modifier.padding(top = 10.dp),
                style = TextStyle(color = colors.inkMuted, fontSize = 11.sp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                DialogAction("Issue 留言", false, onOpenIssues)
                Spacer(Modifier.size(8.dp))
                DialogAction("关闭", true, onDismiss)
            }
        }
    }
}

@Composable
internal fun DialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = Glass.colors
    Box(
        Modifier
            .background(
                if (primary) colors.accent else colors.chipSurface,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (primary) colors.onAccent else colors.inkMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
