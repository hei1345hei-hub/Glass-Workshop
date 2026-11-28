package com.c17.glass.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 改动前的安全基线。
 *
 * 说明：
 * - 基线只建立一次。后续多次「应用」都回滚到第一次应用之前的状态，而不是上一次应用后的状态。
 * - 当前覆盖：secure.clock_seconds；config.json 作为 App 生成文件，还原时删除。
 * - 以后新增文件改动时，先调用 [SafetyManager.backupFile] 再把文件写进去。
 * - 备份与恢复脚本放在 /data/adb 下，App 打不开时仍可由 root 终端执行 restore.sh。
 */
object SafetyState {
    var ready by mutableStateOf(false)
    var pending by mutableStateOf(false)
    var restoring by mutableStateOf(false)
    var lastBackupAt by mutableStateOf<String?>(null)
    var lastMessage by mutableStateOf<String?>(null)

    /** 崩溃守护：正在 90 秒窗口内盯着 SystemUI。 */
    var watching by mutableStateOf(false)

    /** 开机守护脚本是否已写入。 */
    var guardInstalled by mutableStateOf(false)

    /** 最近一次自动回滚。 */
    var lastAutoRevertAt by mutableStateOf<String?>(null)
    var lastAutoRevertKeys by mutableStateOf<String?>(null)
    var lastAutoRevertReason by mutableStateOf<String?>(null)
}

object SafetyManager {

    private const val SAFETY_DIR = "/data/adb/c17_glass_safety"
    private const val ORIGINALS_DIR = "$SAFETY_DIR/originals"
    private const val FILES_DIR = "$ORIGINALS_DIR/files"
    private const val RECORDS_PATH = "$SAFETY_DIR/records.tsv"
    private const val SCRIPT_PATH = "$SAFETY_DIR/restore.sh"
    private const val PENDING_PATH = "$SAFETY_DIR/pending"
    private const val PENDING_META = "$SAFETY_DIR/pending_meta"
    private const val BEFORE_PATH = "$SAFETY_DIR/before.json"
    private const val AUTO_LOG_PATH = "$SAFETY_DIR/auto_revert.tsv"
    private const val BOOT_REVERTED_PATH = "$SAFETY_DIR/boot_reverted"
    private const val CREATED_AT_PATH = "$SAFETY_DIR/created_at"
    private const val VERSION_PATH = "$SAFETY_DIR/baseline_version"
    private const val BASELINE_VERSION = "3"
    private const val MIRROR_SCRIPT = "/data/local/tmp/c17_glass/restore.sh"
    private const val RESTORED_MARKER = "/data/local/tmp/c17_glass/restored"
    private const val GUARD_SCRIPT = "/data/adb/service.d/c17_safety_guard.sh"
    private const val GUARD_MIRROR = "/data/local/tmp/c17_glass/guard.sh"
    private const val SERVICE_D = "/data/adb/service.d"
    private const val CONFIG_PATH = C17Commands.CONFIG_PATH

    /** 应用后盯多久；窗口内 SystemUI 崩溃 / 重启就回滚。 */
    const val WATCH_WINDOW_MS = 90_000L
    private const val WATCH_POLL_MS = 2_500L

    @Volatile
    private var watchGeneration = 0

    private data class FileRecord(
        val target: String,
        val backup: String,
        val existed: Boolean
    )

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun hashPath(path: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private suspend fun exists(path: String): Boolean =
        RootShell.exec("test -e ${quote(path)} && echo yes", 8_000L)
            .stdout.lines().any { it.trim() == "yes" }

    private suspend fun readText(path: String): String =
        RootShell.exec("cat ${quote(path)} 2>/dev/null", 8_000L).stdout.trim()

    private suspend fun writeText(path: String, content: String): Boolean {
        val parent = path.substringBeforeLast('/', "")
        val marker = "C17_SAFETY_EOF"
        val command = buildString {
            if (parent.isNotBlank()) appendLine("mkdir -p ${quote(parent)}")
            appendLine("cat > ${quote(path)} <<'$marker'")
            appendLine(content)
            appendLine(marker)
        }
        return RootShell.exec(command, 12_000L).ok
    }

    private suspend fun loadRecords(): List<FileRecord> {
        val text = readText(RECORDS_PATH)
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            FileRecord(
                target = parts[0],
                backup = parts[1],
                existed = parts[2] == "1"
            )
        }.toList()
    }

