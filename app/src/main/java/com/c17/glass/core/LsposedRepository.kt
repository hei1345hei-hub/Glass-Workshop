package com.c17.glass.core

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val C17_MODULE_PACKAGE = "dev.puitheme.iosstatusbar"
private val SYSTEM_SCOPE_PACKAGES = setOf("com.android.systemui", "system", "android")
private val LAUNCHER_SCOPE_PACKAGES = setOf("com.android.launcher")

data class LsposedScope(
    val packageName: String,
    val label: String
)

data class LsposedModule(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val scopes: List<LsposedScope>
)

data class LsposedInfo(
    val installed: Boolean,
    val active: Boolean,
    val version: String?,
    val modules: List<LsposedModule>
) {
    val enabledModules: List<LsposedModule> get() = modules.filter { it.enabled }

    /** 当前 C17 状态栏对应的 LSPosed 模块：优先本应用自己的模块，其次 Status C17。 */
    val c17Module: LsposedModule?
        get() = modules.firstOrNull { it.packageName == "com.c17.glass" }
            ?: modules.firstOrNull { it.packageName == C17_MODULE_PACKAGE }
            ?: modules.firstOrNull { it.label.contains("C17", ignoreCase = true) }

    /** 是否已经在 LSPosed 里给 C17 模块勾选了系统作用域。 */
    val systemScopeGranted: Boolean
        get() = c17Module?.scopes.orEmpty().any { it.packageName in SYSTEM_SCOPE_PACKAGES }

    /** 是否已经在 LSPosed 里给 C17 模块勾选了系统桌面作用域。 */
    val launcherScopeGranted: Boolean
        get() = c17Module?.scopes.orEmpty().any { it.packageName in LAUNCHER_SCOPE_PACKAGES }
}

/**
 * 读取 LSPosed 的模块与作用域。
 *
 * LSPosed 把配置存在 /data/adb/lspd/config/modules_config.db。应用自身没有权限读，
 * 先用 root 把数据库（以及 -wal）复制到 cache，再用系统自带的 SQLiteDatabase 读取，
 * 不依赖设备上是否存在 sqlite3 / magisk --sqlite。
 */
object LsposedRepository {

    private const val MANAGER_PACKAGE = "org.lsposed.manager"

    private val DB_CANDIDATES = listOf(
        "/data/adb/lspd/config/modules_config.db",
        "/data/adb/lspd/config.db",
        "/data/adb/lspd/modules_config.db"
    )

    suspend fun load(context: Context): LsposedInfo = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val managerInfo = runCatching {
            pm.getPackageInfo(MANAGER_PACKAGE, 0)
        }.getOrNull()

        val lspdDir = RootShell.exec("test -d /data/adb/lspd && echo yes", 8_000L)
            .stdout.lines().any { it.trim() == "yes" }
        val installed = managerInfo != null || lspdDir
        val active = installed && RootShell.exec(
            "pidof lspd >/dev/null 2>&1 && echo yes",
            8_000L
        ).stdout.lines().any { it.trim() == "yes" }

        val version = managerInfo?.versionName?.takeIf { it.isNotBlank() }
            ?: frameworkVersion()
            ?: RootShell.readProp("ro.lsposed.version").takeIf { it.isNotBlank() }

        val modules = if (installed) {
            readModules(context, pm)
        } else {
            emptyList()
        }

