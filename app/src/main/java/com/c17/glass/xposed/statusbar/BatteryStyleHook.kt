package com.c17.glass.xposed.statusbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 仿 iOS 胶囊电池。
 *
 * ColorOS 17 的状态栏电池由 StatBatteryMeterView 里的
 * HorizontalBatteryContentDrawable 绘制（参考 StatusC17）。
 * 这里接管它的 draw(Canvas)，改用 IosBatteryPainter 绘制，原实现不再执行。
 */
object BatteryStyleHook : StatusbarHook {
    override val id = "battery-style"
    override val label = "仿 iOS 胶囊电池"

    private const val METER_CLASS =
        "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView"
    private const val DRAWABLE_CLASS =
        "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable"

    override fun install(env: HookEnv) {
        if (!env.config.iosBatteryStyle) {
            HookLog.info("[$id] 选项关闭，保持原生电池")
            return
        }

        HookRuntime.startBatteryWatch()
        hookMeterContext(env)

        val drawableClass = env.findClass(DRAWABLE_CLASS)
        if (drawableClass == null) {
            HookLog.warn("[$id] 找不到 $DRAWABLE_CLASS，本 ROM 暂不支持仿电池")
            return
        }
        HookLog.ok("[$id] 找到电池 Drawable：$DRAWABLE_CLASS")

        hookColorCapture(env, drawableClass)
        hookDraw(env, drawableClass)
    }

    /** 电池 View 挂到窗口时先拿到 Context，后面的 hook 才能读资源和字体。 */
    private fun hookMeterContext(env: HookEnv) {
        val meter = env.findClass(METER_CLASS)
        if (meter == null) {
            HookLog.warn("[$id] 找不到 $METER_CLASS（ROM 可能不同）")
            return
        }
        HookLog.ok("[$id] 找到电池 View：$METER_CLASS")

        val attach = meter.findMethodsNamed("onAttachedToWindow")
            .firstOrNull { it.parameterTypes.isEmpty() } ?: return
        env.hookAfter(attach, "$id/meterAttach") { chain ->
            (chain.getThisObject() as? View)?.context?.let { HookRuntime.initContext(it) }
        }
    }

    /** ColorOS 通过 setColors(int,int,int) 把深浅色传给电池 drawable，这里顺手记下来。 */
    private fun hookColorCapture(env: HookEnv, drawableClass: Class<*>) {
        var hooked = false
        drawableClass.findMethodsNamed("setColors")
            .filter { it.parameterTypes.size == 3 && it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType } }
            .forEach { method ->
                val ok = env.hookBefore(method, "$id/colors") { chain ->
                    HookRuntime.captureBatteryColors(
                        chain.getArg(0) as Int,
                        chain.getArg(1) as Int,
                        chain.getArg(2) as Int
                    )
                }
                hooked = hooked || ok
            }
        if (!hooked) {
            HookLog.warn("[$id] 没挂到 setColors，图标颜色回退为白色")
        }
    }

    private fun hookDraw(env: HookEnv, drawableClass: Class<*>) {
        val drawMethod = drawableClass.findMethodsNamed("draw").firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Canvas::class.java
        }
        if (drawMethod == null) {
            HookLog.warn("[$id] 找不到 draw(Canvas)，无法接管绘制")
            return
        }

        var firstDrawLogged = false
        env.hookReplace(drawMethod, "$id/draw") { chain ->
            val drawable = chain.getThisObject() as? Drawable
                ?: return@hookReplace chain.proceed()

            drawable.viewContext()?.let { HookRuntime.initContext(it) }
            HookRuntime.trackBatteryDrawable(drawable)

            val level = drawable.readInt("getBatteryLevel")
                ?: drawable.readFieldInt("batteryLevel")
                ?: HookRuntime.batteryLevel
            val charging = HookRuntime.batteryCharging ||
                (drawable.readInt("getChargeIconId") ?: 0) > 0

            if (!firstDrawLogged) {
                firstDrawLogged = true
                HookLog.ok(
                    "[$id] 首次接管电池绘制：bounds=${drawable.bounds.width()}x${drawable.bounds.height()} " +
                        "level=$level charging=$charging showPercent=${env.config.batteryPercent}"
                )
            }

            IosBatteryPainter.draw(
                canvas = chain.getArg(0) as Canvas,
                bounds = drawable.bounds,
                level = level,
                charging = charging,
                showPercent = env.config.batteryPercent,
                foreground = HookRuntime.inkColor(env.config),
                background = HookRuntime.batteryBackground
            )
            null
        }
    }

    private fun Drawable.viewContext(): Context? = runCatching {
        javaClass.getMethod("getContext").invoke(this) as? Context
    }.getOrNull()

    private fun Drawable.readInt(methodName: String): Int? = runCatching {
        javaClass.getMethod(methodName).invoke(this) as? Int
    }.getOrNull()

    /** 字段可能定义在父类，逐层往上找。 */
    private fun Drawable.readFieldInt(fieldName: String): Int? {
        var type: Class<*>? = javaClass
        while (type != null) {
            val field = runCatching {
                type.getDeclaredField(fieldName).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) {
                return runCatching { field.getInt(this) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }
}