    /**
     * 首次建立安全基线。已有基线时直接返回 true，不覆盖最初状态。
     */
    suspend fun ensureBaseline(): Boolean {
        if (RootState.granted != true) {
            SafetyState.ready = false
            return false
        }
        if (exists(SCRIPT_PATH)) {
            if (readText(VERSION_PATH) == BASELINE_VERSION) {
                // 每次启动都按最新格式重写脚本，保证文件登记和恢复逻辑同步。
                if (!writeRestoreScript(loadRecords())) {
                    SafetyState.ready = false
                    return false
                }
                refreshStatus()
                return true
            }
            // 旧基线版本：重建，避免把旧版本留下的 config.json 当成用户原始文件。
            removeBaselineFiles()
        }

        val setup = RootShell.exec(
            "mkdir -p ${quote(ORIGINALS_DIR)} ${quote(FILES_DIR)}",
            10_000L
        )
        if (!setup.ok) {
            LogRepository.log(LogLevel.ERROR, "安全基线建立失败", setup.output)
            SafetyState.ready = false
            return false
        }

        val clockResult = RootShell.exec("settings get secure clock_seconds", 10_000L)
        if (!clockResult.ok) {
            LogRepository.log(
                LogLevel.ERROR,
                "安全基线建立失败",
                "无法读取 secure.clock_seconds：${clockResult.output}"
            )
            SafetyState.ready = false
            return false
        }
        val rawClock = clockResult.stdout.trim()
        val clock = if (rawClock.isBlank() || rawClock == "null") "__ABSENT__" else rawClock

        val writes = listOf(
            "printf '%s' ${quote(clock)} > ${quote("$ORIGINALS_DIR/clock_seconds")}",
            "printf '%s' ${quote(stamp())} > ${quote(CREATED_AT_PATH)}",
            "printf '%s' ${quote(BASELINE_VERSION)} > ${quote(VERSION_PATH)}",
            ": > ${quote(RECORDS_PATH)}"
        )
        val writeResult = RootShell.exec(writes.joinToString("\n"), 10_000L)
        if (!writeResult.ok) {
            LogRepository.log(LogLevel.ERROR, "安全基线建立失败", writeResult.output)
            SafetyState.ready = false
            return false
        }

        if (!writeRestoreScript(emptyList())) {
            SafetyState.ready = false
            return false
        }

        SafetyState.ready = true
        SafetyState.pending = false
        SafetyState.lastBackupAt = stamp()
        SafetyState.lastMessage = null
        LogRepository.log(
            LogLevel.SUCCESS,
            "安全基线已建立",
            "时钟 ${if (clock == "__ABSENT__") "未设置" else clock} · " +
                "config.json 将在还原时删除 · $SCRIPT_PATH"
        )
        return true
    }

    /**
     * 每次真正改系统前调用：
     * 1. 先把「上一份已确认的配置」存成快照（崩溃时按它回退）；
     * 2. 写 pending + pending_meta（改动项、boot_id、SystemUI pid、时间）。
     * 应用结束后由调用方启动 [watchAfterApply] 守 90 秒。
     */
    suspend fun beginApply(
        before: TweakConfig? = null,
        changed: List<String> = emptyList()
    ): Boolean {
        if (RootState.granted != true) {
            return false
        }
        if (exists(PENDING_PATH)) {
            val meta = readMeta()
            val crashes = scanSystemCrashes(meta["ts"]?.toLongOrNull() ?: 0L)
            val sameBoot = meta["boot"] == readBootId()
            if (crashes.isNotEmpty() || !sameBoot) {
                LogRepository.log(LogLevel.WARN, "检测到未确认的上次应用", "先自动回滚再继续")
                if (crashes.isNotEmpty()) {
                    autoRevertPending("上一次改动未确认就出现系统崩溃", crashes.joinToString("；"))
                } else if (!restoreAll("上次应用未确认，自动回滚")) {
                    return false
                }
            } else {
                LogRepository.log(LogLevel.INFO, "上一次改动已稳定", "清除旧的守护标记")
                clearPending()
            }
        }
        if (!ensureBaseline()) {
            LogRepository.log(
                LogLevel.ERROR,
                "已阻止应用",
                "安全备份没有建立成功，不能修改系统"
            )
            return false
        }

        if (before != null) {
            val json = before.toJson()
            runCatching { AppPrefs.instance.saveGuardSnapshot(json) }
            writeText(BEFORE_PATH, json)
        }

        val meta = buildString {
            appendLine("ts=${System.currentTimeMillis() / 1000}")
            appendLine("stamp=${stamp()}")
            appendLine("boot=${readBootId()}")
            appendLine("keys=${changed.joinToString("、").ifBlank { "全部参数" }}")
            appendLine("systemui=${systemUiPid() ?: -1}")
        }
        val result = RootShell.exec(
            "printf '%s' ${quote(stamp())} > ${quote(PENDING_PATH)}",
            8_000L
        )
        if (!result.ok) {
            SafetyState.pending = false
            LogRepository.log(LogLevel.ERROR, "守护标记写入失败", result.output)
            return false
        }
        writeText(PENDING_META, meta)
        SafetyState.pending = true
        LogRepository.log(
            LogLevel.INFO,
            "崩溃守护已武装",
            "改动：${changed.joinToString("、").ifBlank { "全部参数" }} · " +
                "${WATCH_WINDOW_MS / 1000} 秒内 SystemUI 崩溃/重启会自动关闭"
        )
        return true
    }

