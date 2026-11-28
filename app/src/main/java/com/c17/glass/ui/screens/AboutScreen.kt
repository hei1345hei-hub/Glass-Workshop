package com.c17.glass.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.c17.glass.BuildConfig
import com.c17.glass.R
import com.c17.glass.core.AppPrefs
import com.c17.glass.core.DeviceInfo
import com.c17.glass.core.DeviceInfoLoader
import com.c17.glass.core.RootState
import com.c17.glass.core.UpdateCheckResult
import com.c17.glass.core.UpdateChecker
import com.c17.glass.core.UpdateState
import com.c17.glass.ui.components.C17Links
import com.c17.glass.ui.components.DialogAction
import com.c17.glass.ui.components.GlassCard
import com.c17.glass.ui.components.GlassDivider
import com.c17.glass.ui.components.GlassIcons
import com.c17.glass.ui.components.GlassIcon
import com.c17.glass.ui.components.InfoRow
import com.c17.glass.ui.components.LiquidButton
import com.c17.glass.ui.components.PageHeader
import com.c17.glass.ui.components.SectionTitle
import com.c17.glass.ui.components.SponsorDialog
import com.c17.glass.ui.components.StatusDot
import com.c17.glass.ui.components.TagPill
import com.c17.glass.ui.theme.Glass
import com.c17.glass.ui.theme.ThemeMode
import com.c17.glass.ui.theme.ThemeState
import com.kyant.backdrop.Backdrop

