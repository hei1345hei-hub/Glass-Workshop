package com.c17.glass.xposed.statusbar

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 本文件是四个图标 hook 共用的诊断出口；由模块主入口统一维护，子智能体不要修改。
// 设计要点：
//  1) 永远不抛异常——写日志本身失败（例如没有权限）也必须静默吞掉；
//  2) 双通道：Log.i 走 logcat（adb 一定能读），同时尽力追加到 hook.log；
//  3) hook.log 位于 /data/local/tmp/c17_glass/，目录被 App 应用流程 chmod 0777 后才可写。

object HookLog {

    /** logcat 过滤：adb logcat -s C17Glass */
    const val TAG = "C17Glass"

    const val DIR = "/data/local/tmp/c17_glass"
    const val FILE = "$DIR/hook.log"

    private const val MAX_BYTES = 512L * 1024L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile
    private var fileUsable: Boolean? = null

    fun ok(msg: String) = write("OK   ", msg)
    fun info(msg: String) = write("INFO ", msg)
    fun warn(msg: String) = write("WARN ", msg)
    fun error(msg: String, t: Throwable? = null) =
        write("FAIL ", if (t == null) msg else "$msg :: ${t.javaClass.name}: ${t.message}")

    fun error(msg: String, vararg detail: Any?) =
        write("FAIL ", msg + detail.joinToString(" | ", prefix = " :: ") { it.toString() })

    private fun write(level: String, msg: String) {
        val line = "${stamp.format(Date())} [$level] $msg"
        runCatching {
            when (level) {
                "FAIL " -> Log.e(TAG, line)
                "WARN " -> Log.w(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
        appendFile(line)
    }

    /** 首次写入时决定文件通道是否可用，避免每条日志都做一次失败的系统调用。 */
    private fun appendFile(line: String) {
        synchronized(lock) {
            if (fileUsable == false) return
            runCatching {
                val dir = File(DIR)
                if (!dir.exists()) dir.mkdirs()
                val f = File(FILE)
                if (f.length() > MAX_BYTES) runCatching { f.delete() }
                f.appendText(line + "\n")
                fileUsable = true
            }.onFailure {
                fileUsable = false
                runCatching { Log.w(TAG, "hook.log 不可写（$FILE），仅输出 logcat：${it.javaClass.simpleName}") }
            }
        }
    }

    /** 每次 SystemUI 重新注入时打一条分隔，便于区分“这次装了什么”。 */
    fun banner(version: String) {
        write(
            "INFO ",
            "===== C17 Glass hook 注入开始 · v$version · pid=${android.os.Process.myPid()} " +
                "uid=${android.os.Process.myUid()} ====="
        )
    }
}