    private suspend fun clearPending() {
        RootShell.exec("rm -f ${quote(PENDING_PATH)} ${quote(PENDING_META)}", 8_000L)
        SafetyState.pending = false
    }

    /** 用户主动手动重启 SystemUI 前解除 90 秒崩溃看门狗，避免误判崩溃引发二次强杀。 */
    suspend fun cancelWatchForManualRestart() {
        watchGeneration++
        SafetyState.watching = false
        clearPending()
    }

    /** 窗口内没有崩溃：确认稳定，撤掉 pending（开机守护就不再动它）。 */
    suspend fun markStable() {
        if (!exists(PENDING_PATH)) return
        clearPending()
        LogRepository.log(
            LogLevel.SUCCESS,
            "崩溃守护确认",
            "改动稳定 ${WATCH_WINDOW_MS / 1000} 秒，已解除回滚标记"
        )
    }

    /**
     * 检测到系统异常：把配置退回「改动前」，让开关自动回到原状态。
     * 没有快照时走全部还原兜底。
     */
    suspend fun autoRevertPending(reason: String, evidence: String): Boolean {
        if (RootState.granted != true) return false
        val keys = readMeta()["keys"] ?: "本次改动"
        LogRepository.log(
            LogLevel.ERROR,
            "检测到系统异常，自动关闭改动",
            "$keys ← $reason · $evidence"
        )

        val snapshotJson = AppPrefs.instance.loadGuardSnapshot()
            ?: readText(BEFORE_PATH).ifBlank { null }
        val snapshot = snapshotJson?.let { TweakConfig.fromJson(it) }
        if (snapshot == null) {
            LogRepository.log(LogLevel.WARN, "没有可用快照", "改为执行全部还原")
            return restoreAll("$reason（无快照兜底）")
        }

        TweakState.applyConfig(snapshot)
        runCatching { AppPrefs.instance.saveConfig(snapshot) }
        RootShell.exec(C17Commands.writeConfig(snapshot.toJson()), 12_000L)
        LogRepository.log(
            LogLevel.SUCCESS,
            "已退回改动前",
            "开关已恢复：$keys"
        )

        clearPending()
        RootShell.exec(C17Commands.RESTART_SYSTEMUI, 10_000L)
        RootShell.exec(C17Commands.RESTART_LAUNCHER, 8_000L)
        if (!waitForSystemUi()) {
            LogRepository.log(LogLevel.WARN, "SystemUI 未在预期时间内恢复", "继续观察")
        }
        recordAutoRevert(keys, reason, evidence)
        return true
    }

    private suspend fun recordAutoRevert(keys: String, reason: String, evidence: String) {
        val at = stamp()
        SafetyState.lastAutoRevertAt = at
        SafetyState.lastAutoRevertKeys = keys
        SafetyState.lastAutoRevertReason = reason
        runCatching { AppPrefs.instance.saveLastRevert(at, keys, reason) }
        RootShell.exec(
            "mkdir -p ${quote(SAFETY_DIR)} && " +
                "printf '%s\\n' ${quote("$at\t$keys\t$reason\t${evidence.replace('\n', ' ')}")} " +
                ">> ${quote(AUTO_LOG_PATH)}",
            8_000L
        )
    }

