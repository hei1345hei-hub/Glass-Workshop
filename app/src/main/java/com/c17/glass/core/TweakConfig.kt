package com.c17.glass.core

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.c17.glass.ui.theme.ThemeMode
import org.json.JSONObject

/** 5G 标识在状态栏右侧的落点，按 iPhone 默认布局排列。 */
enum class Ios5gPosition(val label: String) {
    DEFAULT("默认"),
    BATTERY("电量旁")
}

/** 一份完整的可下发配置。UI 与 SystemUI 侧都以此为契约。 */
data class TweakConfig(
    val clockSeconds: Boolean = false,
    val centerClock: Boolean = false,
    val networkSpeed: Boolean = false,
    val batteryPercent: Boolean = true,
    val iconTint: Boolean = false,
    val statusBarAlpha: Float = 0.12f,
    val iosStatusBar: Boolean = true,
    val iosTime24Hour: Boolean = false,
    val iosBatteryStyle: Boolean = true,
    val iosMuteIcon: Boolean = false,
    val iosDoubleSignal: Boolean = false,
    val iosBluetoothIcon: Boolean = false,
    val ios5gPosition: Ios5gPosition = Ios5gPosition.DEFAULT,
    val glassControlCenter: Boolean = DEFAULT_GLASS_CONTROL_CENTER,
    val glassNotifications: Boolean = DEFAULT_GLASS_NOTIFICATIONS,
    val glassVolume: Boolean = DEFAULT_GLASS_VOLUME,
    val glassFolders: Boolean = DEFAULT_GLASS_FOLDERS,
    val glassDock: Boolean = DEFAULT_GLASS_DOCK,
    val applyGlobally: Boolean = DEFAULT_APPLY_GLOBALLY,
    // 第 1 类：控制中心与通知中心
    val refraction: Float = DEFAULT_REFRACTION,
    val blurRadius: Float = DEFAULT_BLUR_RADIUS,
    val highlight: Boolean = DEFAULT_HIGHLIGHT,
    val cornerRadius: Float = DEFAULT_CORNER_RADIUS,
    // 第 2 类：桌面文件夹与 Dock 栏
    val launcherRefraction: Float = DEFAULT_LAUNCHER_REFRACTION,
    val launcherBlurRadius: Float = DEFAULT_LAUNCHER_BLUR_RADIUS,
    val launcherHighlight: Boolean = DEFAULT_LAUNCHER_HIGHLIGHT,
    val launcherCornerRadius: Float = DEFAULT_LAUNCHER_CORNER_RADIUS,
    // 第 3 类：音量条
    val volumeRefraction: Float = DEFAULT_VOLUME_REFRACTION,
    val volumeBlurRadius: Float = DEFAULT_VOLUME_BLUR_RADIUS,
    val volumeHighlight: Boolean = DEFAULT_VOLUME_HIGHLIGHT,
    val volumeCornerRadius: Float = DEFAULT_VOLUME_CORNER_RADIUS
) {

    /** 与上一次已确认的配置相比，哪些选项被改动过（用于崩溃时精确提示）。 */
    fun diffLabels(other: TweakConfig?): List<String> {
        if (other == null) return listOf("全部参数")
        val out = mutableListOf<String>()
        if (clockSeconds != other.clockSeconds) out += "时钟显示秒数"
        if (centerClock != other.centerClock) out += "居中时钟"
        if (networkSpeed != other.networkSpeed) out += "网速显示"
        if (batteryPercent != other.batteryPercent) out += "电量百分比"
        if (iconTint != other.iconTint) out += "图标着色"
        if (statusBarAlpha != other.statusBarAlpha) out += "状态栏透明度"
        if (iosStatusBar != other.iosStatusBar) out += "仿 iOS 状态栏"
        if (iosTime24Hour != other.iosTime24Hour) out += "时间"
        if (iosBatteryStyle != other.iosBatteryStyle) out += "仿电池外观"
        if (iosMuteIcon != other.iosMuteIcon) out += "静音"
        if (iosDoubleSignal != other.iosDoubleSignal) out += "单双层信号图标"
        if (iosBluetoothIcon != other.iosBluetoothIcon) out += "蓝牙图标"
        if (ios5gPosition != other.ios5gPosition) out += "5G 图标位置"
        if (glassControlCenter != other.glassControlCenter) out += "控制中心玻璃"
        if (glassNotifications != other.glassNotifications) out += "通知中心玻璃"
        if (glassVolume != other.glassVolume) out += "音量条玻璃"
        if (glassFolders != other.glassFolders) out += "文件夹玻璃"
        if (glassDock != other.glassDock) out += "Dock 栏玻璃"
        if (applyGlobally != other.applyGlobally) out += "全局生效"
        if (refraction != other.refraction) out += "控制/通知折射"
        if (blurRadius != other.blurRadius) out += "控制/通知模糊"
        if (highlight != other.highlight) out += "控制/通知高光"
        if (cornerRadius != other.cornerRadius) out += "控制/通知圆角"
        if (launcherRefraction != other.launcherRefraction) out += "桌面/Dock 折射"
        if (launcherBlurRadius != other.launcherBlurRadius) out += "桌面/Dock 模糊"
        if (launcherHighlight != other.launcherHighlight) out += "桌面/Dock 高光"
        if (launcherCornerRadius != other.launcherCornerRadius) out += "桌面/Dock 圆角"
        if (volumeRefraction != other.volumeRefraction) out += "音量条折射"
        if (volumeBlurRadius != other.volumeBlurRadius) out += "音量条模糊"
        if (volumeHighlight != other.volumeHighlight) out += "音量条高光"
        if (volumeCornerRadius != other.volumeCornerRadius) out += "音量条圆角"
        return out
    }

    /** 下发给 SystemUI / 模块的 JSON 契约。 */
    fun toJson(): String = JSONObject().apply {
        put("clockSeconds", clockSeconds)
        put("centerClock", centerClock)
        put("networkSpeed", networkSpeed)
        put("batteryPercent", batteryPercent)
        put("iconTint", iconTint)
        put("iconTintColor", ICON_TINT_COLOR)
        put("statusBarAlpha", statusBarAlpha.toDouble())
        put("iosStatusBar", iosStatusBar)
        put("iosTime24Hour", iosTime24Hour)
        put("iosBatteryStyle", iosBatteryStyle)
        put("iosMuteIcon", iosMuteIcon)
        put("iosDoubleSignal", iosDoubleSignal)
        put("iosBluetoothIcon", iosBluetoothIcon)
        put("ios5gPosition", ios5gPosition.name)
        put("glassControlCenter", glassControlCenter)
        put("glassNotifications", glassNotifications)
        put("glassVolume", glassVolume)
        put("glassFolders", glassFolders)
        put("glassDock", glassDock)
        put("applyGlobally", applyGlobally)
        put("refraction", refraction.toDouble())
        put("blurRadius", blurRadius.toDouble())
        put("highlight", highlight)
        put("cornerRadius", cornerRadius.toDouble())
        put("launcherRefraction", launcherRefraction.toDouble())
        put("launcherBlurRadius", launcherBlurRadius.toDouble())
        put("launcherHighlight", launcherHighlight)
        put("launcherCornerRadius", launcherCornerRadius.toDouble())
        put("volumeRefraction", volumeRefraction.toDouble())
        put("volumeBlurRadius", volumeBlurRadius.toDouble())
        put("volumeHighlight", volumeHighlight)
        put("volumeCornerRadius", volumeCornerRadius.toDouble())
    }.toString(2)

    companion object {
        /** 图标着色用的颜色，与预览里的 accent 保持一致。 */
        const val ICON_TINT_COLOR = "#2F6BFF"

        /** 固定默认参数：仅允许“恢复默认”读取，不提供自定义覆盖入口。 */
        const val DEFAULT_GLASS_CONTROL_CENTER = true
        const val DEFAULT_GLASS_NOTIFICATIONS = true
        const val DEFAULT_GLASS_VOLUME = true
        const val DEFAULT_GLASS_FOLDERS = true
        const val DEFAULT_GLASS_DOCK = true
        const val DEFAULT_APPLY_GLOBALLY = true

        const val DEFAULT_REFRACTION = 0.58f
        const val DEFAULT_BLUR_RADIUS = 24f
        const val DEFAULT_HIGHLIGHT = true
        const val DEFAULT_CORNER_RADIUS = 26f

        const val DEFAULT_LAUNCHER_REFRACTION = 0.58f
        const val DEFAULT_LAUNCHER_BLUR_RADIUS = 10f
        const val DEFAULT_LAUNCHER_HIGHLIGHT = true
        const val DEFAULT_LAUNCHER_CORNER_RADIUS = 26f

        const val DEFAULT_VOLUME_REFRACTION = 0.58f
        const val DEFAULT_VOLUME_BLUR_RADIUS = 16f
        const val DEFAULT_VOLUME_HIGHLIGHT = true
        const val DEFAULT_VOLUME_CORNER_RADIUS = 26f

        /** 还原快照用；解析失败返回 null，交给上层走「全部还原」兜底。 */
        fun fromJson(text: String): TweakConfig? = runCatching {
            val o = JSONObject(text)
            val baseRefraction = o.optDouble("refraction", DEFAULT_REFRACTION.toDouble()).toFloat()
            val baseBlur = o.optDouble("blurRadius", DEFAULT_BLUR_RADIUS.toDouble()).toFloat()
            val baseHighlight = o.optBoolean("highlight", DEFAULT_HIGHLIGHT)
            val baseCorner = o.optDouble("cornerRadius", DEFAULT_CORNER_RADIUS.toDouble()).toFloat()
            TweakConfig(
                clockSeconds = o.optBoolean("clockSeconds", false),
                centerClock = o.optBoolean("centerClock", false),
                networkSpeed = o.optBoolean("networkSpeed", false),
                batteryPercent = o.optBoolean("batteryPercent", true),
                iconTint = o.optBoolean("iconTint", false),
                statusBarAlpha = o.optDouble("statusBarAlpha", 0.12).toFloat(),
                iosStatusBar = o.optBoolean("iosStatusBar", true),
                iosTime24Hour = o.optBoolean("iosTime24Hour", false),
                iosBatteryStyle = o.optBoolean("iosBatteryStyle", true),
                iosMuteIcon = o.optBoolean("iosMuteIcon", false),
                iosDoubleSignal = o.optBoolean("iosDoubleSignal", false),
                iosBluetoothIcon = o.optBoolean("iosBluetoothIcon", false),
                ios5gPosition = runCatching {
                    Ios5gPosition.valueOf(o.optString("ios5gPosition", Ios5gPosition.DEFAULT.name))
                }.getOrDefault(Ios5gPosition.DEFAULT),
                glassControlCenter = o.optBoolean("glassControlCenter", DEFAULT_GLASS_CONTROL_CENTER),
                glassNotifications = o.optBoolean("glassNotifications", DEFAULT_GLASS_NOTIFICATIONS),
                glassVolume = o.optBoolean("glassVolume", DEFAULT_GLASS_VOLUME),
                glassFolders = o.optBoolean("glassFolders", DEFAULT_GLASS_FOLDERS),
                glassDock = o.optBoolean("glassDock", DEFAULT_GLASS_DOCK),
                applyGlobally = o.optBoolean("applyGlobally", DEFAULT_APPLY_GLOBALLY),
                refraction = baseRefraction,
                blurRadius = baseBlur,
                highlight = baseHighlight,
                cornerRadius = baseCorner,
                launcherRefraction = o.optDouble("launcherRefraction", baseRefraction.toDouble()).toFloat(),
                launcherBlurRadius = o.optDouble("launcherBlurRadius", DEFAULT_LAUNCHER_BLUR_RADIUS.toDouble()).toFloat(),
                launcherHighlight = o.optBoolean("launcherHighlight", baseHighlight),
                launcherCornerRadius = o.optDouble("launcherCornerRadius", baseCorner.toDouble()).toFloat(),
                volumeRefraction = o.optDouble("volumeRefraction", baseRefraction.toDouble()).toFloat(),
                volumeBlurRadius = o.optDouble("volumeBlurRadius", DEFAULT_VOLUME_BLUR_RADIUS.toDouble()).toFloat(),
                volumeHighlight = o.optBoolean("volumeHighlight", baseHighlight),
                volumeCornerRadius = o.optDouble("volumeCornerRadius", baseCorner.toDouble()).toFloat()
            )
        }.getOrNull()
    }
}