@Composable
fun AboutScreen(
    backdrop: Backdrop,
    onOpenFeatures: () -> Unit
) {
    val colors = Glass.colors
    val context = LocalContext.current
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var showTutorial by remember { mutableStateOf(false) }
    var showSponsor by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        info = runCatching { DeviceInfoLoader.load() }.getOrNull()
    }

    fun openUrl(url: String) = C17Links.openUrl(context, url)

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

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
        item {
            PageHeader(
                title = "关于"
            )
        }

        item {
            val arrowAngle by animateFloatAsState(
                targetValue = if (introExpanded) 270f else 90f,
                label = "aboutArrow"
            )
            GlassCard(
                backdrop = backdrop,
                modifier = Modifier.clickable { introExpanded = !introExpanded }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF123A32),
                                        Color(0xFF0B2A2E),
                                        Color(0xFF06120F)
                                    )
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "C17 玻璃工坊图标",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        BasicText(
                            "C17 玻璃工坊",
                            style = TextStyle(
                                color = colors.ink,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        BasicText(
                            "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
                            Modifier.padding(top = 4.dp),
                            style = TextStyle(
                                color = colors.inkMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    GlassIcon(
                        icon = GlassIcons.ChevronRight,
                        tint = colors.inkDim,
                        size = 18.dp,
                        modifier = Modifier.rotate(arrowAngle)
                    )
                }

                AnimatedVisibility(
                    visible = introExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                ) {
                    Column {
                        GlassDivider(verticalPadding = 0.dp)
                        Spacer(Modifier.height(14.dp))
                        BasicText(
                            "把液态玻璃效果、状态栏定制与 root 命令集中在一个界面里，已接通控制中心 / 通知 / 音量 / 桌面文件夹 / Dock 栏的原生 AGSL 折射玻璃注入、LSPosed 作用域检测与安全回滚链路。",
                            style = TextStyle(
                                color = colors.inkMuted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TagPill("Kotlin", colors.accentDeep)
                            TagPill("Compose", colors.accentDeep)
                            TagPill("Liquid Glass", colors.accentDeep)
                        }
                    }
                }
            }
        }

        item {
            UpdateCheckCard(
                backdrop = backdrop,
                checking = UpdateState.checking,
                result = UpdateState.result,
                checkedAt = UpdateState.checkedAt,
                onCheckUpdate = UpdateChecker::startCheck,
                onOpenUpdateDialog = { UpdateState.showDialog = true },
                onOpenUrl = ::openUrl
            )
        }

        item { SectionTitle("运行环境") }

        item {
            GlassCard(backdrop) {
                InfoRow("机型", info?.model ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("系统", info?.colorOsVersion ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("Android", info?.let { "${it.androidVersion} · API ${it.sdk}" } ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("SELinux", info?.selinux ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow(
                    "su",
                    if (RootState.granted == true) RootState.suVersion.ifBlank { "已授权" } else "未授权",
                    valueColor = if (RootState.granted == true) colors.success else colors.inkMuted
                )
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("包名", BuildConfig.APPLICATION_ID)
            }
        }

        item { SectionTitle("界面主题") }

        item {
            GlassCard(backdrop) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    ThemeOptionRow(
                        mode = mode,
                        selected = ThemeState.mode == mode,
                        onClick = {
                            ThemeState.mode = mode
                            runCatching { AppPrefs.instance.saveTheme(mode) }
                        }
                    )
                    if (index != ThemeMode.entries.lastIndex) {
                        GlassDivider(verticalPadding = 0.dp)
                    }
                }
            }
        }

        item { SectionTitle("项目与支持") }

        item {
            GlassCard(backdrop) {
                LinkRow(
                    title = "GitHub 仓库",
                    subtitle = "github.com/hei1345hei-hub/Glass-Workshop",
                    icon = GlassIcons.Code,
                    onClick = { openUrl(C17Links.REPO_URL) }
                )
                GlassDivider(verticalPadding = 4.dp)
                LinkRow(
                    title = "使用教程",
                    subtitle = "安装、root、LSPosed 作用域与功能页说明",
                    icon = GlassIcons.Book,
                    onClick = { showTutorial = true }
                )
                GlassDivider(verticalPadding = 4.dp)
                LinkRow(
                    title = "问题反馈",
                    subtitle = "带机型、系统版本和日志提交 Issue",
                    icon = GlassIcons.Bug,
                    onClick = { openUrl(C17Links.ISSUE_URL) }
                )
                GlassDivider(verticalPadding = 4.dp)
                LinkRow(
                    title = "赞助作者",
                    subtitle = "微信扫码，支持后续维护、测试和更新",
                    icon = GlassIcons.Heart,
                    onClick = { showSponsor = true }
                )
            }
        }

        item { SectionTitle("技术与导航") }

        item {
            GlassCard(backdrop) {
                LinkRow(
                    title = "Kyant0 / AndroidLiquidGlass",
                    subtitle = "Jetpack Compose 液态玻璃折射管线与交互参考",
                    onClick = { openUrl("https://github.com/Kyant0/AndroidLiquidGlass") }
                )
                GlassDivider(verticalPadding = 4.dp)
                LinkRow(
                    title = "Jetpack Compose",
                    subtitle = "声明式 UI 与弹性动画",
                    onClick = { openUrl("https://developer.android.com/jetpack/compose") }
                )
                GlassDivider(verticalPadding = 4.dp)
                LinkRow(
                    title = "查看功能面板",
                    subtitle = "回到功能页继续调参数",
                    onClick = onOpenFeatures,
                    icon = GlassIcons.Tune
                )
            }
        }

        item { SectionTitle("说明") }

        item {
            GlassCard(backdrop) {
                Row(verticalAlignment = Alignment.Top) {
                    StatusDot(colors.danger, 6)
                    BasicText(
                        "本项目完全免费开源，禁止倒卖，若私自转售，相关后果自行承担。",
                        Modifier.padding(start = 10.dp),
                        style = TextStyle(
                            color = colors.danger,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                GlassDivider(verticalPadding = 10.dp)
                Row(verticalAlignment = Alignment.Top) {
                    StatusDot(colors.warn, 6)
                    BasicText(
                        "本工具面向个人设备的系统定制研究。修改系统界面、重启 SystemUI 或写入 /data 分区都存在风险，请先备份，并在理解命令含义后使用。",
                        Modifier.padding(start = 10.dp),
                        style = TextStyle(color = colors.inkMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    )
                }
            }
        }
    }

    if (showTutorial) {
        TutorialDialog(
            onDismiss = { showTutorial = false },
            onOpenReadme = { openUrl(C17Links.README_URL) }
        )
    }
    if (showSponsor) {
        SponsorDialog(
            onDismiss = { showSponsor = false },
            onOpenIssues = { openUrl(C17Links.ISSUE_URL) }
        )
    }
    val availableUpdate = UpdateState.result as? UpdateCheckResult.UpdateAvailable
    if (UpdateState.showDialog && availableUpdate != null) {
        UpdateAvailableDialog(
            update = availableUpdate,
            downloading = UpdateState.downloading,
            downloadProgress = UpdateState.downloadProgress,
            downloadError = UpdateState.downloadError,
            downloaded = UpdateState.downloadedApk?.exists() == true,
            onDismiss = { if (!UpdateState.downloading) UpdateState.showDialog = false },
            onDownloadAndInstall = {
                UpdateChecker.startDownloadOrInstall(context, availableUpdate.release, ::openUrl)
            },
            onOpenBrowser = { openUrl(availableUpdate.release.htmlUrl) }
        )
    }
}

@Composable
private fun UpdateCheckCard(
    backdrop: Backdrop,
    checking: Boolean,
    result: UpdateCheckResult?,
    checkedAt: String?,
    onCheckUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val colors = Glass.colors
    val badgeText: String
    val badgeColor: Color
    val statusText: String
    val statusColor: Color

    when {
        checking -> {
            badgeText = "检测中"
            badgeColor = colors.warn
            statusText = "正在连接 GitHub Releases 获取最新版本信息…"
            statusColor = colors.inkMuted
        }

        result is UpdateCheckResult.UpdateAvailable -> {
            val sizeText = result.release.apkAsset
                ?.let { UpdateChecker.formatSize(it.sizeBytes) }
                ?.takeIf { it.isNotBlank() }
                ?.let { " · $it" }
                .orEmpty()
            val dateText = result.release.publishedAt
                .takeIf { it.isNotBlank() }
                ?.let { " · $it" }
                .orEmpty()
            badgeText = "发现新版本 ${result.release.tagName}"
            badgeColor = colors.accent
            statusText = "当前 v${result.currentVersion}，可升级至 ${result.release.tagName}$sizeText$dateText。"
            statusColor = colors.ink
        }

        result is UpdateCheckResult.UpToDate -> {
            badgeText = "已是最新"
            badgeColor = colors.success
            statusText = if (result.aheadOfRemote) {
                "当前为 v${result.currentVersion}（高于 GitHub 最新版本 ${result.release.tagName}），无需更新。"
            } else {
                "当前已是最新版本 v${result.currentVersion}（与 GitHub ${result.release.tagName} 一致）。"
            }
            statusColor = colors.success
        }

        result is UpdateCheckResult.Unavailable -> {
            badgeText = "暂无发行版"
            badgeColor = colors.warn
            statusText = result.message
            statusColor = colors.inkMuted
        }

        result is UpdateCheckResult.Error -> {
            badgeText = "检查失败"
            badgeColor = colors.danger
            statusText = "${result.message}（${result.detail}）"
            statusColor = colors.danger
        }

        else -> {
            badgeText = "v${BuildConfig.VERSION_NAME}"
            badgeColor = colors.inkMuted
            statusText = "点击下方按钮从 GitHub Releases 检测最新版本与 APK 安装包。"
            statusColor = colors.inkMuted
        }
    }

    val available = result as? UpdateCheckResult.UpdateAvailable
    val buttonInteractive = !checking

    GlassCard(backdrop) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "版本更新",
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            TagPill(badgeText, badgeColor)
        }

        Spacer(Modifier.height(8.dp))

        BasicText(
            statusText,
            style = TextStyle(
                color = statusColor,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        )

        checkedAt?.let { at ->
            Spacer(Modifier.height(4.dp))
            BasicText(
                "上次检测 $at",
                style = TextStyle(
                    color = colors.inkDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        LiquidButton(
            onClick = {
                if (!checking) {
                    if (available != null) onOpenUpdateDialog() else onCheckUpdate()
                }
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            isInteractive = buttonInteractive,
            tint = if (buttonInteractive) colors.accent else colors.chipSurface
        ) {
            GlassIcon(
                icon = if (available != null) GlassIcons.Download else GlassIcons.Restore,
                tint = if (buttonInteractive) Color.White else colors.inkMuted,
                size = 16.dp
            )
            BasicText(
                when {
                    checking -> "正在检测更新…"
                    available != null -> "下载更新 ${available.release.tagName}"
                    else -> "检测更新"
                },
                style = TextStyle(
                    color = if (buttonInteractive) Color.White else colors.inkMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        "重新检测",
                        Modifier
                            .clickable(enabled = !checking, onClick = onCheckUpdate)
                            .padding(vertical = 2.dp),
                        style = TextStyle(
                            color = colors.inkMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    BasicText(
                        "前往 GitHub Releases →",
                        Modifier
                            .clickable { onOpenUrl(result.release.htmlUrl) }
                            .padding(vertical = 2.dp),
                        style = TextStyle(
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            is UpdateCheckResult.Unavailable -> {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "在浏览器中查看 GitHub Releases →",
                    Modifier
                        .clickable { onOpenUrl(result.pageUrl) }
                        .padding(vertical = 2.dp),
                    style = TextStyle(
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            is UpdateCheckResult.Error -> {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "在浏览器中打开发布页 →",
                    Modifier
                        .clickable { onOpenUrl(result.pageUrl) }
                        .padding(vertical = 2.dp),
                    style = TextStyle(
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    update: UpdateCheckResult.UpdateAvailable,
    downloading: Boolean,
    downloadProgress: Int?,
    downloadError: String?,
    downloaded: Boolean,
    onDismiss: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val colors = Glass.colors
    val release = update.release
    val asset = release.apkAsset
    val sizeText = asset?.let { UpdateChecker.formatSize(it.sizeBytes) }.orEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .heightIn(max = 620.dp)
                .background(colors.cardSurface, RoundedCornerShape(26.dp))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(GlassIcons.Download, colors.accent, 22.dp)
                BasicText(
                    "发现新版本 ${release.tagName}",
                    Modifier.padding(start = 10.dp).weight(1f),
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (!downloading) {
                    BasicText(
                        "关闭",
                        Modifier.clickable(onClick = onDismiss).padding(4.dp),
                        style = TextStyle(color = colors.inkMuted, fontSize = 12.sp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagPill("v${update.currentVersion} → ${release.tagName}", colors.accent)
                if (sizeText.isNotBlank()) {
                    TagPill(sizeText, colors.inkMuted)
                }
                if (release.publishedAt.isNotBlank()) {
                    TagPill(release.publishedAt, colors.inkMuted)
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(colors.chipSurface, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                BasicText(
                    release.notes.ifBlank {
                        "该版本未附带更新说明，可直接下载安装或点击「浏览器打开」查看详情。"
                    },
                    style = TextStyle(
                        color = colors.inkMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                )
            }

            if (downloading) {
                Spacer(Modifier.height(12.dp))
                val pct = downloadProgress
                BasicText(
                    if (pct != null) "正在下载安装包… $pct%" else "正在下载安装包…",
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(Modifier.height(6.dp))
                val fraction = ((pct ?: 25).coerceIn(0, 100)) / 100f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(colors.chipSurface)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(colors.accent)
                    )
                }
            }

            downloadError?.let { err ->
                Spacer(Modifier.height(10.dp))
                BasicText(
                    err,
                    style = TextStyle(
                        color = colors.danger,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                DialogAction("浏览器打开", false, onOpenBrowser)
                if (asset != null) {
                    Spacer(Modifier.size(8.dp))
                    val actionLabel = when {
                        downloading -> "下载中…"
                        downloaded -> "立即安装"
                        else -> "下载并安装"
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (downloading) colors.chipSurface else colors.accent)
                            .clickable(enabled = !downloading, onClick = onDownloadAndInstall)
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        BasicText(
                            actionLabel,
                            style = TextStyle(
                                color = if (downloading) colors.inkMuted else colors.onAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialDialog(
    onDismiss: () -> Unit,
    onOpenReadme: () -> Unit
) {
    val colors = Glass.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .heightIn(max = 620.dp)
                .background(colors.cardSurface, RoundedCornerShape(26.dp))
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(GlassIcons.Book, colors.accent, 22.dp)
                BasicText(
                    "使用教程",
                    Modifier.padding(start = 10.dp).weight(1f),
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                BasicText(
                    "关闭",
                    Modifier.clickable(onClick = onDismiss).padding(4.dp),
                    style = TextStyle(color = colors.inkMuted, fontSize = 12.sp)
                )
            }
            BasicText(
                "从安装到启用两个功能入口，六步完成基础配置。",
                Modifier.padding(top = 8.dp),
                style = TextStyle(color = colors.inkMuted, fontSize = 12.sp, lineHeight = 18.sp)
            )

            TutorialStep(
                1,
                "安装 APK",
                "从 Release 下载 C17-Glass-Workshop-v${BuildConfig.VERSION_NAME}.apk，允许浏览器或文件管理器安装未知来源应用。"
            )
            TutorialStep(
                2,
                "授予 root",
                "打开 KernelSU / Magisk，为「C17 玻璃工坊」授予 root。首页右上角显示 ROOT 即表示已连接。"
            )
            TutorialStep(
                3,
                "启用 LSPosed 模块",
                "在 LSPosed 里启用本模块，在推荐应用里勾选「系统界面」「系统桌面」和「Breeno 速览」（负一屏）作用域。作用域只能在 LSPosed 中申请，App 只负责检测和回显。"
            )
            TutorialStep(
                4,
                "调整状态栏",
                "进入「功能」→「状态栏」，选择 ColorOS 或 iOS 样式，打开需要的开关或拖动透明度滑杆，然后点「应用」。"
            )
            TutorialStep(
                5,
                "液态玻璃范围",
                "进入「功能」→「液态玻璃」，分别开关控制中心、通知中心、音量条、桌面文件夹、Dock 栏与负一屏，并调整折射强度、模糊半径、圆角半径与高光描边。"
            )
            TutorialStep(
                6,
                "重启并验收",
                "修改作用域或模块配置后，用关于页的「重启系统作用域」或在功能页点「重启作用域」同时重启系统界面、系统桌面与负一屏。"
            )

            Box(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .background(colors.chipSurface, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                BasicText(
                    "当前版本已完成界面、配置写入和 LSPosed 模块识别；部分状态栏参数仍需 SystemUI 侧消费，开关会先写入配置。",
                    style = TextStyle(color = colors.inkMuted, fontSize = 11.sp, lineHeight = 16.sp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                DialogAction("在线 README", false, onOpenReadme)
                Spacer(Modifier.size(8.dp))
                DialogAction("知道了", true, onDismiss)
            }
        }
    }
}

@Composable
private fun TutorialStep(number: Int, title: String, body: String) {
    val colors = Glass.colors
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                number.toString(),
                style = TextStyle(
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            BasicText(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                body,
                Modifier.padding(top = 3.dp),
                style = TextStyle(color = colors.inkMuted, fontSize = 11.sp, lineHeight = 16.sp)
            )
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = GlassIcons.Info
) {
    val colors = Glass.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIcon(icon, colors.accent, 18.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            BasicText(
                title,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            BasicText(
                subtitle,
                Modifier.padding(top = 2.dp),
                style = TextStyle(color = colors.inkDim, fontSize = 11.sp)
            )
        }
        BasicText(
            "→",
            style = TextStyle(color = colors.inkDim, fontSize = 12.sp)
        )
    }
}

@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Glass.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemeSwatch(mode)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            BasicText(
                mode.label,
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                themeDescription(mode),
                Modifier.padding(top = 2.dp),
                style = TextStyle(color = colors.inkDim, fontSize = 11.sp)
            )
        }
        ThemeSelectionDot(selected)
    }
}

@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val colors = Glass.colors
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.separator, RoundedCornerShape(12.dp))
    ) {
        when (mode) {
            ThemeMode.SYSTEM -> Row(Modifier.fillMaxSize()) {
                MiniThemePanel(dark = false, modifier = Modifier.weight(1f))
                MiniThemePanel(dark = true, modifier = Modifier.weight(1f))
            }

            ThemeMode.LIGHT -> MiniThemePanel(dark = false, modifier = Modifier.fillMaxSize())
            ThemeMode.DARK -> MiniThemePanel(dark = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MiniThemePanel(dark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .background(if (dark) Color(0xFF000000) else Color(0xFFF4F4F6)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (dark) Color(0xFF242424) else Color.White)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (dark) Color(0xFF2C2C2C) else Color(0xFFE4E6EC))
                )
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (dark) Color(0xFF5B8DFF) else Color(0xFF2F6BFF))
                )
            }
        }
    }
}

@Composable
private fun ThemeSelectionDot(selected: Boolean) {
    val colors = Glass.colors
    Box(
        Modifier
            .size(18.dp)
            .border(
                width = 1.2.dp,
                color = if (selected) colors.accent else colors.separator,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
            )
        }
    }
}

private fun themeDescription(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统深浅色"
    ThemeMode.LIGHT -> "浅灰背景 · 白色卡片"
    ThemeMode.DARK -> "纯黑背景 · 深灰卡片"
}