    /**
     * 应用后的 90 秒守护窗口：SystemUI 崩溃或进程重启 → 自动退回改动前。
     * 设备重启则交给开机守护脚本处理。
     */
    suspend fun watchAfterApply() {
        if (RootState.granted != true) return
        if (!exists(PENDING_PATH)) return
        val generation = ++watchGeneration
        SafetyState.watching = true
        val startedAt = System.currentTimeMillis()
        val sinceSec = startedAt / 1000
        val bootId = readBootId()
        val pidAtStart = systemUiPid()
        try {
            while (System.currentTimeMillis() - startedAt < WATCH_WINDOW_MS) {
                delay(WATCH_POLL_MS)
                if (generation != watchGeneration) return
                val status = pollStatus(sinceSec)
                if (!status.pending) break
                if (status.bootId != bootId) break
                val pid = status.pid
                if (pid != null && pidAtStart != null && pid != pidAtStart) {
                    autoRevertPending("SystemUI 进程重启", "pid $pidAtStart → $pid")
                    return
                }
                if (status.crashes.isNotEmpty()) {
                    autoRevertPending("检测到系统进程崩溃", status.crashes.joinToString("；"))
                    return
                }
            }
            if (exists(PENDING_PATH)) markStable()
        } finally {
            if (generation == watchGeneration) SafetyState.watching = false
        }
    }

    private data class SysStatus(
        val bootId: String,
        val pid: Int?,
        val pending: Boolean,
        val crashes: List<String>
    )

    private suspend fun pollStatus(sinceSec: Long): SysStatus {
        val cmd = buildString {
            appendLine("cat /proc/sys/kernel/random/boot_id 2>/dev/null")
            appendLine("echo __C17_PID__")
            appendLine("pidof com.android.systemui 2>/dev/null || pidof com.coloros.systemui 2>/dev/null")
            appendLine("echo __C17_PEND__")
            appendLine("test -f ${quote(PENDING_PATH)} && echo 1 || echo 0")
            appendLine("echo __C17_CRASH__")
            appendLine("logcat -b crash -d -v epoch 2>/dev/null | tail -n 80")
        }
        val text = RootShell.exec(cmd, 20_000L).stdout
        val bootId = text.substringBefore("__C17_PID__").trim()
        val pidRaw = text.substringAfter("__C17_PID__", "").substringBefore("__C17_PEND__").trim()
        val pid = pidRaw.split(' ').firstOrNull()?.toIntOrNull()
        val pending = text.substringAfter("__C17_PEND__", "").substringBefore("__C17_CRASH__").trim() == "1"
        return SysStatus(
            bootId = bootId,
            pid = pid,
            pending = pending,
            crashes = parseSystemCrashes(text.substringAfter("__C17_CRASH__", ""), sinceSec)
        )
    }

    private val crashLine = Regex("""^\s*(\d{10})\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+[^:]+:\s(.*)$""")

    /** 从 crash buffer 里挑出 SystemUI / system_server 在 [sinceSec] 之后的崩溃。 */
    fun parseSystemCrashes(text: String, sinceSec: Long): List<String> {
        if (text.isBlank()) return emptyList()
        val hits = mutableListOf<String>()
        var fatal = false
        text.lineSequence().forEach { line ->
            val match = crashLine.find(line)
            val epoch = match?.groupValues?.get(1)?.toLongOrNull()
            val message = match?.groupValues?.get(2) ?: line
            when {
                message.contains("FATAL EXCEPTION IN SYSTEM PROCESS") -> {
                    fatal = false
                    if (epoch != null && epoch >= sinceSec) hits += "system_server @ $epoch"
                }
                message.contains("FATAL EXCEPTION") -> {
                    fatal = true
                }
                fatal && message.startsWith("Process:") -> {
                    val process = message.removePrefix("Process:").substringBefore(",").trim()
                    fatal = false
                    if (epoch != null && epoch >= sinceSec && isSystemProcess(process)) {
                        hits += "$process @ $epoch"
                    }
                }
            }
        }
        return hits
    }

    private fun isSystemProcess(process: String): Boolean =
        process == "system_server" ||
            process.startsWith("com.android.systemui") ||
            process.startsWith("com.coloros.systemui")

