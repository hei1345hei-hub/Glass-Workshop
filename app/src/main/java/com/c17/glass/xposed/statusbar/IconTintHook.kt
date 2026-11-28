package com.c17.glass.xposed.statusbar

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.widget.ImageView

/**
 * 状态栏图标着色。
 *
 * ColorOS 17 的系统图标有一部分仍是 StatusBarIconView（通知图标、部分系统槽位），
 * 这里在它换 drawable 时统一盖上主题蓝滤镜；电池与信号由各自的绘制器直接用 inkColor 上色。
 * Compose 绘制的系统图标暂不受影响，真机上会通过 hook.log 观察覆盖率。
 */
object IconTintHook : StatusbarHook {
    override val id = "icon-tint"
    override val label = "状态栏图标着色"

    override fun install(env: HookEnv) {
        if (!env.config.iconTint) {
            HookLog.info("[$id] 选项关闭，图标保持系统默认色")
            return
        }
        val tint = env.config.iconTintColor
        val tintLabel = hexColor(tint)
        val iconViewClass = env.findClass("com.android.systemui.statusbar.StatusBarIconView")
        if (iconViewClass == null) {
            HookLog.warn("[$id] 找不到 StatusBarIconView，仅电池/信号会着色（不接管通用 ImageView）")
            HookLog.ok("[$id] 着色入口已装：$tintLabel（仅电池/信号）")
            return
        }

        val imageViewClass = ImageView::class.java
        val setImage = imageViewClass.findMethodsNamed("setImageDrawable").firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Drawable::class.java
        }
        if (setImage == null) {
            HookLog.warn("[$id] 找不到 ImageView.setImageDrawable")
            return
        }

        var tinted = 0
        env.hookAfter(setImage, "$id/setImageDrawable") { chain ->
            val view = chain.getThisObject() as? ImageView ?: return@hookAfter
            if (!iconViewClass.isInstance(view)) return@hookAfter
            val drawable = chain.getArg(0) as? Drawable ?: view.drawable ?: return@hookAfter
            runCatching { drawable.setColorFilter(tint, PorterDuff.Mode.SRC_IN) }
            if (tinted++ == 0) {
                HookLog.ok("[$id] 开始着色：$tintLabel（statusbar icon）")
            }
        }
        HookLog.ok("[$id] 着色入口已装：$tintLabel（StatusBarIconView）")
    }

    private fun hexColor(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
