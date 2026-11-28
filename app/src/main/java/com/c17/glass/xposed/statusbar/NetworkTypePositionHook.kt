package com.c17.glass.xposed.statusbar

import android.view.View
import android.view.ViewGroup

/**
 * 5G / 网络类型图标位置。
 *
 * ColorOS 17 的蜂窝状态栏有两条实现路径：
 *  1) 视图版：ModernStatusBarMobileView 里 mobile_type_container（5G 文本）跟在信号格后面；
 *     这里在它 attach / 重新配置布局后，把类型容器挪到父容器最后 → 视觉上贴着电量。
 *  2) Compose 版：OplusStackedMobileIconLayoutStrategyKt 里的 NetworkTypeText 节点，
 *     位置由布局策略决定，运行时挪动需要真机逐帧调试；本文件先只做探测与日志。
 *
 * 默认位置（DEFAULT）不做任何改动。
 */
object NetworkTypePositionHook : StatusbarHook {
    override val id = "network-type-position"
    override val label = "5G 图标位置"

    override fun install(env: HookEnv) {
        if (!env.config.networkTypeBesideBattery) {
            HookLog.info("[$id] 默认位置，不改动网络类型图标")
            return
        }

        val modernClass = env.findClass(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"
        )
        if (modernClass != null) {
            HookLog.ok("[$id] 找到视图版移动信号：${modernClass.name}")
            modernClass.findMethodsNamed("onAttachedToWindow").forEach { method ->
                env.hookAfter(method, "$id/attached") { chain ->
                    val view = chain.getThisObject() as? View ?: return@hookAfter
                    moveTypeBesideBattery(view)
                }
                HookLog.info("[$id] 已挂 onAttachedToWindow")
            }
            modernClass.findMethodsNamed("configureLayoutForNewStatusBarIcons").forEach { method ->
                env.hookAfter(method, "$id/configure") { chain ->
                    val view = chain.getThisObject() as? View ?: return@hookAfter
                    moveTypeBesideBattery(view)
                }
                HookLog.info("[$id] 已挂 configureLayoutForNewStatusBarIcons")
            }
        } else {
            HookLog.warn("[$id] 没有 ModernStatusBarMobileView")
        }

        val composeClass = env.findClass(
            "com.android.systemui.statusbar.pipeline.shared.ui.composable.OplusStackedMobileIconLayoutStrategyKt"
        )
        if (composeClass != null) {
            HookLog.warn(
                "[$id] 检测到 Compose 蜂窝布局（OplusStackedMobileIconLayoutStrategyKt），" +
                    "该路径的位置调整需真机验证，本轮先不动"
            )
        }
    }

    /** 把 mobile_type_container / mobile_type 挪到父容器末尾（最靠近电量）。 */
    private fun moveTypeBesideBattery(root: View) {
        runCatching {
            val res = root.resources ?: return
            val containerId = res.getIdentifier("mobile_type_container", "id", "com.android.systemui")
            val typeId = res.getIdentifier("mobile_type", "id", "com.android.systemui")
            val target = when {
                containerId != 0 -> root.findViewById<View>(containerId)
                typeId != 0 -> root.findViewById<View>(typeId)
                else -> null
            } ?: return
            val parent = target.parent as? ViewGroup ?: return
            if (parent.childCount <= 1) return
            if (parent.getChildAt(parent.childCount - 1) === target) return
            parent.removeView(target)
            parent.addView(target, parent.childCount)
            HookLog.ok("[$id] 网络类型已移动到信号组末尾（贴近电量侧）")
        }.onFailure { HookLog.error("[$id] 调整位置失败", it) }
    }
}