    suspend fun scanSystemCrashes(sinceSec: Long): List<String> {
        if (sinceSec <= 0L) return emptyList()
        val text = RootShell.exec(
            "logcat -b crash -d -v epoch 2>/dev/null | tail -n 80",
            20_000L
        ).stdout
        return parseSystemCrashes(text, sinceSec)
    }

    private suspend fun readMeta(): Map<String, String> {
        val text = readText(PENDING_META)
        if (text.isBlank()) return emptyMap()
        return text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
    }

    private suspend fun readBootId(): String =
        RootShell.exec("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 6_000L).stdout.trim()

    private suspend fun systemUiPid(): Int? =
        RootShell.exec(
            "pidof com.android.systemui 2>/dev/null || pidof com.coloros.systemui 2>/dev/null",
            6_000L
        ).stdout.trim().split(' ').firstOrNull()?.toIntOrNull()

    private suspend fun waitForSystemUi(): Boolean {
        val result = RootShell.exec(
            "for i in 1 2 3 4 5 6 7 8 9 10; do " +
                "sleep 1; " +
                "if pidof com.android.systemui >/dev/null 2>&1 || " +
                "pidof com.coloros.systemui >/dev/null 2>&1; then " +
                "echo yes; exit 0; fi; done; echo no; exit 1",
            20_000L
        )
        return result.stdout.lines().any { it.trim() == "yes" }
    }

    /** 写入开机守护：上次改动没确认就重启，开机时自动执行 restore.sh。 */
    suspend fun installBootGuard(): Boolean {
        if (RootState.granted != true) return false
        val wasInstalled = SafetyState.guardInstalled
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# C17 玻璃工坊 · 开机安全守护（自动生成，请勿修改）")
            appendLine("BASE=" + quote(SAFETY_DIR))
            appendLine("PEND=\"\$BASE/pending\"")
            appendLine("META=\"\$BASE/pending_meta\"")
            appendLine("[ -f \"\$PEND\" ] || exit 0")
            appendLine("STAMP=\$(date '+%Y-%m-%d %H:%M:%S')")
            appendLine("{")
            appendLine("  echo \"stamp=\$STAMP\"")
            appendLine("  echo \"reason=boot\"")
            appendLine("  [ -f \"\$META\" ] && cat \"\$META\"")
            appendLine("} > \"\$BASE/boot_reverted\" 2>/dev/null")
            appendLine("if [ -f \"\$BASE/restore.sh\" ]; then")
            appendLine("  sh \"\$BASE/restore.sh\" >/dev/null 2>&1")
            appendLine("else")
            appendLine("  rm -f \"\$PEND\" \"\$META\"")
            appendLine("fi")
            appendLine("exit 0")
        }
        val written = writeText(GUARD_SCRIPT, script)
        val chmod = RootShell.exec(
            "mkdir -p ${quote(SERVICE_D)} && " +
                "chmod 755 ${quote(GUARD_SCRIPT)} 2>/dev/null; " +
                "mkdir -p ${quote("/data/local/tmp/c17_glass")} && " +
                "cp -f ${quote(GUARD_SCRIPT)} ${quote(GUARD_MIRROR)} && " +
                "chmod 755 ${quote(GUARD_MIRROR)}",
            10_000L
        )
        if (!written || !chmod.ok) {
            LogRepository.log(LogLevel.WARN, "开机守护写入失败", "$GUARD_SCRIPT")
            SafetyState.guardInstalled = false
            return false
        }
        val syntax = RootShell.exec(
            "sh -n ${quote(GUARD_SCRIPT)} && echo C17_GUARD_OK",
            10_000L
        )
        SafetyState.guardInstalled = true
        if (syntax.stdout.contains("C17_GUARD_OK")) {
            if (!wasInstalled) {
                LogRepository.log(
                    LogLevel.SUCCESS,
                    "开机守护已安装",
                    "$GUARD_SCRIPT · 改动没确认就重启会自动执行 restore.sh"
                )
            }
        } else {
            LogRepository.log(
                LogLevel.WARN,
                "开机守护语法检查未通过",
                syntax.output.ifBlank { "exit=${syntax.code}" }
            )
        }
        return true
    }

