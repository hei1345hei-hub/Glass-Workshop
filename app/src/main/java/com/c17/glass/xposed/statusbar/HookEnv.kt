package com.c17.glass.xposed.statusbar

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

// 本文件是 hook 的公共基础设施：运行环境 + 反射查找 + 安全挂载。
// 只由模块主入口维护；各图标 hook 直接使用，不要修改本文件。

/** 每个 hook 拿到的运行环境。 */
class HookEnv(
    val module: XposedModule,
    val classLoader: ClassLoader,
    val config: HookConfig
) {

    /** 按类名加载 SystemUI 内部类（走 SystemUI 自己的 ClassLoader）。 */
    fun findClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    /** 找不到时打一行日志，方便判断是不是 ColorOS 改过类名。 */
    fun requireClass(name: String): Class<*>? {
        val c = findClass(name)
        if (c == null) HookLog.warn("类不存在（ColorOS 可能改名）：$name")
        return c
    }

    /**
     * 在原方法执行「之前」插入逻辑。body 抛异常只会被记录，不会影响宿主。
     * 目标为空 / 挂载失败都返回 false，调用方无需处理。
     */
    fun hookBefore(target: Executable?, tag: String, body: (XposedInterface.Chain) -> Unit): Boolean =
        mount(target, tag) { chain ->
            runCatching { body(chain) }.onFailure { HookLog.error("$tag 回调异常", it) }
            chain.proceed()
        }

    /** 在原方法执行「之后」插入逻辑（先 proceed，拿到返回值后再处理）。 */
    fun hookAfter(target: Executable?, tag: String, body: (XposedInterface.Chain) -> Unit): Boolean =
        mount(target, tag) { chain ->
            val result = chain.proceed()
            runCatching { body(chain) }.onFailure { HookLog.error("$tag 回调异常", it) }
            result
        }

    /** 只观察不干预：不改返回值，仅执行 body 做记录/副作用。 */
    fun hookObserve(target: Executable?, tag: String, body: (XposedInterface.Chain) -> Unit): Boolean =
        mount(target, tag) { chain ->
            runCatching { body(chain) }.onFailure { HookLog.error("$tag 回调异常", it) }
            chain.proceed()
        }

    /**
     * 完全接管：body 返回什么就用什么，不再调用原方法。
     * 用于「自己画完就不让系统再画」的场景（电池、信号）。
     */
    fun hookReplace(target: Executable?, tag: String, body: (XposedInterface.Chain) -> Any?): Boolean =
        mount(target, tag) { chain ->
            runCatching { body(chain) }.getOrElse {
                HookLog.error("$tag 接管失败，回退系统实现", it)
                chain.proceed()
            }
        }

    private fun mount(
        target: Executable?,
        tag: String,
        handler: (XposedInterface.Chain) -> Any?
    ): Boolean {
        if (target == null) {
            HookLog.warn("跳过挂载（目标为空）：$tag")
            return false
        }
        return try {
            target.isAccessible = true
            module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain -> handler(chain) })
            HookLog.ok("已挂载：$tag")
            true
        } catch (t: Throwable) {
            HookLog.error("挂载失败：$tag", t)
            false
        }
    }
}

/** 一个状态栏图标能力。install 在 SystemUI 进程里被调用一次。 */
interface StatusbarHook {
    /** 稳定标识，写进 hook.log，例如 battery-style */
    val id: String

    /** 中文名，用于日志与交付清单 */
    val label: String

    fun install(env: HookEnv)
}

// ---------------- 反射小工具 ----------------

/** 先找 public 方法，再找 declared（含私有），命中后打开可访问。 */
fun Class<*>.findMethod(name: String, vararg types: Class<*>): Method? {
    runCatching { getMethod(name, *types) }.getOrNull()?.let { return it }
    return runCatching { getDeclaredMethod(name, *types).apply { isAccessible = true } }.getOrNull()
}

/** 按名字找出所有同名方法（用于签名不确定、按参数区分的情况）。 */
fun Class<*>.findMethodsNamed(name: String): List<Method> {
    val out = mutableListOf<Method>()
    runCatching { declaredMethods.forEach { if (it.name == name) out += it.apply { isAccessible = true } } }
    runCatching { methods.forEach { if (it.name == name && out.none { m -> m === it }) out += it } }
    return out
}

/** 沿父类向上查找字段（SystemUI 大量字段声明在父类里）。 */
fun Class<*>.findField(name: String): Field? {
    var c: Class<*>? = this
    while (c != null) {
        runCatching {
            c!!.getDeclaredField(name).let { it.isAccessible = true; return it }
        }
        c = c.superclass
    }
    return null
}

/** 找出所有指定类型的字段（不依赖字段名，绕开 ColorOS 改名）。 */
fun Class<*>.findFieldsOfType(type: Class<*>): List<Field> {
    val out = mutableListOf<Field>()
    var c: Class<*>? = this
    while (c != null) {
        runCatching {
            c!!.declaredFields.forEach { f ->
                if (type.isAssignableFrom(f.type) && out.none { it.name == f.name }) {
                    out += f.apply { isAccessible = true }
                }
            }
        }
        c = c.superclass
    }
    return out
}

/** 读字段，失败返回 null（字段名不确定时用 findFieldsOfType 兜底）。 */
fun Any.readField(field: Field): Any? = runCatching { field.get(this) }.getOrNull()

/** 方法调用，失败返回 null，绝不抛给宿主。 */
fun Any.invokeMethod(method: Method, vararg args: Any?): Any? =
    runCatching { method.invoke(this, *args) }.getOrNull()
