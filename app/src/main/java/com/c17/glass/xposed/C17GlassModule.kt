package com.c17.glass.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.c17.glass.xposed.glass.AssistantGlassHook
import com.c17.glass.xposed.glass.LauncherGlassHook
import com.c17.glass.xposed.statusbar.HookConfig
import com.c17.glass.xposed.statusbar.HookEnv
import com.c17.glass.xposed.statusbar.HookLog
import com.c17.glass.xposed.statusbar.statusbarHooks

/**
 * LSPosed 入口。
 *
 * 注入时机：
 *  1) `com.android.systemui`：仅安装控制中心 / 通知中心 / 音量液态玻璃 Hook；
 *     状态栏仍在开发中，当前版本不安装任何状态栏样式 Hook。
 *  2) `com.android.launcher`：安装桌面文件夹与底部 Dock 栏液态玻璃 Hook；
 *  3) `com.coloros.assistantscreen`：仅安装负一屏顶栏液态玻璃 Hook，卡片与浮窗保持原生；
 * 三端统一读取 `/data/local/tmp/c17_glass/config.json` 并支持热重载。
 */
class C17GlassModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.info("模块已加载：process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        when (param.packageName) {
            SYSTEMUI_PACKAGE -> installSystemUi(param)
            LAUNCHER_PACKAGE -> installLauncher(param)
            ASSISTANT_PACKAGE -> installAssistant(param)
            else -> HookLog.info("跳过非目标进程：${param.packageName}")
        }
    }

    private fun installSystemUi(param: PackageLoadedParam) {
        HookLog.banner("$VERSION_NAME/systemui")
        if (HookConfig.isExplicitlyDisabled()) {
            HookLog.warn("检测到已还原安全基线标记，保持原生界面")
            return
        }
        val config = HookConfig.read(quiet = true)
        if (!config.available) {
            HookLog.warn("SystemUI 直读 config.json 暂不可用，先按默认参数挂载并在获得 Context 后同步配置")
        }
        HookLog.info(
            "配置：cc=${config.glassControlCenter} notif=${config.glassNotifications} " +
                "vol=${config.glassVolume} folders=${config.glassFolders} dock=${config.glassDock} " +
                "global=${config.applyGlobally} qs(r=${config.refraction},b=${config.blurRadius}) " +
                "launcher(r=${config.launcherRefraction},b=${config.launcherBlurRadius}) " +
                "vol(r=${config.volumeRefraction},b=${config.volumeBlurRadius})；状态栏保持系统原样"
        )

        val env = HookEnv(this, param.defaultClassLoader, config)
        var mounted = 0
        statusbarHooks().forEach { hook ->
            try {
                hook.install(env)
                mounted++
                HookLog.info("[${hook.id}] ${hook.label} 安装流程结束")
            } catch (t: Throwable) {
                HookLog.error("[${hook.id}] 安装抛出异常（已隔离）", t)
            }
        }
        HookLog.info("注入完成：$mounted/${statusbarHooks().size} 个能力已进入安装流程")
    }

    private fun installLauncher(param: PackageLoadedParam) {
        HookLog.banner("$VERSION_NAME/launcher")
        if (HookConfig.isExplicitlyDisabled()) {
            HookLog.warn("检测到已还原安全基线标记，跳过桌面模块挂载")
            return
        }
        val config = HookConfig.read(quiet = true)
        if (!config.available) {
            HookLog.warn("桌面直读 config.json 暂不可用，先按默认参数挂载并在获得 Context 后同步配置")
        }
        val env = HookEnv(this, param.defaultClassLoader, config)
        try {
            LauncherGlassHook.install(env)
        } catch (t: Throwable) {
            HookLog.error("[launcher-glass] 安装抛出异常（已隔离）", t)
        }
    }

    private fun installAssistant(param: PackageLoadedParam) {
        HookLog.banner("$VERSION_NAME/assistant")
        if (HookConfig.isExplicitlyDisabled()) {
            HookLog.warn("检测到已还原安全基线标记，跳过负一屏模块挂载")
            return
        }
        val config = HookConfig.read(quiet = true)
        if (!config.available) {
            HookLog.warn("负一屏直读 config.json 暂不可用，先按默认参数挂载并在获得 Context 后同步配置")
        }
        val env = HookEnv(this, param.defaultClassLoader, config)
        try {
            AssistantGlassHook.install(env)
        } catch (t: Throwable) {
            HookLog.error("[assistant-glass] 安装抛出异常（已隔离）", t)
        }
    }

    companion object {
        /** 必须与 build.gradle.kts 的 versionName 保持一致，仅用于日志标识。 */
        const val VERSION_NAME = "1.0.25"
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val LAUNCHER_PACKAGE = "com.android.launcher"
        const val ASSISTANT_PACKAGE = "com.coloros.assistantscreen"
    }
}