    private suspend fun handleBootRevert(): Boolean {
        val text = readText(BOOT_REVERTED_PATH)
        if (text.isBlank()) return false
        val lines = text.lineSequence().toList()
        val at = lines.firstOrNull { it.startsWith("stamp=") }
            ?.removePrefix("stamp=")?.trim() ?: stamp()
        val keys = lines.firstOrNull { it.startsWith("keys=") }
            ?.removePrefix("keys=")?.trim() ?: "全部参数"
        val reason = lines.firstOrNull { it.startsWith("reason=") }
            ?.removePrefix("reason=")?.trim() ?: "boot"
        RootShell.exec("rm -f ${quote(BOOT_REVERTED_PATH)}", 8_000L)
        resetAppConfig()
        val textReason = if (reason == "boot") "开机时检测到未确认的改动，已自动还原" else reason
        SafetyState.lastAutoRevertAt = at
        SafetyState.lastAutoRevertKeys = keys
        SafetyState.lastAutoRevertReason = textReason
        runCatching { AppPrefs.instance.saveLastRevert(at, keys, textReason) }
        LogRepository.log(
            LogLevel.WARN,
            "开机守护已自动还原",
            "未确认的改动：$keys · $at"
        )
        return true
    }

    /**
     * App 启动时检查：
     * - 上次改动还在守护窗口内 → 返回 true，让调用方继续盯崩溃；
     * - 窗口内出现过崩溃 → 自动退回改动前；
     * - 设备重启过 → 交给开机守护的标记，兜底全部还原。
     */
    suspend fun bootstrap(): Boolean {
        if (RootState.granted != true) {
            SafetyState.ready = false
            refreshStatus()
            return false
        }
        if (exists(RESTORED_MARKER)) {
            // 由 root 终端直接跑过 restore.sh：下次打开 App 时同步清空界面选择。
            resetAppConfig()
            RootShell.exec("rm -f ${quote(RESTORED_MARKER)}", 8_000L)
            LogRepository.log(LogLevel.WARN, "已从 root 终端还原", "界面选项已同步清空")
        }
        handleBootRevert()

        var shouldWatch = false
        if (exists(PENDING_PATH)) {
            val meta = readMeta()
            val pendingAt = meta["ts"]?.toLongOrNull() ?: 0L
            val crashes = scanSystemCrashes(pendingAt)
            val sameBoot = meta["boot"] == readBootId()
            val ageMs = System.currentTimeMillis() - pendingAt * 1000
            when {
                crashes.isNotEmpty() -> {
                    autoRevertPending(
                        "重新打开 App 时检测到崩溃",
                        crashes.joinToString("；")
                    )
                }

                !sameBoot -> {
                    LogRepository.log(
                        LogLevel.WARN,
                        "检测到重启后的未确认改动",
                        "App 启动时自动回滚"
                    )
                    restoreAll("重启后的未确认改动，自动回滚")
                }

                ageMs in 0 until WATCH_WINDOW_MS -> shouldWatch = true
                else -> markStable()
            }
        } else {
            ensureBaseline()
        }

        AppPrefs.instance.loadLastRevert()?.let { (at, keys, reason) ->
            SafetyState.lastAutoRevertAt = at
            SafetyState.lastAutoRevertKeys = keys
            SafetyState.lastAutoRevertReason = reason
        }
        installBootGuard()
        refreshStatus()
        return shouldWatch
    }

