package com.c17.glass.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    INFO("信息"),
    SUCCESS("成功"),
    WARN("警告"),
    ERROR("错误"),
    CMD("命令")
}

data class LogEntry(
    val id: Long,
    val time: String,
    val level: LogLevel,
    val title: String,
    val detail: String = ""
)

/** 全局运行日志，界面直接观察 entries。 */
object LogRepository {

    private const val MAX_ENTRIES = 400
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    private val _entries = mutableStateListOf<LogEntry>()
    val entries: List<LogEntry> get() = _entries

    private var sequence = 0L

    fun log(level: LogLevel, title: String, detail: String = "") {
        val entry = synchronized(lock) {
            LogEntry(
                id = sequence++,
                time = timeFormat.format(Date()),
                level = level,
                title = title,
                detail = detail
            )
        }
        val append = {
            _entries.add(0, entry)
            while (_entries.size > MAX_ENTRIES) {
                _entries.removeAt(_entries.lastIndex)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) append() else mainHandler.post(append)
    }

    fun clear() {
        val action = { _entries.clear() }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    fun exportText(): String = _entries.joinToString("\n") { entry ->
        val head = "${entry.time} [${entry.level.label}] ${entry.title}"
        if (entry.detail.isBlank()) head else "$head\n    ${entry.detail.replace("\n", "\n    ")}"
    }
}
