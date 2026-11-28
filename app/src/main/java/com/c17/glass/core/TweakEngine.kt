package com.c17.glass.core

import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ColorOS 17 相关命令的集中出口。
 *
 * 说明：状态栏与系统材质在 ColorOS 上大多由 SystemUI 的 overlay / 主题配置驱动，
 * 与 AOSP 的 secure 设置并不完全一致。这里把已接入的少量命令与待适配项分开，
 * 后续只需替换本文件里的实现，不影响 UI 与日志层。
 */
object C17Commands {

    const val CONFIG_DIR = "/data/local/tmp/c17_glass"
    const val CONFIG_PATH = "$CONFIG_DIR/config.json"
    const val DISABLED_PATH = "$CONFIG_DIR/disabled"
    const val RESTORED_PATH = "$CONFIG_DIR/restored"
    const val GLOBAL_CONFIG_KEY = "c17_glass_json"
    const val GLOBAL_DISABLED_KEY = "c17_glass_disabled"

    /**
     * 写配置。
     *
     * chmod 0777 是给 SystemUI 用的：SystemUI 以 system uid 运行，只有目录世界可写，
     * 模块才能把 hook.log 追加到同一目录（config.json 本身仍由 root 写、0644 全局可读）。
     */
    fun writeConfig(json: String): String {
        val compactJson = runCatching { org.json.JSONObject(json).toString() }.getOrDefault(json)
        val escapedGlobal = "'" + compactJson.replace("'", "'\\''") + "'"
        return "mkdir -p $CONFIG_DIR && chmod 0777 $CONFIG_DIR && " +
            "rm -f $DISABLED_PATH $RESTORED_PATH && " +
            "settings put global $GLOBAL_DISABLED_KEY 0 >/dev/null 2>&1 || true; " +
            "settings put global $GLOBAL_CONFIG_KEY $escapedGlobal >/dev/null 2>&1 || true; " +
            "cat > $CONFIG_PATH <<'EOF'\n$json\nEOF\n" +
            "chmod 0644 $CONFIG_PATH"
    }

    /** 只按 PID 结束 SystemUI，不能用 pkill -f（会匹配到执行命令的 root shell 自身）。 */
    const val RESTART_SYSTEMUI =
        "for p in \$(pidof com.android.systemui com.coloros.systemui 2>/dev/null); do " +
            "kill -TERM \"\$p\" 2>/dev/null; done"

    /** 重启系统桌面使桌面文件夹与 Dock 栏 Hook 冷启动生效，或切换作用域后刷新。 */
    const val RESTART_LAUNCHER =
        "for p in \$(pidof com.android.launcher 2>/dev/null); do " +
            "kill -TERM \"\$p\" 2>/dev/null; done"
}

data class TweakStep(
    val label: String,
    val command: String?,
    val note: String = ""
)

object TweakEngine {

    private val stampFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 当前版本只下发液态玻璃；状态栏开发中，不生成或执行任何状态栏设置命令。 */
    fun plan(config: TweakConfig): List<TweakStep> = listOf(
        TweakStep(
            label = "控制中心液态玻璃",
            command = null,
            note = if (config.glassControlCenter) "保留系统材质并注入控制中心 AGSL 折射" else "控制中心保持原生背景"
        ),
        TweakStep(
            label = "通知中心液态玻璃",
            command = null,
            note = if (config.glassNotifications) "由模块注入通知卡片液态玻璃" else "通知中心保持原生背景"
        ),
        TweakStep(
            label = "音量条液态玻璃",
            command = null,
            note = if (config.glassVolume) "独立调节（模糊 ${config.volumeBlurRadius.toInt()}dp / 折射 ${(config.volumeRefraction * 100).toInt()}%）" else "音量条保持原生背景"
        ),
        TweakStep(
            label = "桌面文件夹与 Dock 栏",
            command = null,
            note = "文件夹=${config.glassFolders}, Dock=${config.glassDock}, 全局=${config.applyGlobally}（含消息弹窗与长按应用浮窗）"
        ),
        TweakStep(
            label = "控制/通知参数",
            command = null,
            note = "折射 ${(config.refraction * 100).toInt()}% · 模糊 ${config.blurRadius.toInt()}dp · 圆角 ${config.cornerRadius.toInt()}dp"
        ),
        TweakStep(
            label = "桌面/Dock 参数",
            command = null,
            note = "折射 ${(config.launcherRefraction * 100).toInt()}% · 模糊 ${config.launcherBlurRadius.toInt()}dp · 圆角 ${config.launcherCornerRadius.toInt()}dp"
        )
    )

