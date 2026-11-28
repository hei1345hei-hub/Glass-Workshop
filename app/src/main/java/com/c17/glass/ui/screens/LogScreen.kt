package com.c17.glass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.core.LogEntry
import com.c17.glass.core.LogLevel
import com.c17.glass.core.LogRepository
import com.c17.glass.ui.components.GlassCard
import com.c17.glass.ui.components.GlassChip
import com.c17.glass.ui.components.GlassIcon
import com.c17.glass.ui.components.GlassIcons
import com.c17.glass.ui.components.PageHeader
import com.c17.glass.ui.components.SectionTitle
import com.c17.glass.ui.components.TagPill
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.Backdrop

@Composable
fun LogScreen(backdrop: Backdrop) {
    val colors = Glass.colors
    val clipboard = LocalClipboardManager.current
    var filter by remember { mutableStateOf<LogLevel?>(null) }

    val allEntries = LogRepository.entries.toList()
    val entries = if (filter == null) allEntries else allEntries.filter { it.level == filter }
    val counts = LogLevel.entries.associateWith { level ->
        allEntries.count { it.level == level }
    }
    val visibleLevels = LogLevel.entries.filter { (counts[it] ?: 0) > 0 }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = statusBarTop + 10.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader(
                title = "运行日志",
                trailing = { TagPill("LIVE", colors.success) }
            )
        }

        item {
            LogOverviewCard(
                backdrop = backdrop,
                total = allEntries.size,
                lastTime = allEntries.firstOrNull()?.time,
                counts = counts,
                onCopy = {
                    clipboard.setText(AnnotatedString(LogRepository.exportText()))
                },
                onClear = {
                    LogRepository.clear()
                    filter = null
                }
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip(
                    label = "全部 ${allEntries.size}",
                    selected = filter == null,
                    onClick = { filter = null },
                    backdrop = backdrop,
                    horizontalPadding = 13.dp
                )
                visibleLevels.forEach { level ->
                    GlassChip(
                        label = "${level.label} ${counts[level] ?: 0}",
                        selected = filter == level,
                        onClick = { filter = level },
                        backdrop = backdrop,
                        horizontalPadding = 13.dp
                    )
                }
            }
        }

        item {
            SectionTitle(
                "时间线",
                trailing = "${entries.size} 条 · 最新在上"
            )
        }

        if (entries.isEmpty()) {
            item {
                EmptyLogCard(
                    backdrop = backdrop,
                    filtered = filter != null,
                    onReset = { filter = null }
                )
            }
        }

        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            LogTimelineRow(
                entry = entry,
                showLine = index != entries.lastIndex
            )
        }
    }
}

@Composable
private fun LogOverviewCard(
    backdrop: Backdrop,
    total: Int,
    lastTime: String?,
    counts: Map<LogLevel, Int>,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val colors = Glass.colors
    GlassCard(
        backdrop = backdrop,
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(colors.success, CircleShape)
                    )
                    Spacer(Modifier.width(7.dp))
                    BasicText(
                        "累计记录",
                        style = TextStyle(
                            color = colors.inkDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(top = 5.dp)
                ) {
                    BasicText(
                        total.toString(),
                        style = TextStyle(
                            color = colors.ink,
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp
                        )
                    )
                    BasicText(
                        " 条",
                        Modifier.padding(bottom = 4.dp, start = 2.dp),
                        style = TextStyle(
                            color = colors.inkMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                BasicText(
                    "最近更新 ${lastTime ?: "暂无记录"}",
                    Modifier.padding(top = 2.dp),
                    style = TextStyle(color = colors.inkMuted, fontSize = 11.sp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LogAction(
                    label = "复制全部",
                    color = colors.accent,
                    onClick = onCopy
                )
                LogAction(
                    label = "清空",
                    color = colors.danger,
                    onClick = onClear
                )
            }
        }

        Spacer(Modifier.height(15.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatPill(
                label = "成功",
                count = counts[LogLevel.SUCCESS] ?: 0,
                color = colors.success,
                modifier = Modifier.weight(1f)
            )
            StatPill(
                label = "警告",
                count = counts[LogLevel.WARN] ?: 0,
                color = colors.warn,
                modifier = Modifier.weight(1f)
            )
            StatPill(
                label = "错误",
                count = counts[LogLevel.ERROR] ?: 0,
                color = colors.danger,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LogAction(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun StatPill(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .background(color.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            label,
            style = TextStyle(
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            count.toString(),
            style = TextStyle(
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun EmptyLogCard(
    backdrop: Backdrop,
    filtered: Boolean,
    onReset: () -> Unit
) {
    val colors = Glass.colors
    GlassCard(backdrop) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(colors.accent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                GlassIcon(GlassIcons.Log, colors.accent, 24.dp)
            }
            BasicText(
                if (filtered) "这个筛选下还没有记录" else "还没有运行日志",
                Modifier.padding(top = 12.dp),
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                if (filtered) {
                    "切换到「全部」，或应用一次配置后再回来看看。"
                } else {
                    "应用一次配置、重启 SystemUI 或检查 root 后，记录会出现在这里。"
                },
                Modifier.padding(top = 5.dp),
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            )
            if (filtered) {
                Spacer(Modifier.height(12.dp))
                LogAction(
                    label = "查看全部",
                    color = colors.accent,
                    onClick = onReset
                )
            }
        }
    }
}

@Composable
private fun LogTimelineRow(
    entry: LogEntry,
    showLine: Boolean
) {
    val colors = Glass.colors
    val levelColor = when (entry.level) {
        LogLevel.INFO -> colors.inkMuted
        LogLevel.SUCCESS -> colors.success
        LogLevel.WARN -> colors.warn
        LogLevel.ERROR -> colors.danger
        LogLevel.CMD -> colors.accent
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(
            Modifier
                .width(26.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .size(9.dp)
                    .background(levelColor, CircleShape)
            )
            if (showLine) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(1.dp)
                        .weight(1f)
                        .background(colors.separator)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .background(colors.cardSurface, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogLevelPill(entry.level, levelColor)
                Spacer(Modifier.width(8.dp))
                BasicText(
                    entry.title,
                    Modifier.weight(1f),
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    entry.time,
                    style = TextStyle(
                        color = colors.inkDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
            if (entry.detail.isNotBlank()) {
                BasicText(
                    entry.detail,
                    Modifier.padding(top = 7.dp),
                    style = TextStyle(
                        color = colors.inkMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LogLevelPill(level: LogLevel, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.11f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        BasicText(
            level.label,
            style = TextStyle(
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}
