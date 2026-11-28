package com.c17.glass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.c17.glass.core.SafetyManager
import com.c17.glass.core.SafetyState
import com.c17.glass.core.TweakState
import com.c17.glass.ui.components.GlassCard
import com.c17.glass.ui.components.GlassDivider
import com.c17.glass.ui.components.GlassIcon
import com.c17.glass.ui.components.GlassIcons
import com.c17.glass.ui.components.LiquidButton
import com.c17.glass.ui.components.LiquidSlider
import com.c17.glass.ui.components.LiquidToggle
import com.c17.glass.ui.components.PageHeader
import com.c17.glass.ui.components.SectionTitle
import com.c17.glass.ui.components.SettingRow
import com.c17.glass.ui.components.TagPill
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

private enum class FeatureSection {
    LIQUID,
    STATUS
}

@Composable
fun FeaturesScreen(
    backdrop: Backdrop,
    onApply: () -> Unit,
    onRestore: () -> Unit
) {
    val colors = Glass.colors
    var section by rememberSaveable { mutableStateOf<FeatureSection?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(Unit) {
        TweakState.refreshPendingRestartFlag()
    }

    if (showRestoreConfirm) {
        RestoreConfirmDialog(
            onDismiss = { showRestoreConfirm = false },
            onConfirm = {
                showRestoreConfirm = false
                onRestore()
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = statusBarTop + 10.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (section == null) {
            item {
                PageHeader(
                    title = "功能"
                )
            }

            item { ApplyCard(backdrop, onApply) }

            item {
                FeatureEntryCard(
                    backdrop = backdrop,
                    title = "液态玻璃",
                    subtitle = "控制/通知 · 桌面/Dock · 音量 · 全局浮窗",
                    icon = GlassIcons.Layers,
                    tint = colors.accent,
                    onClick = { section = FeatureSection.LIQUID }
                )
            }

            item {
                FeatureEntryCard(
                    backdrop = backdrop,
                    title = "状态栏",
                    subtitle = "开发中 · 敬请期待",
                    icon = GlassIcons.Tune,
                    tint = colors.inkMuted,
                    onClick = { section = FeatureSection.STATUS }
                )
            }

            item {
                val glassSurfaces = listOf(
                    TweakState.glassControlCenter,
                    TweakState.glassNotifications,
                    TweakState.glassVolume,
                    TweakState.glassFolders,
                    TweakState.glassDock,
                    TweakState.applyGlobally
                ).count { it }
                GlassCard(backdrop) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            "当前方案",
                            style = TextStyle(
                                color = colors.ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        TagPill("范围 $glassSurfaces/6")
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        "状态栏 开发中 · " +
                            "玻璃范围 $glassSurfaces/6" +
                            (TweakState.lastAppliedAt?.let { " · 最近应用 $it" } ?: " · 尚未应用"),
                        style = TextStyle(
                            color = colors.inkMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    )
                }
            }

            item {
                SafetyCard(
                    backdrop = backdrop,
                    onRestore = { showRestoreConfirm = true }
                )
            }
        } else {
            item {
                BackRow(label = "功能") { section = null }
            }

            when (section) {
                FeatureSection.LIQUID -> {
                    item {
                        PageHeader(
                            title = "液态玻璃"
                        )
                    }
                    liquidGlassContent(backdrop, onApply)
                }

                FeatureSection.STATUS -> {
                    item {
                        PageHeader(
                            title = "状态栏"
                        )
                    }
                    statusBarContent(backdrop)
                }

                null -> Unit
            }
        }
    }
}

private fun LazyListScope.liquidGlassContent(
    backdrop: Backdrop,
    onApply: () -> Unit
) {
    item { ApplyCard(backdrop, onApply) }

    item { SectionTitle("玻璃范围", trailing = "SURFACES") }

    item {
        GlassCard(backdrop) {
            ToggleRow(
                title = "控制中心",
                description = "下拉控制中心卡片与滑块",
                selected = { TweakState.glassControlCenter },
                onSelect = { TweakState.glassControlCenter = it },
                backdrop = backdrop
            )
            GlassDivider()
            ToggleRow(
                title = "通知中心",
                description = "下拉通知与横幅卡片",
                selected = { TweakState.glassNotifications },
                onSelect = { TweakState.glassNotifications = it },
                backdrop = backdrop
            )
            GlassDivider()
            ToggleRow(
                title = "音量条",
                description = "侧边音量滑块浮层",
                selected = { TweakState.glassVolume },
                onSelect = { TweakState.glassVolume = it },
                backdrop = backdrop
            )
            GlassDivider()
            ToggleRow(
                title = "桌面文件夹",
                description = "桌面文件夹背景",
                selected = { TweakState.glassFolders },
                onSelect = { TweakState.glassFolders = it },
                backdrop = backdrop
            )
            GlassDivider()
            ToggleRow(
                title = "Dock 栏",
                description = "底部 Dock 背景",
                selected = { TweakState.glassDock },
                onSelect = { TweakState.glassDock = it },
                backdrop = backdrop
            )
            GlassDivider()
            ToggleRow(
                title = "全局生效",
                description = "对消息弹窗（横幅通知）、长按软件小浮窗、桌面卡片及系统其它模糊浮层生效",
                selected = { TweakState.applyGlobally },
                onSelect = { TweakState.applyGlobally = it },
                backdrop = backdrop
            )
        }
    }

    item { SectionTitle("控制中心与通知中心", trailing = "QS & NOTIF") }

    item {
        GlassParamGroupCard(
            backdrop = backdrop,
            refraction = TweakState.refraction,
            blurRadius = TweakState.blurRadius,
            blurRange = 4f..64f,
            cornerRadius = TweakState.cornerRadius,
            highlight = TweakState.highlight,
            defaultSummary = "固定默认：折射 58% · 模糊 24dp · 圆角 26dp · 高光开（50% ≈ 原 100%）",
            onRefractionChange = { TweakState.refraction = it },
            onBlurChange = { TweakState.blurRadius = it },
            onCornerChange = { TweakState.cornerRadius = it },
            onHighlightChange = { TweakState.highlight = it },
            onRestoreDefault = { TweakState.restoreQsGlassDefaults() }
        )
    }

    item { SectionTitle("桌面文件夹与 Dock 栏", trailing = "LAUNCHER") }

    item {
        GlassParamGroupCard(
            backdrop = backdrop,
            refraction = TweakState.launcherRefraction,
            blurRadius = TweakState.launcherBlurRadius,
            blurRange = 2f..32f,
            cornerRadius = TweakState.launcherCornerRadius,
            highlight = TweakState.launcherHighlight,
            defaultSummary = "固定默认：折射 58% · 模糊 10dp · 圆角 26dp · 高光开（含长按小浮窗）",
            onRefractionChange = { TweakState.launcherRefraction = it },
            onBlurChange = { TweakState.launcherBlurRadius = it },
            onCornerChange = { TweakState.launcherCornerRadius = it },
            onHighlightChange = { TweakState.launcherHighlight = it },
            onRestoreDefault = { TweakState.restoreLauncherGlassDefaults() }
        )
    }

    item { SectionTitle("音量条", trailing = "VOLUME") }

    item {
        GlassParamGroupCard(
            backdrop = backdrop,
            refraction = TweakState.volumeRefraction,
            blurRadius = TweakState.volumeBlurRadius,
            blurRange = 2f..48f,
            cornerRadius = TweakState.volumeCornerRadius,
            highlight = TweakState.volumeHighlight,
            defaultSummary = "固定默认：折射 58% · 模糊 16dp · 圆角 26dp · 高光开",
            onRefractionChange = { TweakState.volumeRefraction = it },
            onBlurChange = { TweakState.volumeBlurRadius = it },
            onCornerChange = { TweakState.volumeCornerRadius = it },
            onHighlightChange = { TweakState.volumeHighlight = it },
            onRestoreDefault = { TweakState.restoreVolumeGlassDefaults() }
        )
    }
}

@Composable
private fun GlassParamGroupCard(
    backdrop: Backdrop,
    refraction: Float,
    blurRadius: Float,
    blurRange: ClosedFloatingPointRange<Float>,
    cornerRadius: Float,
    highlight: Boolean,
    defaultSummary: String,
    onRefractionChange: (Float) -> Unit,
    onBlurChange: (Float) -> Unit,
    onCornerChange: (Float) -> Unit,
    onHighlightChange: (Boolean) -> Unit,
    onRestoreDefault: () -> Unit
) {
    val colors = Glass.colors
    GlassCard(backdrop) {
        SliderRow(
            title = "折射强度",
            valueText = "${(refraction * 100).roundToInt()}%",
            value = refraction,
            range = 0f..1f,
            backdrop = backdrop,
            onValueChange = onRefractionChange
        )
        GlassDivider()
        SliderRow(
            title = "模糊半径",
            valueText = "${blurRadius.toInt()}dp",
            value = blurRadius,
            range = blurRange,
            backdrop = backdrop,
            onValueChange = onBlurChange
        )
        GlassDivider()
        SliderRow(
            title = "圆角半径",
            valueText = "${cornerRadius.toInt()}dp",
            value = cornerRadius,
            range = 8f..48f,
            backdrop = backdrop,
            onValueChange = onCornerChange
        )
        GlassDivider()
        ToggleRow(
            title = "高光描边",
            description = "玻璃表面顶部与边缘高光",
            selected = { highlight },
            onSelect = onHighlightChange,
            backdrop = backdrop
        )
        GlassDivider()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                defaultSummary,
                Modifier.weight(1f).padding(end = 10.dp),
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accent)
                    .clickable(onClick = onRestoreDefault)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                BasicText(
                    "恢复默认",
                    style = TextStyle(
                        color = colors.onAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

private fun LazyListScope.statusBarContent(backdrop: Backdrop) {
    item {
        val colors = Glass.colors
        GlassCard(backdrop) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "状态栏功能开发中",
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                TagPill("开发中")
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                "状态栏样式与图标调节正在进一步适配 ColorOS 17，当前暂不开放调节。后续版本完善后会在此页面恢复显示。",
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

@Composable
private fun FeatureEntryCard(
    backdrop: Backdrop,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    val colors = Glass.colors
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(88.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(tint.copy(alpha = 0.12f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GlassIcon(icon, tint, 24.dp)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                BasicText(
                    title,
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp
                    )
                )
                BasicText(
                    subtitle,
                    Modifier.padding(top = 4.dp),
                    style = TextStyle(color = colors.inkDim, fontSize = 12.sp)
                )
            }
            GlassIcon(GlassIcons.ChevronRight, colors.inkDim, 18.dp)
        }
    }
}

@Composable
private fun BackRow(
    label: String,
    onClick: () -> Unit
) {
    val colors = Glass.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIcon(GlassIcons.ChevronLeft, colors.accent, 17.dp)
        Spacer(Modifier.size(4.dp))
        BasicText(
            label,
            style = TextStyle(
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun SafetyCard(
    backdrop: Backdrop,
    onRestore: () -> Unit
) {
    val colors = Glass.colors
    GlassCard(backdrop) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "安全还原",
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            TagPill(
                if (SafetyState.ready) "已备份" else "待备份",
                color = if (SafetyState.ready) colors.success else colors.warn
            )
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (SafetyState.ready) {
                "应用前会记住上一份配置；应用后 ${SafetyManager.WATCH_WINDOW_MS / 1000} 秒内检测到 SystemUI 崩溃或重启，" +
                    "会自动关闭这次改动的选项并退回上一份配置。"
            } else {
                "获得 root 后会自动建立安全基线；备份未成功时不会执行应用。"
            },
            style = TextStyle(
                color = colors.inkMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagPill(
                when {
                    !SafetyState.ready -> "守护待启动"
                    SafetyState.watching -> "守护中"
                    else -> "崩溃守护"
                },
                color = if (SafetyState.ready) colors.success else colors.warn
            )
            Spacer(Modifier.size(6.dp))
            TagPill(
                if (SafetyState.guardInstalled) "开机守护 已装" else "开机守护 未装",
                color = if (SafetyState.guardInstalled) colors.success else colors.warn
            )
        }
        SafetyState.lastBackupAt?.let {
            Spacer(Modifier.height(4.dp))
            BasicText(
                "基线 $it",
                style = TextStyle(
                    color = colors.inkDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
        if (SafetyState.watching) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                "正在守护本次改动：${SafetyManager.WATCH_WINDOW_MS / 1000} 秒内出问题会自动关闭。",
                style = TextStyle(color = colors.success, fontSize = 11.sp)
            )
        } else if (SafetyState.pending) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                "上次改动还没确认：重新打开 App 会先扫描崩溃记录，有问题就自动回滚。",
                style = TextStyle(color = colors.danger, fontSize = 11.sp)
            )
        }
        SafetyState.lastAutoRevertKeys?.let { keys ->
            Spacer(Modifier.height(10.dp))
            BasicText(
                "已自动关闭：$keys",
                style = TextStyle(
                    color = colors.danger,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            SafetyState.lastAutoRevertReason?.let { reason ->
                Spacer(Modifier.height(2.dp))
                BasicText(
                    "原因：$reason",
                    style = TextStyle(color = colors.inkMuted, fontSize = 10.sp, lineHeight = 14.sp)
                )
            }
            SafetyState.lastAutoRevertAt?.let { at ->
                Spacer(Modifier.height(2.dp))
                BasicText(
                    at,
                    style = TextStyle(
                        color = colors.inkDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        SafetyState.lastMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            BasicText(
                message,
                style = TextStyle(
                    color = if (message.contains("失败")) colors.danger else colors.success,
                    fontSize = 11.sp
                )
            )
        }
        Spacer(Modifier.height(14.dp))
        LiquidButton(
            onClick = onRestore,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            isInteractive = !SafetyState.restoring,
            tint = colors.danger
        ) {
            GlassIcon(GlassIcons.Restore, Color.White, 17.dp)
            BasicText(
                if (SafetyState.restoring) "还原中…" else "全部还原",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            "无法打开 App 时，在 root 管理器终端执行：\n" +
                "sh /data/local/tmp/c17_glass/restore.sh",
            style = TextStyle(
                color = colors.inkDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        )
    }
}

@Composable
private fun RestoreConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
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
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(GlassIcons.Shield, colors.danger, 22.dp)
                BasicText(
                    "全部还原？",
                    Modifier.padding(start = 10.dp).weight(1f),
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            BasicText(
                "会清理本软件配置文件、恢复时钟设置和已登记的系统文件，并重启 SystemUI。",
                style = TextStyle(color = colors.inkMuted, fontSize = 12.sp, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "还原期间不要断电。若手机仍可正常操作，建议先停止其他系统修改。",
                style = TextStyle(color = colors.inkDim, fontSize = 11.sp, lineHeight = 16.sp)
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SafetyDialogAction("取消", primary = false, danger = false, onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                SafetyDialogAction("确认还原", primary = true, danger = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun SafetyDialogAction(
    label: String,
    primary: Boolean,
    danger: Boolean,
    onClick: () -> Unit
) {
    val colors = Glass.colors
    Box(
        Modifier
            .background(
                when {
                    primary && danger -> colors.danger
                    primary -> colors.accent
                    else -> colors.chipSurface
                },
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

@Composable
private fun ApplyCard(
    backdrop: Backdrop,
    onApply: () -> Unit
) {
    val colors = Glass.colors
    val needsUiRestart = TweakState.pendingSystemUiRestart

    GlassCard(backdrop) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "配置摘要",
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            if (needsUiRestart) {
                TagPill(text = "需重启作用域", color = colors.warn)
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (needsUiRestart) {
                "检测到模块已更新：点击写入配置并同时重启系统界面与系统桌面（90 秒冷却，防 LSPosed 安全模式）。"
            } else {
                "点击写入配置并同时重启系统界面与系统桌面，两个作用域一起加载玻璃（90 秒冷却防安全模式）。"
            },
            style = TextStyle(
                color = if (needsUiRestart) colors.warn else colors.inkDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        )
        Spacer(Modifier.height(14.dp))
        LiquidButton(
            onClick = { if (!TweakState.applying) onApply() },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            isInteractive = !TweakState.applying,
            tint = if (needsUiRestart) colors.warn else colors.accent
        ) {
            GlassIcon(GlassIcons.Restore, Color.White, 16.dp)
            BasicText(
                if (TweakState.applying) "重启中…" else "重启作用域",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
        TweakState.scopeActionTip?.let { tip ->
            Spacer(Modifier.height(8.dp))
            BasicText(
                tip,
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String? = null,
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop
) {
    SettingRow(title = title, description = description) {
        LiquidToggle(selected = selected, onSelect = onSelect, backdrop = backdrop)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit
) {
    val colors = Glass.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BasicText(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            BasicText(
                valueText,
                style = TextStyle(
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            valueRange = range,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