        LsposedInfo(
            installed = installed,
            active = active,
            version = version,
            modules = modules
        )
    }

    /** 首页轻量检测：只判断 LSPosed 是否安装并运行，不读取模块数据库。 */
    suspend fun checkActive(context: Context): Boolean = withContext(Dispatchers.IO) {
        val managerInfo = runCatching {
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        }.getOrNull()
        val lspdDir = RootShell.exec("test -d /data/adb/lspd && echo yes", 8_000L)
            .stdout.lines().any { it.trim() == "yes" }
        val installed = managerInfo != null || lspdDir
        installed && RootShell.exec(
            "pidof lspd >/dev/null 2>&1 && echo yes",
            8_000L
        ).stdout.lines().any { it.trim() == "yes" }
    }

    private suspend fun frameworkVersion(): String? {
        val output = RootShell.exec(
            "grep -m1 -E '^version' /data/adb/modules/*lsposed*/module.prop 2>/dev/null",
            8_000L
        ).stdout
        return output.lineSequence()
            .map { it.substringAfter('=', "").trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private suspend fun readModules(context: Context, pm: PackageManager): List<LsposedModule> {
        val dbPath = findDatabase()
        if (dbPath == null) return emptyList()

        val local = File(context.cacheDir, "lsposed_modules.db")
        deleteLocalDb(local)

        RootShell.exec(
            buildString {
                append("cp -f '").append(dbPath).append("' '").append(local.absolutePath).append("' 2>/dev/null; ")
                append("cp -f '").append(dbPath).append("-wal' '").append(local.absolutePath).append("-wal' 2>/dev/null; ")
                append("chmod 666 '").append(local.absolutePath).append("' 2>/dev/null; ")
                append("chmod 666 '").append(local.absolutePath).append("-wal' 2>/dev/null; ")
                append("echo ok")
            },
            20_000L
        )

        Log.d("C17Lsp", "local exists=${local.exists()} len=${local.length()}")
        if (!local.exists()) return emptyList()

        return runCatching {
            val db = SQLiteDatabase.openDatabase(
                local.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.use { readModulesFromDb(it, pm) }
        }.onFailure {
            Log.e("C17Lsp", "read db failed", it)
        }.getOrDefault(emptyList()).also {
            deleteLocalDb(local)
        }
    }

    private suspend fun findDatabase(): String? {
        val output = RootShell.exec(
            "ls -1 " + DB_CANDIDATES.joinToString(" ") + " 2>/dev/null",
            10_000L
        ).stdout
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.endsWith(".db") }
    }

    private fun readModulesFromDb(db: SQLiteDatabase, pm: PackageManager): List<LsposedModule> {
        val moduleColumns = tableColumns(db, "modules")
        val modulePkgColumn = when {
            "module_pkg_name" in moduleColumns -> "module_pkg_name"
            "pkg_name" in moduleColumns -> "pkg_name"
            else -> return emptyList()
        }
        val scopeColumns = tableColumns(db, "scope")
        val scopePkgColumn = when {
            "app_pkg_name" in scopeColumns -> "app_pkg_name"
            "pkg_name" in scopeColumns -> "pkg_name"
            else -> return emptyList()
        }

        val scopesByPackage = mutableMapOf<String, MutableList<String>>()
        val moduleRows = mutableListOf<ModuleRow>()

        if ("mid" in moduleColumns && "mid" in scopeColumns) {
            // 旧版 LSPosed：modules(mid, module_pkg_name, enabled...)，scope(mid, app_pkg_name)
            val enabledColumn = "enabled".takeIf { it in moduleColumns }
            val midToPackage = mutableMapOf<Int, String>()
            val sql = buildString {
                append("SELECT mid, ").append(modulePkgColumn)
                if (enabledColumn != null) append(", ").append(enabledColumn)
                append(" FROM modules")
            }
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val mid = cursor.getInt(0)
                    val pkg = cursor.getStringOrNull(1)?.trim().orEmpty()
                    if (pkg.isBlank()) continue
                    val enabled = if (enabledColumn == null) true else cursor.getInt(2) != 0
                    midToPackage[mid] = pkg
                    moduleRows += ModuleRow(pkg, enabled)
                }
            }
            db.rawQuery("SELECT mid, $scopePkgColumn FROM scope", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val mid = cursor.getInt(0)
                    val pkg = cursor.getStringOrNull(1)?.trim().orEmpty()
                    val modulePkg = midToPackage[mid] ?: continue
                    if (pkg.isBlank()) continue
                    val list = scopesByPackage.getOrPut(modulePkg) { mutableListOf() }
                    if (pkg !in list) list += pkg
                }
            }
        } else {
            // 新版 LSPosed：modules(module_pkg_name, apk_path)，启用状态在 modules_state，
            // scope 直接按 module_pkg_name 关联。
            val stateColumns = tableColumns(db, "modules_state")
            val statePkgColumn = when {
                "module_pkg_name" in stateColumns -> "module_pkg_name"
                "pkg_name" in stateColumns -> "pkg_name"
                else -> null
            }
            val stateEnabledColumn = "enabled".takeIf { it in stateColumns }
            val enabledByPackage = mutableMapOf<String, Boolean>()
            if (statePkgColumn != null) {
                val sql = buildString {
                    append("SELECT ").append(statePkgColumn)
                    if (stateEnabledColumn != null) append(", ").append(stateEnabledColumn)
                    append(" FROM modules_state")
                }
                db.rawQuery(sql, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val pkg = cursor.getStringOrNull(0)?.trim().orEmpty()
                        if (pkg.isBlank()) continue
                        val enabled = if (stateEnabledColumn == null) true else cursor.getInt(1) != 0
                        enabledByPackage[pkg] = enabled || enabledByPackage[pkg] == true
                    }
                }
            }

            db.rawQuery("SELECT $modulePkgColumn FROM modules", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val pkg = cursor.getStringOrNull(0)?.trim().orEmpty()
                    if (pkg.isBlank()) continue
                    val enabled = if (statePkgColumn == null) true else enabledByPackage[pkg] == true
                    moduleRows += ModuleRow(pkg, enabled)
                }
            }

            val scopeModuleColumn = "module_pkg_name".takeIf { it in scopeColumns } ?: return emptyList()
            db.rawQuery("SELECT $scopeModuleColumn, $scopePkgColumn FROM scope", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val modulePkg = cursor.getStringOrNull(0)?.trim().orEmpty()
                    val pkg = cursor.getStringOrNull(1)?.trim().orEmpty()
                    if (modulePkg.isBlank() || pkg.isBlank()) continue
                    val list = scopesByPackage.getOrPut(modulePkg) { mutableListOf() }
                    if (pkg !in list) list += pkg
                }
            }
        }

        Log.d(
            "C17Lsp",
            "modules=${moduleRows.size} enabled=${moduleRows.count { it.enabled }} " +
                "scopedModules=${scopesByPackage.size} scopes=${scopesByPackage.values.sumOf { it.size }}"
        )

        return moduleRows.distinctBy { it.packageName }.map { row ->
            LsposedModule(
                packageName = row.packageName,
                label = appLabel(pm, row.packageName),
                enabled = row.enabled,
                scopes = scopesByPackage[row.packageName].orEmpty()
                    .sortedBy { appLabel(pm, it).lowercase() }
                    .map { LsposedScope(packageName = it, label = appLabel(pm, it)) }
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) columns += cursor.getString(nameIndex)
                }
            }
        }
        return columns
    }

    private fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
        val info = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun deleteLocalDb(file: File) {
        listOf(file, File(file.absolutePath + "-wal"), File(file.absolutePath + "-shm"))
            .forEach { runCatching { it.delete() } }
    }

    /** 检测模块 APK 是否已真正加载进目标进程（钩子是否注入）。无法检测时返回 null。 */
    suspend fun isModuleLoadedIn(context: Context, packageName: String): Boolean? =
        withContext(Dispatchers.IO) {
            val apkPath = runCatching {
                context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
            }.getOrNull() ?: return@withContext null
            val pid = RootShell.exec("pidof $packageName", 4_000L).stdout.trim()
                .split(' ').firstOrNull()
            if (pid.isNullOrBlank() || !pid.all(Char::isDigit)) return@withContext null
            val count = RootShell.exec(
                "grep -c '$apkPath' /proc/$pid/maps 2>/dev/null || true",
                4_000L
            ).stdout.trim().toIntOrNull() ?: return@withContext null
            count > 0
        }

    /** 重启系统界面与系统桌面进程，让两个作用域重新注入。 */
    suspend fun restartSystemUi(): ShellResult {
        SafetyManager.cancelWatchForManualRestart()
        val output = RootShell.exec("pidof com.android.systemui com.android.launcher", 8_000L).stdout
        val pids = output.split(' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        if (pids.isEmpty()) {
            return ShellResult(1, "", "未找到系统界面或系统桌面进程")
        }
        val res = RootShell.exec("kill -TERM ${pids.joinToString(" ")}; echo done", 8_000L)
        if (res.code == 0) {
            runCatching { AppPrefs.instance.markAppliedAppStamp() }
            TweakState.pendingSystemUiRestart = false
        }
        return res
    }

    private data class ModuleRow(val packageName: String, val enabled: Boolean)

    /** 强制停止所有作用域内的应用，让 LSPosed 在下次启动时重新注入。 */
    suspend fun restartScopes(packages: List<String>, excludePackage: String? = null): ShellResult {
        val targets = packages
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    it != "android" &&
                    it != "system" &&
                    it != "oplus" &&
                    it != excludePackage
            }
            .distinct()
        if (targets.isEmpty()) {
            return ShellResult(0, "没有可重启的作用域", "")
        }
        val command = targets.joinToString("\n") {
            "am force-stop '$it' >/dev/null 2>&1"
        } + "\necho done"
        return RootShell.exec(command, 30_000L)
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null