    /** 一键全部还原：恢复基线并重建备份。 */
    suspend fun restoreAll(reason: String): Boolean {
        if (RootState.granted != true) {
            SafetyState.lastMessage = "没有 root，无法还原"
            return false
        }
        SafetyState.restoring = true
        SafetyState.lastMessage = null
        LogRepository.log(LogLevel.INFO, "开始安全还原", reason)

        val scriptExists = exists(SCRIPT_PATH)
        val result = if (scriptExists) {
            RootShell.exec("sh ${quote(SCRIPT_PATH)}", 30_000L)
        } else {
            RootShell.exec(
                "settings delete secure clock_seconds >/dev/null 2>&1; " +
                    "settings put secure qs_settings_fluid_glass_switch 0 >/dev/null 2>&1 || true; " +
                    "settings delete global ${C17Commands.GLOBAL_CONFIG_KEY} >/dev/null 2>&1 || true; " +
                    "settings put global ${C17Commands.GLOBAL_DISABLED_KEY} 1 >/dev/null 2>&1 || true; " +
                    "mkdir -p ${quote(C17Commands.CONFIG_DIR)} && chmod 0777 ${quote(C17Commands.CONFIG_DIR)} && " +
                    "printf '%s' '1' > ${quote(C17Commands.DISABLED_PATH)} && chmod 0644 ${quote(C17Commands.DISABLED_PATH)}; " +
                    "rm -f ${quote(CONFIG_PATH)}; " +
                    "kill -TERM \$(pidof com.android.systemui com.coloros.systemui com.android.launcher 2>/dev/null) 2>/dev/null || true; " +
                    "echo C17_RESTORE_OK",
                30_000L
            )
        }

        val ok = result.ok && !result.stdout.contains("C17_RESTORE_FAIL")
        if (ok) {
            removeBaselineFiles()
            SafetyState.ready = false
            SafetyState.pending = false
            resetAppConfig()
            RootShell.exec("rm -f ${quote(RESTORED_MARKER)}", 8_000L)
            LogRepository.log(
                LogLevel.SUCCESS,
                "安全还原完成",
                if (scriptExists) "已执行 $SCRIPT_PATH" else "备份脚本不存在，已清理配置和时钟设置"
            )
            // 还原完成后重新记录一份干净的基线，方便下一次应用。
            ensureBaseline()
            SafetyState.lastMessage = "已还原到安全基线"
        } else {
            SafetyState.lastMessage = "还原失败，备份已保留"
            LogRepository.log(
                LogLevel.ERROR,
                "安全还原失败",
                "exit=${result.code} ${result.output}"
            )
        }

        SafetyState.restoring = false
        refreshStatus()
        return ok
    }

    /**
     * 未来新增文件改动时先调用这里。
     * 文件存在就复制原文件；不存在就记录“原本不存在”，还原时删除。
     */
    suspend fun backupFile(target: String): Boolean {
        if (!ensureBaseline()) return false
        val records = loadRecords()
        if (records.any { it.target == target }) return true

        val type = RootShell.exec(
            "if [ -f ${quote(target)} ]; then echo file; " +
                "elif [ -d ${quote(target)} ]; then echo dir; " +
                "else echo absent; fi",
            8_000L
        ).stdout.trim()
        if (type !in setOf("file", "dir", "absent")) {
            LogRepository.log(LogLevel.ERROR, "文件备份失败", "无法识别 $target")
            return false
        }

        val backup = if (type == "absent") {
            ""
        } else {
            "$FILES_DIR/${hashPath(target)}.bak"
        }
        val existed = type != "absent"

        if (existed) {
            val copy = if (type == "file") {
                RootShell.exec(
                    "rm -rf ${quote(backup)} && cp -a ${quote(target)} ${quote(backup)} " +
                        "&& cmp -s ${quote(target)} ${quote(backup)}",
                    20_000L
                )
            } else {
                RootShell.exec(
                    "rm -rf ${quote(backup)} && cp -a ${quote(target)} ${quote(backup)}",
                    30_000L
                )
            }
            if (!copy.ok) {
                LogRepository.log(LogLevel.ERROR, "文件备份失败", "复制或校验失败：$target")
                return false
            }
        }

        val record = FileRecord(target = target, backup = backup, existed = existed)
        val line = listOf(
            record.target,
            record.backup,
            if (record.existed) "1" else "0"
        ).joinToString("\t")
        val append = RootShell.exec(
            "printf '%s\\n' ${quote(line)} >> ${quote(RECORDS_PATH)}",
            8_000L
        )
        if (!append.ok) {
            LogRepository.log(LogLevel.ERROR, "文件备份失败", "记录写入失败：$target")
            return false
        }
        if (!writeRestoreScript(records + record)) {
            return false
        }
        LogRepository.log(
            LogLevel.SUCCESS,
            "文件已加入安全备份",
            target
        )
        return true
    }

    suspend fun refreshStatus() {
        if (RootState.granted != true) {
            SafetyState.ready = false
            SafetyState.pending = false
            SafetyState.lastBackupAt = null
            SafetyState.guardInstalled = false
            return
        }
        SafetyState.ready = exists(SCRIPT_PATH)
        SafetyState.pending = exists(PENDING_PATH)
        SafetyState.lastBackupAt = readText(CREATED_AT_PATH).ifBlank { null }
        SafetyState.guardInstalled = exists(GUARD_SCRIPT)
    }

