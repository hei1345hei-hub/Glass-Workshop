package com.c17.glass.xposed.statusbar

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File

// 本文件是配置契约（与 App 端 TweakConfig.toJson 一一对应）；只由模块主入口维护。
// SystemUI 侧只读，任何字段读不到都回落到默认值，绝不让 hook 因为配置异常而抛。

class HookConfig(private val json: JSONObject?) {

    val available: Boolean get() = json != null

    val clockSeconds: Boolean get() = bool("clockSeconds", false)
    val centerClock: Boolean get() = bool("centerClock", false)
    val networkSpeed: Boolean get() = bool("networkSpeed", false)
    val batteryPercent: Boolean get() = bool("batteryPercent", true)
    val iconTint: Boolean get() = bool("iconTint", false)
    /** 图标着色用的颜色，由 App 端写入（默认主题蓝 #2F6BFF）。 */
    val iconTintColorHex: String get() = json?.optString("iconTintColor", "#2F6BFF") ?: "#2F6BFF"
    val iconTintColor: Int
        get() = runCatching { android.graphics.Color.parseColor(iconTintColorHex) }
            .getOrDefault(0xFF2F6BFF.toInt())
    val statusBarAlpha: Float get() = number("statusBarAlpha", 0.12f)
    val iosStatusBar: Boolean get() = bool("iosStatusBar", true)
    val iosTime24Hour: Boolean get() = bool("iosTime24Hour", false)
    val iosBatteryStyle: Boolean get() = bool("iosBatteryStyle", true)
    val iosMuteIcon: Boolean get() = bool("iosMuteIcon", false)
    val iosDoubleSignal: Boolean get() = bool("iosDoubleSignal", false)
    val iosBluetoothIcon: Boolean get() = bool("iosBluetoothIcon", false)
    val ios5gPosition: String get() = json?.optString("ios5gPosition", "DEFAULT") ?: "DEFAULT"
    val glassControlCenter: Boolean get() = bool("glassControlCenter", true)
    val glassNotifications: Boolean get() = bool("glassNotifications", true)
    val glassVolume: Boolean get() = bool("glassVolume", true)
    val glassFolders: Boolean get() = bool("glassFolders", true)
    val glassDock: Boolean get() = bool("glassDock", true)
    val applyGlobally: Boolean get() = bool("applyGlobally", true)
    val hasSystemUiGlass: Boolean get() = glassControlCenter || glassNotifications || glassVolume || applyGlobally
    val hasLauncherGlass: Boolean get() = glassFolders || glassDock || applyGlobally

    // 第 1 类：控制中心与通知中心
    val refraction: Float get() = number("refraction", 0.58f)
    val blurRadius: Float get() = number("blurRadius", 24f)
    val highlight: Boolean get() = bool("highlight", true)
    val cornerRadius: Float get() = number("cornerRadius", 26f)

    // 第 2 类：桌面文件夹与 Dock 栏
    val launcherRefraction: Float get() = number("launcherRefraction", refraction)
    val launcherBlurRadius: Float get() = number("launcherBlurRadius", 10f)
    val launcherHighlight: Boolean get() = bool("launcherHighlight", highlight)
    val launcherCornerRadius: Float get() = number("launcherCornerRadius", cornerRadius)

    // 第 3 类：音量条
    val volumeRefraction: Float get() = number("volumeRefraction", refraction)
    val volumeBlurRadius: Float get() = number("volumeBlurRadius", 16f)
    val volumeHighlight: Boolean get() = bool("volumeHighlight", highlight)
    val volumeCornerRadius: Float get() = number("volumeCornerRadius", cornerRadius)

    /** 5G / 网络类型标识是否要挪到电量旁（iPhone 风格）。 */
    val networkTypeBesideBattery: Boolean get() = ios5gPosition.equals("BATTERY", ignoreCase = true)

    fun raw(): String = json?.toString() ?: "null"

    private fun bool(key: String, def: Boolean): Boolean =
        runCatching { json?.optBoolean(key, def) ?: def }.getOrDefault(def)

    private fun number(key: String, def: Float): Float =
        runCatching { json?.optDouble(key, def.toDouble())?.toFloat() ?: def }.getOrDefault(def)

    companion object {
        const val DIR = "/data/local/tmp/c17_glass"
        const val PATH = "$DIR/config.json"
        const val DISABLED_PATH = "$DIR/disabled"
        const val RESTORED_PATH = "$DIR/restored"
        const val GLOBAL_CONFIG_KEY = "c17_glass_json"
        const val GLOBAL_DISABLED_KEY = "c17_glass_disabled"

        fun resolveContext(context: Context? = null): Context? {
            if (context != null) return context
            return runCatching {
                val atCls = Class.forName("android.app.ActivityThread")
                atCls.getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()
        }

        fun isExplicitlyDisabled(context: Context? = null): Boolean {
            if (runCatching { File(DISABLED_PATH).exists() || File(RESTORED_PATH).exists() }.getOrDefault(false)) {
                return true
            }
            val ctx = resolveContext(context)
            if (ctx != null) {
                val disabled = runCatching {
                    Settings.Global.getInt(ctx.contentResolver, GLOBAL_DISABLED_KEY, 0) == 1
                }.getOrDefault(false)
                if (disabled) return true
            }
            return false
        }

        fun stamp(context: Context? = null): String {
            val ctx = resolveContext(context)
            if (isExplicitlyDisabled(ctx)) return "__DISABLED__"
            val fileStamp = runCatching {
                val file = File(PATH)
                if (file.exists() && file.canRead() && file.length() > 0L) {
                    "file:${file.length()}:${file.lastModified()}"
                } else {
                    ""
                }
            }.getOrDefault("")
            if (fileStamp.isNotEmpty()) return fileStamp
            if (ctx != null) {
                val globalJson = runCatching {
                    Settings.Global.getString(ctx.contentResolver, GLOBAL_CONFIG_KEY)
                }.getOrNull()
                if (!globalJson.isNullOrBlank()) {
                    return "global:${globalJson.hashCode()}"
                }
            }
            return ""
        }

        /**
         * 优先读取 /data/local/tmp/c17_glass/config.json；
         * 若 SystemUI / priv_app 受 SELinux/DAC 限制无法直读临时目录且提供了或可解析出 Context，
         * 自动回落到 Settings.Global 镜像契约。读失败则返回默认占位（available=false）。
         */
        fun read(context: Context? = null, quiet: Boolean = false): HookConfig = runCatching {
            val file = File(PATH)
            if (file.exists() && file.canRead()) {
                val text = file.readText()
                if (text.isNotBlank()) return HookConfig(JSONObject(text))
            }
            val ctx = resolveContext(context)
            if (ctx != null) {
                val globalText = runCatching {
                    Settings.Global.getString(ctx.contentResolver, GLOBAL_CONFIG_KEY)
                }.getOrNull()
                if (!globalText.isNullOrBlank()) {
                    return HookConfig(JSONObject(globalText))
                }
            }
            if (!quiet) {
                HookLog.warn("config.json 不可读或不存在：$PATH")
            }
            HookConfig(null)
        }.getOrElse {
            HookLog.error("config.json 解析失败", it)
            HookConfig(null)
        }
    }
}
