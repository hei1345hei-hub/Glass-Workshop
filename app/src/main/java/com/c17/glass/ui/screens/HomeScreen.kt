package com.c17.glass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.core.DeviceInfo
import com.c17.glass.core.DeviceInfoLoader
import com.c17.glass.core.LsposedRepository
import com.c17.glass.core.RootState
import com.c17.glass.ui.components.C17Links
import com.c17.glass.ui.components.GlassCard
import com.c17.glass.ui.components.GlassDivider
import com.c17.glass.ui.components.HomeOpenSourceSponsorCard
import com.c17.glass.ui.components.InfoRow
import com.c17.glass.ui.components.LsposedCard
import com.c17.glass.ui.components.RootActivationHero
import com.c17.glass.ui.components.SponsorDialog
import com.c17.glass.ui.components.StatusDot
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    backdrop: Backdrop
) {
    val colors = Glass.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var lsposedActive by remember { mutableStateOf<Boolean?>(null) }
    var lsposedRefreshKey by remember { mutableIntStateOf(0) }
    var showSponsor by remember { mutableStateOf(false) }

    fun reloadDevice() {
        scope.launch {
            loading = true
            RootState.refresh()
            info = runCatching { DeviceInfoLoader.load() }.getOrNull()
            lsposedActive = runCatching { LsposedRepository.checkActive(context) }.getOrNull()
            loading = false
        }
    }

    fun refreshLsposed() {
        lsposedActive = null
        lsposedRefreshKey += 1
        scope.launch {
            lsposedActive = runCatching { LsposedRepository.checkActive(context) }.getOrNull()
        }
    }

    fun reloadAll() {
        reloadDevice()
        refreshLsposed()
    }

    LaunchedEffect(Unit) { reloadDevice() }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = statusBarTop + 30.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "玻璃工坊",
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(
                        color = when (RootState.granted) {
                            true -> colors.success
                            false -> colors.danger
                            null -> colors.warn
                        }
                    )
                    BasicText(
                        text = when (RootState.granted) {
                            true -> "ROOT"
                            false -> "未授权"
                            null -> "检测中"
                        },
                        style = TextStyle(
                            color = colors.inkMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }

        item {
            RootActivationHero(
                deviceModel = info?.model ?: "—",
                lsposedActive = lsposedActive,
                onRefresh = ::reloadAll,
                onRefreshLsposed = ::refreshLsposed
            )
        }

        item {
            GlassCard(backdrop) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        "设备状态",
                        style = TextStyle(
                            color = colors.ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    BasicText(
                        if (loading) "读取中…" else "刷新",
                        Modifier
                            .clickable(enabled = !loading) { reloadAll() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = TextStyle(
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                Spacer(Modifier.height(4.dp))
                InfoRow("机型", info?.model ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("系统", info?.colorOsVersion ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("Android", info?.let { "${it.androidVersion} · API ${it.sdk}" } ?: "—")
                GlassDivider(verticalPadding = 0.dp)
                InfoRow("SELinux", info?.selinux ?: "—")
            }
        }

        item {
            LsposedCard(
                backdrop = backdrop,
                refreshKey = lsposedRefreshKey,
                onStatusChanged = { lsposedActive = it?.active }
            )
        }

        item {
            HomeOpenSourceSponsorCard(
                backdrop = backdrop,
                onOpenRepo = { C17Links.openUrl(context, C17Links.REPO_URL) },
                onOpenSponsor = { showSponsor = true }
            )
        }
    }

    if (showSponsor) {
        SponsorDialog(
            onDismiss = { showSponsor = false },
            onOpenIssues = { C17Links.openUrl(context, C17Links.ISSUE_URL) }
        )
    }
}
