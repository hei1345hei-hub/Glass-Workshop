package com.c17.glass.core

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String
) {
    val ok: Boolean get() = code == 0
    val output: String get() = stdout.ifBlank { stderr }.trim()

    companion object {
        const val TIMEOUT = -2
        const val NO_SU = -3
    }
}

/**
 * 最小可用的 root 执行层。
 * 只依赖系统自带的 `su`（Magisk / KernelSU / APatch 都提供），不引入第三方库。
 */
object RootShell {

    suspend fun exec(command: String, timeoutMs: Long = 20_000L): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command).start()
                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val outReader = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
                    }
                }.apply { isDaemon = true; start() }

                val errReader = Thread {
                    runCatching {
                        process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
                    }
                }.apply { isDaemon = true; start() }

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                }
                outReader.join(1_000)
                errReader.join(1_000)

                val code = if (finished) runCatching { process.exitValue() }.getOrDefault(0) else ShellResult.TIMEOUT
                ShellResult(
                    code = code,
                    stdout = stdout.toString().trim(),
                    stderr = stderr.toString().trim()
                )
            } catch (t: Throwable) {
                ShellResult(ShellResult.NO_SU, "", t.message ?: "su 不可用")
            }
        }

    suspend fun readProp(key: String): String =
        exec("getprop $key", 6_000L).stdout.trim()

    suspend fun isRooted(): Boolean {
        val result = exec("id -u", 8_000L)
        return result.code == 0 && result.stdout.trim().lines().lastOrNull()?.trim() == "0"
    }

    suspend fun suVersion(): String {
        val version = exec("su -v", 8_000L).stdout.trim()
        return version.ifBlank { "unknown" }
    }
}

/** 全局 root 状态，供各个界面共享。 */
object RootState {
    var granted: Boolean? by mutableStateOf(null)
    var suVersion: String by mutableStateOf("")
    var lastError: String? by mutableStateOf(null)
    var checking: Boolean by mutableStateOf(false)

    suspend fun refresh() {
        if (checking) return
        checking = true
        try {
            granted = RootShell.isRooted()
            suVersion = if (granted == true) RootShell.suVersion() else ""
            lastError = null
        } catch (t: Throwable) {
            granted = false
            lastError = t.message
        } finally {
            checking = false
        }
    }
}

/** 设备与系统信息。 */
data class DeviceInfo(
    val model: String,
    val brand: String,
    val colorOsVersion: String,
    val androidVersion: String,
    val sdk: Int,
    val selinux: String,
    val rooted: Boolean,
    val suVersion: String
)

object DeviceInfoLoader {

    @Volatile
    private var cached: DeviceInfo? = null

    private fun readSystemProperty(key: String): String = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java).invoke(null, key) as? String)?.trim().orEmpty()
    }.getOrDefault("")

    suspend fun load(forceRefresh: Boolean = false): DeviceInfo {
        if (!forceRefresh) {
            cached?.let { return it }
        }
        val rooted = RootState.granted ?: RootShell.isRooted()
        val suVersion = if (rooted) {
            RootState.suVersion.takeIf { it.isNotBlank() } ?: RootShell.suVersion()
        } else {
            ""
        }
        val colorOs = listOf(
            "ro.build.version.oplusrom",
            "ro.build.version.oplusrom.display",
            "ro.oplus.version",
            "ro.build.version.incremental"
        ).firstNotNullOfOrNull { key ->
            readSystemProperty(key).takeIf { it.isNotBlank() }
                ?: RootShell.readProp(key).takeIf { it.isNotBlank() }
        } ?: "ColorOS 17"

        val selinuxModes = setOf("Enforcing", "Permissive", "Disabled")
        val selinuxRaw = runCatching {
            ProcessBuilder("getenforce").start()
                .inputStream.bufferedReader()
                .use { it.readText().trim() }
        }.getOrNull()?.takeIf { it in selinuxModes }
            ?: RootShell.exec("getenforce", 6_000L).output.trim()
        val selinux = selinuxRaw.takeIf { it in selinuxModes } ?: "未知"

        return DeviceInfo(
            model = Build.MODEL ?: "Unknown",
            brand = Build.BRAND ?: "Unknown",
            colorOsVersion = colorOs,
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdk = Build.VERSION.SDK_INT,
            selinux = selinux,
            rooted = rooted,
            suVersion = suVersion
        ).also { cached = it }
    }
}
