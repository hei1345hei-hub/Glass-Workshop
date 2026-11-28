package com.c17.glass.xposed.statusbar

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * 单/双层信号图标。
 *
 * ColorOS 17 的移动信号不是程序化 SignalDrawable，而是按等级拆开的资源：
 *   stat_signal_lte_signal_stacked_primary_0~4 / _secondary_0~4
 *   stat_signal_lte_signal_0~4 / stat_signal_signal_lte_single_0~4
 * 所以从 Resources.getDrawable(id) 这一层替换最稳（Compose 与 View 都走它）。
 *
 * 单层：副卡资源返回透明、主卡画 iOS 单排 4 柱。
 * 双层：完全交回系统原生堆叠样式（ColorOS 的双排本身就和 iPhone 很接近）。
 */
object SignalIconHook : StatusbarHook {
    override val id = "signal-icon"
    override val label = "单/双层信号图标"

    private val nameCache = ConcurrentHashMap<Int, String>()
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun install(env: HookEnv) {
        if (env.config.iosDoubleSignal) {
            HookLog.info("[$id] 双层模式：保留系统原生堆叠信号，不做替换")
            return
        }

        val targets = listOfNotNull(
            runCatching { Resources::class.java.getDeclaredMethod("getDrawable", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching {
                Resources::class.java.getDeclaredMethod("getDrawable", Int::class.javaPrimitiveType, Theme::class.java)
            }.getOrNull(),
            runCatching {
                Resources::class.java.getDeclaredMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Theme::class.java
                )
            }.getOrNull()
        )
        if (targets.isEmpty()) {
            HookLog.warn("[$id] 找不到 Resources.getDrawable，无法替换信号图标")
            return
        }

        var installed = 0
        targets.forEach { method ->
            val ok = env.hookReplace(method, "$id/${method.name}") { chain ->
                val native: Drawable? = try {
                    chain.proceed() as? Drawable
                } catch (t: Throwable) {
                    HookLog.error("[$id] 原 getDrawable 失败", t)
                    null
                }
                val res = chain.getThisObject() as? Resources
                val id = chain.getArg(0) as? Int ?: 0
                if (res == null || id == 0 || native == null) return@hookReplace native

                val decorated = decorate(res, id, native, env.config)
                decorated ?: native
            }
            if (ok) installed++
        }
        HookLog.info("[$id] 拦截入口已装：$installed/${targets.size}，单层模式")
    }

    /** 命中移动信号资源时返回替换 drawable，否则 null（用系统原图）。 */
    private fun decorate(
        res: Resources,
        id: Int,
        native: Drawable,
        config: HookConfig
    ): Drawable? {
        val name = nameOf(res, id) ?: return null
        if (!IosSignalDrawable.isMobileSignal(name)) return null
        val level = IosSignalDrawable.levelOf(name) ?: return null

        if (IosSignalDrawable.isSecondary(name)) {
            if (reported.add("secondary-hidden")) {
                HookLog.ok("[$id] 副卡信号资源已隐藏：$name")
            }
            return hiddenSignalDrawable(native)
        }

        val density = res.displayMetrics.density
        val w = if (native.intrinsicWidth > 0) native.intrinsicWidth else dp(density, 16f)
        val h = if (native.intrinsicHeight > 0) native.intrinsicHeight else dp(density, 14f)
        if (reported.add(name)) {
            HookLog.ok("[$id] 替换信号资源：$name → iOS 单排（$level 格）")
        }
        return IosSignalDrawable(
            bars = level,
            fallbackInk = HookRuntime.inkColor(config),
            intrinsicW = w,
            intrinsicH = h,
            density = density
        )
    }

    private fun nameOf(res: Resources, id: Int): String? {
        nameCache[id]?.let { return it.ifEmpty { null } }
        val name = runCatching { res.getResourceEntryName(id) }.getOrNull()
        nameCache[id] = name ?: ""
        return name
    }
}
