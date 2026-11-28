package com.c17.glass.ui

import android.os.SystemClock
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.c17.glass.core.AppPrefs
import com.c17.glass.core.LogLevel
import com.c17.glass.core.LogRepository
import com.c17.glass.core.LsposedRepository
import com.c17.glass.core.RootState
import com.c17.glass.core.SafetyManager
import com.c17.glass.core.TweakEngine
import com.c17.glass.core.TweakState
import com.c17.glass.ui.components.GlassIcons
import com.c17.glass.ui.components.GlassIcon
import com.c17.glass.ui.components.LiquidTab
import com.c17.glass.ui.components.LiquidTabBar
import com.c17.glass.ui.screens.AboutScreen
import com.c17.glass.ui.screens.FeaturesScreen
import com.c17.glass.ui.screens.HomeScreen
import com.c17.glass.ui.screens.LogScreen
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** SystemUI 两次重启之间的最小间隔：短于该间隔的连续重启会被 LSPosed 判定为崩溃循环。 */
private const val SYSTEM_UI_RESTART_COOLDOWN_MS = 90_000L

private data class TabSpec(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val Tabs = listOf(
    TabSpec("首页", GlassIcons.Home),
    TabSpec("功能", GlassIcons.Tune),
    TabSpec("日志", GlassIcons.Log),
    TabSpec("关于", GlassIcons.Info)
)

@Composable
fun GlassApp() {
    val colors = Glass.colors
    val scope = rememberCoroutineScope()
    // 背景层：静态渐变，供卡片折射，成本最低。
    val backdrop = rememberLayerBackdrop()
    // 内容层：单独录 Pager，供底部导航整条玻璃采样。
    // 导航栏必须画在这个录制节点之外，否则会再次形成 DisplayList 自引用。
    val contentBackdrop = rememberLayerBackdrop()
    // 导航同时吃到背景 + 页面内容，滚动时能透过玻璃看到下面的卡片和文字。
    val navBackdrop = rememberCombinedBackdrop(backdrop, contentBackdrop)

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { Tabs.size })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isTabClicking by remember { mutableStateOf(false) }
    var scrollToken by remember { mutableIntStateOf(0) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var preloadPages by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        delay(250L)
        preloadPages = 2.coerceAtMost(Tabs.lastIndex)
        delay(300L)
        preloadPages = Tabs.lastIndex
    }

    fun switchTab(targetIndex: Int) {
        val target = targetIndex.coerceIn(0, Tabs.lastIndex)
        selectedIndex = target
        if (pagerState.currentPage == target && !pagerState.isScrollInProgress) return
        val myToken = ++scrollToken
        isTabClicking = true
        scrollJob?.cancel()
        scrollJob = scope.launch {
            try {
                pagerState.animateScrollToPage(
                    page = target,
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f)
                )
            } finally {
                if (scrollToken == myToken) {
                    isTabClicking = false
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (!isTabClicking) {
                selectedIndex = page
            }
        }
    }

    LaunchedEffect(Unit) {
        if (LogRepository.entries.isEmpty()) {
            LogRepository.log(
                LogLevel.INFO,
                "C17 玻璃工坊已启动",
                "ColorOS 17 · Kotlin + Compose · 液态玻璃框架"
            )
        }
        RootState.refresh()
        if (RootState.granted == true) {
            LogRepository.log(LogLevel.SUCCESS, "root 已授权", RootState.suVersion.ifBlank { "su" })
        } else {
            LogRepository.log(
                LogLevel.WARN,
                "未检测到 root",
                RootState.lastError ?: "root 管理器未授权本应用，功能仅可预览"
            )
        }
        SafetyManager.bootstrap().let { needWatch ->
            if (needWatch) SafetyManager.watchAfterApply()
        }
    }

    /** 写入配置：参数默认热重载；仅模块更新或首次应用时重启两个作用域，并带冷却防 LSPosed 安全模式。 */
    fun restartScopesNow() {
        if (TweakState.applying) return
        scope.launch {
            TweakState.applying = true
            TweakState.scopeActionTip = null
            val before = runCatching { AppPrefs.instance.loadConfig() }.getOrNull()
            val config = TweakState.toConfig()
            val changed = config.diffLabels(before)
            runCatching { AppPrefs.instance.saveConfig(config) }
            val ctx = AppPrefs.instance.appContext
            val loadedSysUi = LsposedRepository.isModuleLoadedIn(ctx, "com.android.systemui")
            val loadedLauncher = LsposedRepository.isModuleLoadedIn(ctx, "com.android.launcher")
            val ok = TweakEngine.apply(config, before, changed)
            if (ok) {
                val notLoaded = loadedSysUi == false
                val needsSystemUi = TweakState.pendingSystemUiRestart || before == null || notLoaded
                val now = SystemClock.elapsedRealtime()
                val remain = SYSTEM_UI_RESTART_COOLDOWN_MS - (now - TweakState.lastSystemUiKillAt)
                when {
                    !needsSystemUi -> {
                        if (loadedLauncher == false && !TweakState.lastApplyRestartedLauncher) {
                            LsposedRepository.restartScopes(listOf("com.android.launcher"))
                        }
                        TweakState.scopeActionTip = "配置已写入并热重载，无需重启系统界面"
                        LogRepository.log(
                            LogLevel.SUCCESS,
                            "配置热重载",
                            "参数实时生效；桌面按需重启，未重启 SystemUI"
                        )
                    }
                    remain > 0 -> {
                        val secs = remain / 1000 + 1
                        TweakState.scopeActionTip = "距上次重启系统界面不足 90 秒，本次跳过重启以避免 LSPosed 安全模式，请 ${secs}s 后再试"
                        LogRepository.log(
                            LogLevel.WARN,
                            "跳过 SystemUI 重启",
                            "冷却期未过（剩余 ${secs}s），配置已写入；频繁重启会被 LSPosed 判定为崩溃循环"
                        )
                    }
                    else -> {
                        val result = LsposedRepository.restartSystemUi()
                        TweakState.lastSystemUiKillAt = SystemClock.elapsedRealtime()
                        if (result.code == 0) {
                            TweakState.scopeActionTip = if (notLoaded) {
                                "检测到系统界面进程未加载模块（安全模式恢复或作用域刚勾选），已强制重启系统界面与桌面"
                            } else {
                                "系统界面与桌面已重启，请等待重新载入"
                            }
                            LogRepository.log(
                                LogLevel.SUCCESS,
                                "重启作用域",
                                if (notLoaded) {
                                    "SystemUI 进程未加载模块 APK，已强制重启两个作用域完成注入"
                                } else {
                                    "已同时重启系统界面与系统桌面，等待两个作用域重新加载"
                                }
                            )
                        } else {
                            TweakState.scopeActionTip = result.output.ifBlank { "重启失败" }
                            LogRepository.log(LogLevel.ERROR, "重启作用域失败", result.output)
                            launch { SafetyManager.watchAfterApply() }
                        }
                    }
                }
            }
            TweakState.applying = false
        }
    }

    fun restoreNow() {
        if (TweakState.applying) return
        scope.launch {
            TweakState.applying = true
            TweakState.reset()
            val config = TweakState.toConfig()
            runCatching { AppPrefs.instance.saveConfig(config) }
            TweakEngine.restoreDefaults()
            TweakState.applying = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgTop)
    ) {
        // 只把背景录进 backdrop。玻璃组件必须画在 layerBackdrop 之外，
        // 否则组件会把自己的绘制结果当作折射源，形成 RenderNode 自引用并导致 hwui 递归崩溃。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            GlassBackground()
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(contentBackdrop),
            userScrollEnabled = true,
            key = { it },
            beyondViewportPageCount = preloadPages
        ) { page ->
            Box(Modifier.fillMaxSize().graphicsLayer()) {
                when (page) {
                    0 -> HomeScreen(backdrop = backdrop)

                    1 -> FeaturesScreen(
                        backdrop = backdrop,
                        onApply = ::restartScopesNow,
                        onRestore = ::restoreNow
                    )

                    2 -> LogScreen(backdrop = backdrop)
                    3 -> AboutScreen(backdrop = backdrop, onOpenFeatures = { switchTab(1) })
                }
            }
        }

        LiquidTabBar(
            selectedTabIndex = { selectedIndex },
            onTabSelected = { switchTab(it) },
            backdrop = navBackdrop,
            tabsCount = Tabs.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Tabs.forEachIndexed { index, tab ->
                LiquidTab(
                    index = index,
                    onClick = { switchTab(index) }
                ) { fraction ->
                    val tintProducer = remember(colors.inkMuted, colors.accent, fraction) {
                        ColorProducer { lerp(colors.inkMuted, colors.accent, fraction()) }
                    }
                    GlassIcon(
                        icon = tab.icon,
                        tint = { tintProducer() },
                        size = 21.dp
                    )
                    BasicText(
                        tab.label,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tintProducer
                    )
                }
            }
        }
    }
}