/** 界面状态：所有控件直接读写这里，切换页面不丢失。 */
object TweakState {
    var clockSeconds by mutableStateOf(false)
    var centerClock by mutableStateOf(false)
    var networkSpeed by mutableStateOf(false)
    var batteryPercent by mutableStateOf(true)
    var iconTint by mutableStateOf(false)
    var statusBarAlpha by mutableFloatStateOf(0.12f)
    var iosStatusBar by mutableStateOf(true)
    var iosTime24Hour by mutableStateOf(false)
    var iosBatteryStyle by mutableStateOf(true)
    var iosMuteIcon by mutableStateOf(false)
    var iosDoubleSignal by mutableStateOf(false)
    var iosBluetoothIcon by mutableStateOf(false)
    var ios5gPosition by mutableStateOf(Ios5gPosition.DEFAULT)
    var glassControlCenter by mutableStateOf(TweakConfig.DEFAULT_GLASS_CONTROL_CENTER)
    var glassNotifications by mutableStateOf(TweakConfig.DEFAULT_GLASS_NOTIFICATIONS)
    var glassVolume by mutableStateOf(TweakConfig.DEFAULT_GLASS_VOLUME)
    var glassFolders by mutableStateOf(TweakConfig.DEFAULT_GLASS_FOLDERS)
    var glassDock by mutableStateOf(TweakConfig.DEFAULT_GLASS_DOCK)
    var applyGlobally by mutableStateOf(TweakConfig.DEFAULT_APPLY_GLOBALLY)