    private suspend fun removeBaselineFiles() {
        RootShell.exec(
            "rm -rf ${quote(SAFETY_DIR)} && rm -f ${quote(MIRROR_SCRIPT)}",
            10_000L
        )
    }

    private fun resetAppConfig() {
        runCatching {
            TweakState.reset()
            TweakState.lastAppliedAt = null
            AppPrefs.instance.saveConfig(TweakState.toConfig())
            AppPrefs.instance.clearGuardSnapshot()
        }
    }

    private suspend fun writeRestoreScript(records: List<FileRecord>): Boolean {
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("BASE=${quote(SAFETY_DIR)}")
            appendLine("CONFIG=${quote(CONFIG_PATH)}")
            appendLine("fail=0")
            appendLine()
            appendLine("rm -f \"\$CONFIG\" || fail=1")
            appendLine("rm -f \"\$BASE/pending_meta\" \"\$BASE/before.json\"")
            appendLine("settings put secure qs_settings_fluid_glass_switch 0 >/dev/null 2>&1 || true")
            appendLine("settings delete global ${C17Commands.GLOBAL_CONFIG_KEY} >/dev/null 2>&1 || true")
            appendLine("settings put global ${C17Commands.GLOBAL_DISABLED_KEY} 1 >/dev/null 2>&1 || true")
            appendLine("mkdir -p ${quote(C17Commands.CONFIG_DIR)} && chmod 0777 ${quote(C17Commands.CONFIG_DIR)}")
            appendLine("printf '%s' '1' > ${quote(C17Commands.DISABLED_PATH)} && chmod 0644 ${quote(C17Commands.DISABLED_PATH)}")
            appendLine()
            appendLine("ORIG=\$(cat \"\$BASE/originals/clock_seconds\" 2>/dev/null || echo __ABSENT__)")
            appendLine("if [ \"\$ORIG\" = \"__ABSENT__\" ] || [ -z \"\$ORIG\" ]; then")
            appendLine("  settings delete secure clock_seconds >/dev/null 2>&1 || true")
            appendLine("else")
            appendLine("  settings put secure clock_seconds \"\$ORIG\" >/dev/null 2>&1 || fail=1")
            appendLine("fi")
            appendLine()
            records.forEach { record ->
                val target = quote(record.target)
                if (record.existed) {
                    val backup = quote(record.backup)
                    val parent = quote(record.target.substringBeforeLast('/', "/"))
                    appendLine("if [ -e $backup ]; then")
                    appendLine("  rm -rf $target")
                    appendLine("  mkdir -p $parent 2>/dev/null || true")
                    appendLine("  cp -a $backup $target || fail=1")
                    appendLine("  command -v restorecon >/dev/null 2>&1 && restorecon -R $target >/dev/null 2>&1 || true")
                    appendLine("else")
                    appendLine("  fail=1")
                    appendLine("fi")
                } else {
                    appendLine("rm -rf $target || fail=1")
                }
            }
            appendLine()
            appendLine("kill -TERM \$(pidof com.android.systemui com.coloros.systemui com.android.launcher 2>/dev/null) 2>/dev/null || true")
            appendLine("if [ \"\$fail\" -eq 0 ]; then")
            appendLine("  rm -f \"\$BASE/pending\"")
            appendLine("  mkdir -p ${quote(C17Commands.CONFIG_DIR)}")
            appendLine("  printf '%s' '1' > ${quote(RESTORED_MARKER)}")
            appendLine("  echo C17_RESTORE_OK")
            appendLine("else")
            appendLine("  echo C17_RESTORE_FAIL")
            appendLine("fi")
            appendLine("exit \"\$fail\"")
        }

        if (!writeText(SCRIPT_PATH, script)) {
            LogRepository.log(LogLevel.ERROR, "安全脚本写入失败", SCRIPT_PATH)
            return false
        }
        val mirror = RootShell.exec(
            "mkdir -p ${quote("/data/local/tmp/c17_glass")} && " +
                "cp -f ${quote(SCRIPT_PATH)} ${quote(MIRROR_SCRIPT)} && " +
                "chmod 755 ${quote(SCRIPT_PATH)} ${quote(MIRROR_SCRIPT)}",
            10_000L
        )
        if (!mirror.ok) {
            LogRepository.log(LogLevel.WARN, "恢复脚本镜像写入失败", mirror.output)
        }
        return true
    }
}
