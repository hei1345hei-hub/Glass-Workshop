package com.c17.glass.xposed.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import java.util.Collections
import java.util.WeakHashMap

// 四个图标 hook 共用的运行时：SystemUI 进程里的 Context、电量状态、图标颜色。
// 约定：所有回调都吞异常，只记日志，绝不影响 SystemUI。

object HookRuntime {

    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    var moduleContext: Context? = null
        private set

    /** 无论从 ActivityThread、View 还是 Drawable 拿到 Context，统一在此登记。 */
    fun initContext(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (appContext == null) {
            appContext = app
            HookLog.ok("已捕获 SystemUI Context：${app.packageName}")
        }
        if (moduleContext == null) {
            moduleContext = runCatching {
                app.createPackageContext("com.c17.glass", Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            if (moduleContext != null) {
                HookLog.ok("已初始化模块 PackageContext：com.c17.glass")
                IosBatteryPainter.loadFont(moduleContext)
            }
        }
        startBatteryWatch(app)
        MuteIconHook.onContextReady(app)
    }

    /** SystemUI 里拿 Application Context（ActivityThread.currentApplication）。 */
    fun context(): Context? {
        appContext?.let { return it }
        val ctx = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
        if (ctx != null) {
            initContext(ctx)
            return ctx
        }
        return appContext
    }

    // ---------------- 电量 ----------------

    @Volatile
    var batteryLevel: Int = 50
        private set

    @Volatile
    var batteryCharging: Boolean = false
        private set

    @Volatile
    private var batteryWatchStarted = false

    private val batteryDrawables: MutableSet<Drawable> =
        Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())

    /** 状态栏图标颜色（电池 drawable 的 setColors 会告诉我们当前是深色还是浅色）。 */
    @Volatile
    var iconColor: Int = Color.WHITE

    /** 电池 setColors 给的背景色，仿电池的底槽用它。 */
    @Volatile
    var batteryBackground: Int = Color.TRANSPARENT
        private set

    fun captureBatteryColors(foreground: Int, background: Int, singleTone: Int) {
        batteryBackground = background
        captureIconColor(foreground, background, singleTone)
    }

    fun trackBatteryDrawable(drawable: Drawable) {
        synchronized(batteryDrawables) { batteryDrawables.add(drawable) }
    }

    fun startBatteryWatch(targetCtx: Context? = null) {
        if (batteryWatchStarted) return
        val ctx = targetCtx ?: context() ?: return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                applyBatteryIntent(intent)
            }
        }
        runCatching {
            ctx.registerReceiver(receiver, filter)
            batteryWatchStarted = true
        }.onFailure { HookLog.error("注册电池广播失败", it) }
        runCatching { applyBatteryIntent(ctx.registerReceiver(null, filter)) }
        HookLog.info("电池状态监听已启动：level=$batteryLevel charging=$batteryCharging")
    }

    private fun applyBatteryIntent(intent: Intent?) {
        if (intent == null) return
        runCatching {
            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (rawLevel >= 0 && scale > 0) {
                batteryLevel = (rawLevel * 100 / scale).coerceIn(0, 100)
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            synchronized(batteryDrawables) {
                batteryDrawables.forEach { runCatching { it.invalidateSelf() } }
            }
        }.onFailure { HookLog.error("解析电池广播失败", it) }
    }

    // ---------------- 颜色 ----------------

    /**
     * 电池 drawable 的 setColors 一般给 (背景色, 填充色, 文字色)，
     * 取其中不透明度最高的那个作为状态栏前景色（浅色栏=黑，深色栏=白）。
     */
    fun captureIconColor(vararg colors: Int) {
        val best = colors.asSequence()
            .filter { Color.alpha(it) >= 0xC0 }
            .maxByOrNull { colorBrightness(it) }
        if (best != null) iconColor = best
    }

    private fun colorBrightness(color: Int): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** 当前应该用的图标颜色：开了着色就用主题蓝，否则跟随系统栏深浅色。 */
    fun inkColor(config: HookConfig): Int = if (config.iconTint) config.iconTintColor else iconColor
}