    suspend fun apply(
        config: TweakConfig,
        before: TweakConfig? = null,
        changed: List<String> = emptyList(),
        restartLauncher: Boolean = true
    ): Boolean {
        LogRepository.log(
            LogLevel.CMD,
            "开始应用配置",
            "改动=${changed.joinToString("、").ifBlank { "全部参数" }}"
        )

        if (RootState.granted != true) {
            RootState.refresh()
        }
        if (RootState.granted != true) {
            LogRepository.log(
                LogLevel.ERROR,
                "未获得 root 权限",
                RootState.lastError ?: "请先在 root 管理器中授权本应用"
            )
            return false
        }

        if (!SafetyManager.beginApply(before, changed)) {
            LogRepository.log(
                LogLevel.ERROR,
                "已阻止应用",
                "安全备份未建立成功，未修改任何系统设置"
            )
            return false
        }

        var successCount = 0
        var pendingCount = 0
        var failedCount = 0

        plan(config).forEach { step ->
            if (step.command == null) {
                pendingCount++
                LogRepository.log(LogLevel.WARN, "交由模块处理：${step.label}", step.note)
            } else {
                LogRepository.log(LogLevel.CMD, "执行：${step.label}", step.command)
                val result = RootShell.exec(step.command)
                if (result.ok) {
                    successCount++
                    LogRepository.log(
                        LogLevel.SUCCESS,
                        "完成：${step.label}",
                        result.output.ifBlank { step.note }
                    )
                } else {
                    failedCount++
                    LogRepository.log(
                        LogLevel.ERROR,
                        "失败：${step.label}",
                        "exit=${result.code} ${result.output}"
                    )
                }
            }
        }

        val hadActiveConfigFile = RootShell.exec(
            "test -s ${C17Commands.CONFIG_PATH} && test ! -e ${C17Commands.DISABLED_PATH} && echo yes",
            3_000L
        ).stdout.contains("yes")
        val appUpdatedSinceApply = runCatching {
            AppPrefs.instance.needsProcessRestartForAppUpdate()
        }.getOrDefault(false)

        val json = config.toJson()
        val writeResult = RootShell.exec(C17Commands.writeConfig(json))
        if (writeResult.ok) {
            LogRepository.log(LogLevel.SUCCESS, "配置已写入", C17Commands.CONFIG_PATH)
        } else {
            failedCount++
            LogRepository.log(LogLevel.ERROR, "配置写入失败", writeResult.output)
        }

        val oldPid = RootShell.exec(
            "pidof com.android.systemui 2>/dev/null || pidof com.coloros.systemui 2>/dev/null",
            4_000L
        ).stdout.trim().split(' ').firstOrNull()

        val prevLauncherGlassEnabled = before != null && (before.glassFolders || before.glassDock || before.applyGlobally)
        val nextLauncherGlassEnabled = config.glassFolders || config.glassDock || config.applyGlobally
        if (restartLauncher && writeResult.ok && (
                !hadActiveConfigFile ||
                    before == null ||
                    changed.isEmpty() ||
                    "文件夹玻璃" in changed ||
                    "Dock 栏玻璃" in changed ||
                    "全局生效" in changed ||
                    (!prevLauncherGlassEnabled && nextLauncherGlassEnabled)
                )
        ) {
            RootShell.exec(C17Commands.RESTART_LAUNCHER, 8_000L)
        }

        val systemUiAlive = if (!writeResult.ok) {
            false
        } else if (appUpdatedSinceApply) {
            TweakState.pendingSystemUiRestart = true
            LogRepository.log(
                LogLevel.INFO,
                "模块已更新，需重启作用域加载新版 Hook",
                "配置已写入（系统桌面已按需重启）；为防止连续自动强杀触发 LSPosed 安全模式，请点击「重启作用域」或手动重启"
            )
            true
        } else {
            LogRepository.log(
                LogLevel.INFO,
                "配置已同步（SystemUI 参数热重载）",
                "SystemUI 不主动重启（避免触发 LSPosed 安全模式）；配置已写入，作用域重启由调用方统一执行"
            )
            true
        }
        if (failedCount > 0 || !systemUiAlive) {
            LogRepository.log(
                LogLevel.ERROR,
                "应用失败，自动回滚",
                if (!systemUiAlive) "SystemUI 未在预期时间内恢复" else "有 $failedCount 个执行项失败"
            )
            SafetyManager.restoreAll(
                if (!systemUiAlive) "SystemUI 未恢复，自动回滚" else "执行项失败，自动回滚"
            )
            return false
        }

        // 不立刻确认：交给 90 秒崩溃守护窗口，稳定后才撤掉 pending；
        // 若模块 APK 刚更新且尚未手动重启系统界面，保留待重启提示，等重启完成后再更新 stamp。
        TweakState.lastAppliedAt = stampFormat.format(Date())
        LogRepository.log(
            LogLevel.INFO,
            "应用结束",
            "执行成功 $successCount 项 · 待适配 $pendingCount 项 · " +
                "${SafetyManager.WATCH_WINDOW_MS / 1000} 秒崩溃守护已开始"
        )
        delay(120)
        return true
    }

    private suspend fun waitForSystemUi(oldPid: String? = null): Boolean {
        val excludeCheck = if (!oldPid.isNullOrBlank()) {
            " && [ \"\$NEW_PID\" != '$oldPid' ]"
        } else {
            ""
        }
        val result = RootShell.exec(
            "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do " +
                "sleep 1; " +
                "NEW_PID=\$(pidof com.android.systemui 2>/dev/null || pidof com.coloros.systemui 2>/dev/null); " +
                "NEW_PID=\${NEW_PID%% *}; " +
                "if [ -n \"\$NEW_PID\" ]$excludeCheck; then " +
                "echo yes; exit 0; fi; done; echo no; exit 1",
            22_000L
        )
        return result.stdout.lines().any { it.trim() == "yes" }
    }

    suspend fun restoreDefaults(): Boolean {
        LogRepository.log(LogLevel.INFO, "还原到安全基线")
        return SafetyManager.restoreAll("用户点击全部还原")
    }

    private val HOT_RELOAD_GLASS_LABELS = setOf(
        "控制中心玻璃",
        "通知中心玻璃",
        "音量条玻璃",
        "文件夹玻璃",
        "Dock 栏玻璃",
        "全局生效",
        "控制/通知折射",
        "控制/通知模糊",
        "控制/通知高光",
        "控制/通知圆角",
        "桌面/Dock 折射",
        "桌面/Dock 模糊",
        "桌面/Dock 高光",
        "桌面/Dock 圆角",
        "音量条折射",
        "音量条模糊",
        "音量条高光",
        "音量条圆角"
    )
}
