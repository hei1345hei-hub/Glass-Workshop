package com.c17.glass.xposed.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 仿 iOS 静音图标：静音/振动时在时间右侧补一个铃铛划掉图标。
 *
 * ColorOS 17 的左侧时间仍是 com.android.systemui.statusbar.policy.Clock（TextView），
 * 所以直接给这个 TextView 挂 drawableEnd，比自己往状态栏布局里塞 View 稳。
 * 图标资源取模块 APK 里的 ic_c17_silent（路径与 App 预览一致）。
 */
object MuteIconHook : StatusbarHook {
    override val id = "mute-icon"
    override val label = "仿 iOS 静音图标"

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"

    private val clocks: MutableSet<TextView> =
        Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())

    @Volatile
    private var watching = false

    @Volatile
    private var config: HookConfig? = null

    /** 只在铃声状态真正变化时打日志，避免 onDarkChanged 每次回调都刷屏。 */
    @Volatile
    private var lastMuted: Boolean? = null

    override fun install(env: HookEnv) {
        if (!env.config.iosMuteIcon) {
            HookLog.info("[$id] 选项关闭，不显示静音图标")
            return
        }
        config = env.config
        val clockClass = env.findClass(CLOCK_CLASS)
        if (clockClass == null) {
            HookLog.warn("[$id] 找不到 $CLOCK_CLASS，静音图标不生效")
            return
        }
        HookLog.ok("[$id] 找到时间控件：$CLOCK_CLASS")

        startRingerWatch()

        val attach = clockClass.findMethodsNamed("onAttachedToWindow")
            .firstOrNull { it.parameterTypes.isEmpty() }
        if (attach == null) {
            HookLog.warn("[$id] Clock 没有 onAttachedToWindow，无法挂静音图标")
            return
        }
        env.hookAfter(attach, "$id/attach") { chain ->
            (chain.getThisObject() as? TextView)?.let { clock ->
                HookRuntime.initContext(clock.context)
                clocks += clock
                HookLog.ok("[$id] Clock 挂载到窗口：clocks=${clocks.size}")
                startRingerWatch(clock.context)
                applyState(clock)
            }
        }
        clockClass.findMethodsNamed("onDarkChanged").forEach { m ->
            env.hookAfter(m, "$id/dark") { chain ->
                (chain.getThisObject() as? TextView)?.let { applyState(it) }
            }
        }
        clockClass.findMethodsNamed("setTextColor").forEach { m ->
            env.hookAfter(m, "$id/textColor") { chain ->
                (chain.getThisObject() as? TextView)?.let { applyState(it) }
            }
        }
        HookLog.info("[$id] 已挂载 Clock.onAttachedToWindow")
    }

    fun onContextReady(ctx: Context) {
        if (config?.iosMuteIcon == true) {
            startRingerWatch(ctx)
            applyAll()
        }
    }

    private fun startRingerWatch(targetCtx: Context? = null) {
        if (watching) return
        val ctx = targetCtx ?: HookRuntime.context() ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                HookLog.info("[$id] 收到铃声广播：action=${intent?.action}")
                applyAll()
            }
        }
        val ok = runCatching {
            ctx.registerReceiver(receiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
        }.isSuccess
        if (ok) {
            watching = true
            HookLog.ok("[$id] 已监听铃声模式变化：muted=${isMuted(ctx)}")
        } else {
            HookLog.warn("[$id] 注册铃声模式广播失败")
        }
    }

    private fun applyAll() {
        clocks.forEach { runCatching { applyState(it) } }
    }

    private fun applyState(clock: TextView) {
        val cfg = config ?: return
        val muted = isMuted(clock.context)
        if (lastMuted != muted) {
            lastMuted = muted
            HookLog.ok(if (muted) "[$id] 进入静音/振动模式，显示铃铛" else "[$id] 回到正常铃声，清除铃铛")
        }
        if (!muted) {
            clearIcon(clock)
            return
        }
        val drawable = muteIcon()
        if (drawable == null) {
            HookLog.warn("[$id] 静音模式但未能加载到 ic_c17_silent 图标")
            return
        }
        val px = dp(clock, 13f)
        drawable.setBounds(0, 0, px, px)
        val tint = if (cfg.iconTint) cfg.iconTintColor else clock.currentTextColor
        runCatching { drawable.setTintList(ColorStateList.valueOf(tint)) }
        clock.compoundDrawablePadding = dp(clock, 6f)
        clock.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
    }

    private fun clearIcon(clock: TextView) {
        clock.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        clock.compoundDrawablePadding = 0
    }

    private fun muteIcon(): Drawable? {
        val moduleCtx = HookRuntime.moduleContext ?: runCatching {
            val ctx = HookRuntime.context() ?: return null
            ctx.createPackageContext("com.c17.glass", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val id = moduleCtx.resources.getIdentifier("ic_c17_silent", "drawable", "com.c17.glass")
        if (id == 0) return null
        return runCatching { moduleCtx.resources.getDrawable(id, moduleCtx.theme).mutate() }.getOrNull()
            ?: runCatching { moduleCtx.resources.getDrawable(id, null).mutate() }.getOrNull()
    }

    private fun isMuted(ctx: Context): Boolean = runCatching {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return@runCatching false
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(false)

    private fun dp(view: TextView, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
