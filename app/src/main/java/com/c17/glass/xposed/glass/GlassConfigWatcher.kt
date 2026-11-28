package com.c17.glass.xposed.glass

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.c17.glass.xposed.statusbar.HookConfig
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 异步配置监听与低开销热重载调度器：
 *  - 绘制 / Uniform 热路径只检查本地内存节流戳并在必要时投递到后台单线程执行，不在主线程做文件 I/O 或 Binder IPC；
 *  - 同时监听 `Settings.Global` 中配置与禁用开关的 URI，用户在 App 内点「重启作用域」或「全部还原」时即时后台刷新。
 */
internal class GlassConfigWatcher(
    @Volatile var lastStamp: String,
    private val minIntervalMs: Long = 1_500L,
    private val onReload: (Context?, HookConfig?, Boolean) -> Unit
) {

    @Volatile
    private var lastScheduleMs: Long = 0L

    private val checking = AtomicBoolean(false)
    private val observerInstalled = AtomicBoolean(false)

    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    fun bindContext(context: Context?) {
        val resolved = HookConfig.resolveContext(context) ?: return
        val appCtx = resolved.applicationContext ?: resolved
        appContextRef = WeakReference(appCtx)
        if (!observerInstalled.compareAndSet(false, true)) return
        runCatching {
            val resolver = appCtx.contentResolver ?: return@runCatching
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    requestCheck(appCtx, force = true)
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    requestCheck(appCtx, force = true)
                }
            }
            resolver.registerContentObserver(
                Settings.Global.getUriFor(HookConfig.GLOBAL_CONFIG_KEY),
                false,
                observer
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(HookConfig.GLOBAL_DISABLED_KEY),
                false,
                observer
            )
        }
    }

    /** 在主线程/绘制路径调用：仅做时间戳比较，必要时异步投递检查，耗时 O(1)。 */
    fun scheduleCheck(context: Context? = null) {
        if (observerInstalled.get().not()) {
            bindContext(context)
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastScheduleMs < minIntervalMs) return
        lastScheduleMs = now
        enqueueCheck(context ?: appContextRef?.get())
    }

    fun requestCheck(context: Context? = null, force: Boolean = false) {
        if (context != null || observerInstalled.get().not()) bindContext(context)
        if (force) {
            lastScheduleMs = SystemClock.uptimeMillis()
        }
        enqueueCheck(context ?: appContextRef?.get())
    }

    private fun enqueueCheck(context: Context?) {
        if (!checking.compareAndSet(false, true)) return
        val rawCtx = context?.applicationContext ?: context ?: appContextRef?.get() ?: HookConfig.resolveContext(null)
        if (rawCtx != null && observerInstalled.get().not()) {
            bindContext(rawCtx)
        }
        val ctx = rawCtx?.applicationContext ?: rawCtx
        worker.execute {
            try {
                val stamp = HookConfig.stamp(ctx)
                if (stamp == "__DISABLED__") {
                    if (lastStamp != stamp) {
                        lastStamp = stamp
                        onReload(ctx, null, true)
                    }
                    return@execute
                }
                if (stamp.isEmpty() || stamp == lastStamp) return@execute
                val cfg = HookConfig.read(ctx, quiet = true)
                if (!cfg.available) return@execute
                lastStamp = stamp
                onReload(ctx, cfg, false)
            } catch (_: Throwable) {
            } finally {
                checking.set(false)
            }
        }
    }

    companion object {
        private val mainHandler by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Handler(Looper.getMainLooper())
        }

        private val worker by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "c17-glass-cfg").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 2
                }
            }
        }
    }
}