    var refraction by mutableFloatStateOf(TweakConfig.DEFAULT_REFRACTION)
    var blurRadius by mutableFloatStateOf(TweakConfig.DEFAULT_BLUR_RADIUS)
    var highlight by mutableStateOf(TweakConfig.DEFAULT_HIGHLIGHT)
    var cornerRadius by mutableFloatStateOf(TweakConfig.DEFAULT_CORNER_RADIUS)

    var launcherRefraction by mutableFloatStateOf(TweakConfig.DEFAULT_LAUNCHER_REFRACTION)
    var launcherBlurRadius by mutableFloatStateOf(TweakConfig.DEFAULT_LAUNCHER_BLUR_RADIUS)
    var launcherHighlight by mutableStateOf(TweakConfig.DEFAULT_LAUNCHER_HIGHLIGHT)
    var launcherCornerRadius by mutableFloatStateOf(TweakConfig.DEFAULT_LAUNCHER_CORNER_RADIUS)

    var volumeRefraction by mutableFloatStateOf(TweakConfig.DEFAULT_VOLUME_REFRACTION)
    var volumeBlurRadius by mutableFloatStateOf(TweakConfig.DEFAULT_VOLUME_BLUR_RADIUS)
    var volumeHighlight by mutableStateOf(TweakConfig.DEFAULT_VOLUME_HIGHLIGHT)
    var volumeCornerRadius by mutableFloatStateOf(TweakConfig.DEFAULT_VOLUME_CORNER_RADIUS)

