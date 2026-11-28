package com.c17.glass.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.core.LogLevel
import com.c17.glass.core.LogRepository
import com.c17.glass.core.LsposedInfo
import com.c17.glass.core.LsposedRepository
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

@Composable
fun LsposedCard(
    backdrop: Backdrop,
    refreshKey: Int = 0,
    onStatusChanged: ((LsposedInfo?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = Glass.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<LsposedInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var restarting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun loadInfo() {
        loading = true
        message = null
        val next = runCatching { LsposedRepository.load(context) }.getOrNull()
        info = next
        loading = false
        if (next != null) {
            onStatusChanged?.invoke(next)
        }
    }

    fun reload() {
        scope.launch { loadInfo() }
    }

    LaunchedEffect(refreshKey) {
        loadInfo()
    }

    val statusText = when {
        loading -> "检测中"
        info?.active == true -> "已激活"
        info?.installed == true -> "已安装"
        else -> "未安装"
    }
    val statusColor = when {
        loading -> colors.warn
        info?.active == true -> colors.success
        info?.installed == true -> colors.warn
        else -> colors.inkDim
    }
    val current = info
    val c17Module = current?.c17Module
    val systemScopeGranted = current?.systemScopeGranted == true
    val launcherScopeGranted = current?.launcherScopeGranted == true
    val allScopesGranted = systemScopeGranted && launcherScopeGranted
    val scopeStatusText = when {
        allScopesGranted -> "已申请 · 系统界面 + 系统桌面"
        systemScopeGranted -> "系统界面已勾选（缺系统桌面）"
        launcherScopeGranted -> "系统桌面已勾选（缺系统界面）"
        else -> "未申请"
    }
    val canRestart = !loading && !restarting && current?.installed == true

    GlassCard(backdrop, modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "LSPosed",
                    style = TextStyle(
                        color = colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(statusColor, 6)
                    BasicText(
                        statusText,
                        style = TextStyle(color = statusColor, fontSize = 10.sp)
                    )
                }
            }
            BasicText(
                if (loading) "检测中…" else "刷新",
                Modifier
                    .clickable(enabled = !loading) { reload() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Spacer(Modifier.height(2.dp))

        if (loading && current == null) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                "正在读取 LSPosed 状态…",
                style = TextStyle(color = colors.inkMuted, fontSize = 12.sp)
            )
        } else if (current == null || !current.installed) {
            InfoRow("状态", "未检测到 LSPosed")
            BasicText(
                "安装并激活 LSPosed 后，可在这里检测 C17 模块和系统作用域。",
                style = TextStyle(color = colors.inkDim, fontSize = 11.sp, lineHeight = 15.sp)
            )
        } else {
            InfoRow("版本", current.version ?: "已安装")
            GlassDivider(verticalPadding = 0.dp)
            InfoRow(
                "C17 模块",
                when {
                    c17Module == null -> "未检测到"
                    c17Module.enabled -> "${c17Module.label} · 已启用"
                    else -> "${c17Module.label} · 未启用"
                },
                valueColor = if (c17Module?.enabled == true) colors.success else colors.warn
            )
            GlassDivider(verticalPadding = 0.dp)
            InfoRow(
                "系统作用域",
                scopeStatusText,
                valueColor = if (allScopesGranted) colors.success else colors.warn
            )

            Spacer(Modifier.height(6.dp))
            BasicText(
                "先在 LSPosed 里启用「C17 玻璃工坊」，并在推荐应用里勾选「系统界面」和「系统桌面」；本页只检测状态，不修改 LSPosed 配置。",
                style = TextStyle(color = colors.inkDim, fontSize = 11.sp, lineHeight = 15.sp)
            )

            Spacer(Modifier.height(10.dp))
            LiquidButton(
                onClick = {
                    if (canRestart) {
                        scope.launch {
                            restarting = true
                            val result = LsposedRepository.restartSystemUi()
                            restarting = false
                            if (result.code == 0) {
                                message = "系统界面与系统桌面已重启"
                                LogRepository.log(
                                    LogLevel.SUCCESS,
                                    "重启系统作用域",
                                    "已重启 com.android.systemui 与 com.android.launcher，等待系统重新拉起"
                                )
                            } else {
                                message = result.output.ifBlank { "重启失败" }
                                LogRepository.log(
                                    LogLevel.ERROR,
                                    "重启系统作用域失败",
                                    result.output
                                )
                            }
                        }
                    }
                },
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                isInteractive = canRestart,
                tint = if (canRestart) colors.accent else colors.chipSurface
            ) {
                GlassIcon(
                    GlassIcons.Restore,
                    if (canRestart) Color.White else colors.inkMuted,
                    16.dp
                )
                BasicText(
                    if (restarting) "重启中…" else "重启系统作用域",
                    style = TextStyle(
                        color = if (canRestart) Color.White else colors.inkMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            message?.let {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    it,
                    style = TextStyle(color = colors.inkMuted, fontSize = 10.sp)
                )
            }
        }
    }
}