    var applying by mutableStateOf(false)
    var lastAppliedAt by mutableStateOf<String?>(null)
    var pendingSystemUiRestart by mutableStateOf(false)
    var lastSystemUiKillAt by mutableStateOf(0L)
    var scopeActionTip by mutableStateOf<String?>(null)
    var lastApplyRestartedLauncher by mutableStateOf(false)

    fun refreshPendingRestartFlag() {
        pendingSystemUiRestart = runCatching {
            AppPrefs.instance.needsProcessRestartForAppUpdate()
        }.getOrDefault(false)
    }

    /** 恢复「控制中心与通知中心」固定默认参数。 */
    fun restoreQsGlassDefaults() {
        refraction = TweakConfig.DEFAULT_REFRACTION
        blurRadius = TweakConfig.DEFAULT_BLUR_RADIUS
        highlight = TweakConfig.DEFAULT_HIGHLIGHT
        cornerRadius = TweakConfig.DEFAULT_CORNER_RADIUS
    }

    /** 恢复「桌面文件夹与 Dock 栏」固定默认参数。 */
    fun restoreLauncherGlassDefaults() {
        launcherRefraction = TweakConfig.DEFAULT_LAUNCHER_REFRACTION
        launcherBlurRadius = TweakConfig.DEFAULT_LAUNCHER_BLUR_RADIUS
        launcherHighlight = TweakConfig.DEFAULT_LAUNCHER_HIGHLIGHT
        launcherCornerRadius = TweakConfig.DEFAULT_LAUNCHER_CORNER_RADIUS
    }

    /** 恢复「音量条」固定默认参数。 */
    fun restoreVolumeGlassDefaults() {
        volumeRefraction = TweakConfig.DEFAULT_VOLUME_REFRACTION
        volumeBlurRadius = TweakConfig.DEFAULT_VOLUME_BLUR_RADIUS
        volumeHighlight = TweakConfig.DEFAULT_VOLUME_HIGHLIGHT
        volumeCornerRadius = TweakConfig.DEFAULT_VOLUME_CORNER_RADIUS
    }

    fun toConfig(): TweakConfig = TweakConfig(
        clockSeconds = clockSeconds,
        centerClock = centerClock,
        networkSpeed = networkSpeed,
        batteryPercent = batteryPercent,
        iconTint = iconTint,
        statusBarAlpha = statusBarAlpha,
        iosStatusBar = iosStatusBar,
        iosTime24Hour = iosTime24Hour,
        iosBatteryStyle = iosBatteryStyle,
        iosMuteIcon = iosMuteIcon,
        iosDoubleSignal = iosDoubleSignal,
        iosBluetoothIcon = iosBluetoothIcon,
        ios5gPosition = ios5gPosition,
        glassControlCenter = glassControlCenter,
        glassNotifications = glassNotifications,
        glassVolume = glassVolume,
        glassFolders = glassFolders,
        glassDock = glassDock,
        applyGlobally = applyGlobally,
        refraction = refraction,
        blurRadius = blurRadius,
        highlight = highlight,
        cornerRadius = cornerRadius,
        launcherRefraction = launcherRefraction,
        launcherBlurRadius = launcherBlurRadius,
        launcherHighlight = launcherHighlight,
        launcherCornerRadius = launcherCornerRadius,
        volumeRefraction = volumeRefraction,
        volumeBlurRadius = volumeBlurRadius,
        volumeHighlight = volumeHighlight,
        volumeCornerRadius = volumeCornerRadius
    )

    fun applyConfig(config: TweakConfig) {
        clockSeconds = config.clockSeconds
        centerClock = config.centerClock
        networkSpeed = config.networkSpeed
        batteryPercent = config.batteryPercent
        iconTint = config.iconTint
        statusBarAlpha = config.statusBarAlpha
        iosStatusBar = config.iosStatusBar
        iosTime24Hour = config.iosTime24Hour
        iosBatteryStyle = config.iosBatteryStyle
        iosMuteIcon = config.iosMuteIcon
        iosDoubleSignal = config.iosDoubleSignal
        iosBluetoothIcon = config.iosBluetoothIcon
        ios5gPosition = config.ios5gPosition
        glassControlCenter = config.glassControlCenter
        glassNotifications = config.glassNotifications
        glassVolume = config.glassVolume
        glassFolders = config.glassFolders
        glassDock = config.glassDock
        applyGlobally = config.applyGlobally
        refraction = config.refraction
        blurRadius = config.blurRadius
        highlight = config.highlight
        cornerRadius = config.cornerRadius
        launcherRefraction = config.launcherRefraction
        launcherBlurRadius = config.launcherBlurRadius
        launcherHighlight = config.launcherHighlight
        launcherCornerRadius = config.launcherCornerRadius
        volumeRefraction = config.volumeRefraction
        volumeBlurRadius = config.volumeBlurRadius
        volumeHighlight = config.volumeHighlight
        volumeCornerRadius = config.volumeCornerRadius
    }

    fun reset() = applyConfig(TweakConfig())
}

/** 本地持久化：主题 + 配置 + 崩溃守护快照。 */
class SettingsStore(context: Context) {

    val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("c17_glass", Context.MODE_PRIVATE)
    private val guardPrefs = context.getSharedPreferences("c17_glass_guard", Context.MODE_PRIVATE)

    fun loadTheme(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun saveTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    /** 首次启动的反倒卖声明是否已经由用户手动输入确认。 */
    fun isLicenseAccepted(): Boolean = prefs.getBoolean(KEY_LICENSE_ACCEPTED, false)

    fun acceptLicense() {
        prefs.edit().putBoolean(KEY_LICENSE_ACCEPTED, true).apply()
    }

    fun loadConfig(): TweakConfig {
        val defaultsVer = prefs.getInt(KEY_DEFAULTS_VERSION, 1)
        var refractionVal = prefs.getFloat("refraction", TweakConfig.DEFAULT_REFRACTION)
        val blurVal = prefs.getFloat("blur_radius", TweakConfig.DEFAULT_BLUR_RADIUS)
        val highlightVal = prefs.getBoolean("highlight", TweakConfig.DEFAULT_HIGHLIGHT)
        val cornerVal = prefs.getFloat("corner_radius", TweakConfig.DEFAULT_CORNER_RADIUS)
        val launcherBlurVal = prefs.getFloat("launcher_blur_radius", TweakConfig.DEFAULT_LAUNCHER_BLUR_RADIUS)
        if (defaultsVer < CURRENT_DEFAULTS_VERSION) {
            val edit = prefs.edit()
            if (kotlin.math.abs(refractionVal - 0.60f) < 0.005f || kotlin.math.abs(refractionVal - 0.78f) < 0.005f) {
                refractionVal = TweakConfig.DEFAULT_REFRACTION
                edit.putFloat("refraction", refractionVal)
            }
            val lr = prefs.getFloat("launcher_refraction", TweakConfig.DEFAULT_LAUNCHER_REFRACTION)
            if (kotlin.math.abs(lr - 0.78f) < 0.005f) {
                edit.putFloat("launcher_refraction", TweakConfig.DEFAULT_LAUNCHER_REFRACTION)
            }
            val vr = prefs.getFloat("volume_refraction", TweakConfig.DEFAULT_VOLUME_REFRACTION)
            if (kotlin.math.abs(vr - 0.78f) < 0.005f) {
                edit.putFloat("volume_refraction", TweakConfig.DEFAULT_VOLUME_REFRACTION)
            }
            edit.putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION).apply()
        }
        return TweakConfig(
            clockSeconds = prefs.getBoolean("clock_seconds", false),
            centerClock = prefs.getBoolean("center_clock", false),
            networkSpeed = prefs.getBoolean("network_speed", false),
            batteryPercent = prefs.getBoolean("battery_percent", true),
            iconTint = prefs.getBoolean("icon_tint", false),
            statusBarAlpha = prefs.getFloat("status_bar_alpha", 0.12f),
            iosStatusBar = prefs.getBoolean("ios_status_bar", true),
            iosTime24Hour = prefs.getBoolean("ios_time_24_hour", false),
            iosBatteryStyle = prefs.getBoolean("ios_battery_style", true),
            iosMuteIcon = prefs.getBoolean("ios_mute_icon", false),
            iosDoubleSignal = prefs.getBoolean("ios_double_signal", false),
            iosBluetoothIcon = prefs.getBoolean("ios_bluetooth_icon", false),
            ios5gPosition = runCatching {
                Ios5gPosition.valueOf(prefs.getString("ios_5g_position", Ios5gPosition.DEFAULT.name) ?: Ios5gPosition.DEFAULT.name)
            }.getOrDefault(Ios5gPosition.DEFAULT),
            glassControlCenter = prefs.getBoolean("glass_control_center", TweakConfig.DEFAULT_GLASS_CONTROL_CENTER),
            glassNotifications = prefs.getBoolean("glass_notifications", prefs.getBoolean("apply_globally", TweakConfig.DEFAULT_GLASS_NOTIFICATIONS)),
            glassVolume = prefs.getBoolean("glass_volume", prefs.getBoolean("apply_globally", TweakConfig.DEFAULT_GLASS_VOLUME)),
            glassFolders = prefs.getBoolean("glass_folders", TweakConfig.DEFAULT_GLASS_FOLDERS),
            glassDock = prefs.getBoolean("glass_dock", TweakConfig.DEFAULT_GLASS_DOCK),
            applyGlobally = prefs.getBoolean("apply_globally", TweakConfig.DEFAULT_APPLY_GLOBALLY),
            refraction = refractionVal,
            blurRadius = blurVal,
            highlight = highlightVal,
            cornerRadius = cornerVal,
            launcherRefraction = prefs.getFloat("launcher_refraction", refractionVal),
            launcherBlurRadius = launcherBlurVal,
            launcherHighlight = prefs.getBoolean("launcher_highlight", highlightVal),
            launcherCornerRadius = prefs.getFloat("launcher_corner_radius", cornerVal),
            volumeRefraction = prefs.getFloat("volume_refraction", refractionVal),
            volumeBlurRadius = prefs.getFloat("volume_blur_radius", TweakConfig.DEFAULT_VOLUME_BLUR_RADIUS),
            volumeHighlight = prefs.getBoolean("volume_highlight", highlightVal),
            volumeCornerRadius = prefs.getFloat("volume_corner_radius", cornerVal)
        )
    }

    fun saveConfig(config: TweakConfig) {
        prefs.edit()
            .putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION)
            .putBoolean("clock_seconds", config.clockSeconds)
            .putBoolean("center_clock", config.centerClock)
            .putBoolean("network_speed", config.networkSpeed)
            .putBoolean("battery_percent", config.batteryPercent)
            .putBoolean("icon_tint", config.iconTint)
            .putFloat("status_bar_alpha", config.statusBarAlpha)
            .putBoolean("ios_status_bar", config.iosStatusBar)
            .putBoolean("ios_time_24_hour", config.iosTime24Hour)
            .putBoolean("ios_battery_style", config.iosBatteryStyle)
            .putBoolean("ios_mute_icon", config.iosMuteIcon)
            .putBoolean("ios_double_signal", config.iosDoubleSignal)
            .putBoolean("ios_bluetooth_icon", config.iosBluetoothIcon)
            .putString("ios_5g_position", config.ios5gPosition.name)
            .putBoolean("glass_control_center", config.glassControlCenter)
            .putBoolean("glass_notifications", config.glassNotifications)
            .putBoolean("glass_volume", config.glassVolume)
            .putBoolean("glass_folders", config.glassFolders)
            .putBoolean("glass_dock", config.glassDock)
            .putBoolean("apply_globally", config.applyGlobally)
            .putFloat("refraction", config.refraction)
            .putFloat("blur_radius", config.blurRadius)
            .putBoolean("highlight", config.highlight)
            .putFloat("corner_radius", config.cornerRadius)
            .putFloat("launcher_refraction", config.launcherRefraction)
            .putFloat("launcher_blur_radius", config.launcherBlurRadius)
            .putBoolean("launcher_highlight", config.launcherHighlight)
            .putFloat("launcher_corner_radius", config.launcherCornerRadius)
            .putFloat("volume_refraction", config.volumeRefraction)
            .putFloat("volume_blur_radius", config.volumeBlurRadius)
            .putBoolean("volume_highlight", config.volumeHighlight)
            .putFloat("volume_corner_radius", config.volumeCornerRadius)
            .apply()
    }

    /** 下发前保存「上一份已确认配置」，崩溃时按它回退。 */
    fun saveGuardSnapshot(json: String) {
        guardPrefs.edit().putString(KEY_GUARD_SNAPSHOT, json).apply()
    }

    fun loadGuardSnapshot(): String? = guardPrefs.getString(KEY_GUARD_SNAPSHOT, null)

    fun clearGuardSnapshot() {
        guardPrefs.edit().remove(KEY_GUARD_SNAPSHOT).apply()
    }

    /** 最近一次自动回滚记录，重启 App 后仍能看到。 */
    fun saveLastRevert(stamp: String, keys: String, reason: String) {
        guardPrefs.edit()
            .putString(KEY_REVERT_STAMP, stamp)
            .putString(KEY_REVERT_KEYS, keys)
            .putString(KEY_REVERT_REASON, reason)
            .apply()
    }

    fun loadLastRevert(): Triple<String, String, String>? {
        val stamp = guardPrefs.getString(KEY_REVERT_STAMP, null) ?: return null
        return Triple(
            stamp,
            guardPrefs.getString(KEY_REVERT_KEYS, "") ?: "",
            guardPrefs.getString(KEY_REVERT_REASON, "") ?: ""
        )
    }

    fun clearLastRevert() {
        guardPrefs.edit()
            .remove(KEY_REVERT_STAMP)
            .remove(KEY_REVERT_KEYS)
            .remove(KEY_REVERT_REASON)
            .apply()
    }

    fun needsProcessRestartForAppUpdate(): Boolean =
        prefs.getString(KEY_APPLIED_APP_STAMP, null) != currentAppStamp

    fun markAppliedAppStamp() {
        prefs.edit().putString(KEY_APPLIED_APP_STAMP, currentAppStamp).apply()
    }

    private val currentAppStamp: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val ver = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "$ver:${info.lastUpdateTime}"
    }.getOrDefault("0:0")

    private companion object {
        const val CURRENT_DEFAULTS_VERSION = 4
        const val KEY_DEFAULTS_VERSION = "defaults_version"
        const val KEY_THEME = "theme_mode"
        const val KEY_APPLIED_APP_STAMP = "applied_app_stamp"
        const val KEY_LICENSE_ACCEPTED = "license_accepted"
        const val KEY_GUARD_SNAPSHOT = "guard_snapshot"
        const val KEY_REVERT_STAMP = "guard_revert_stamp"
        const val KEY_REVERT_KEYS = "guard_revert_keys"
        const val KEY_REVERT_REASON = "guard_revert_reason"
    }
}

object AppPrefs {
    private var store: SettingsStore? = null

    fun init(context: Context) {
        if (store == null) {
            store = SettingsStore(context.applicationContext)
        }
    }

    val instance: SettingsStore
        get() = store ?: error("AppPrefs.init(context) 未调用")
}
