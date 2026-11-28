package com.c17.glass.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.c17.glass.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkAsset: ReleaseAsset?
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val release: ReleaseInfo
    ) : UpdateCheckResult

    data class UpToDate(
        val currentVersion: String,
        val release: ReleaseInfo,
        val aheadOfRemote: Boolean
    ) : UpdateCheckResult

    data class Unavailable(
        val message: String,
        val pageUrl: String
    ) : UpdateCheckResult

    data class Error(
        val message: String,
        val detail: String,
        val pageUrl: String
    ) : UpdateCheckResult
}

/** 持久化的更新检测状态，切换页面不会中断正在进行的检查或下载。 */
object UpdateState {
    var checking by mutableStateOf(false)
    var result by mutableStateOf<UpdateCheckResult?>(null)
    var checkedAt by mutableStateOf<String?>(null)
    var showDialog by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var downloadProgress by mutableStateOf<Int?>(null)
    var downloadError by mutableStateOf<String?>(null)
    var downloadedApk by mutableStateOf<File?>(null)
}

/**
 * GitHub Release 版本检查与 APK 下载安装器。
 * 优先请求 GitHub Releases API；遇到 API 限流或需要兜底时回落到 `/releases/latest` 网页跳转解析。
 */
object UpdateChecker {

    private const val REPO_OWNER = "hei1345hei-hub"
    private const val REPO_NAME = "Glass-Workshop"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
    private const val LATEST_WEB_URL = "$RELEASES_PAGE_URL/latest"
    private const val API_LATEST_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val API_LIST_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=3"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "C17-Glass-Workshop/${BuildConfig.VERSION_NAME}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun startCheck() {
        if (UpdateState.checking || UpdateState.downloading) return
        scope.launch {
            UpdateState.checking = true
            UpdateState.downloadError = null
            UpdateState.downloadProgress = null
            UpdateState.downloadedApk = null

            val res = checkLatest()
            UpdateState.result = res
            UpdateState.checkedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            UpdateState.checking = false

            when (res) {
                is UpdateCheckResult.UpdateAvailable -> {
                    LogRepository.log(
                        LogLevel.SUCCESS,
                        "检测到新版本 ${res.release.tagName}",
                        "当前 v${res.currentVersion}，点击更新弹窗即可下载安装"
                    )
                    UpdateState.showDialog = true
                }

                is UpdateCheckResult.UpToDate -> {
                    LogRepository.log(
                        LogLevel.INFO,
                        "当前已是最新版本",
                        "本地 v${res.currentVersion} · GitHub 最新 ${res.release.tagName}"
                    )
                }

                is UpdateCheckResult.Unavailable -> {
                    LogRepository.log(LogLevel.WARN, "暂无公开更新", res.message)
                }

                is UpdateCheckResult.Error -> {
                    LogRepository.log(LogLevel.WARN, "检测更新失败", "${res.message}: ${res.detail}")
                }
            }
        }
    }

    fun startDownloadOrInstall(context: Context, release: ReleaseInfo, onFallbackOpenUrl: (String) -> Unit) {
        val asset = release.apkAsset
        if (asset == null) {
            onFallbackOpenUrl(release.htmlUrl)
            return
        }
        val cached = UpdateState.downloadedApk
        if (cached != null && cached.exists()) {
            installApk(context, cached).onFailure { err ->
                UpdateState.downloadError = err.message ?: "调起系统安装器失败，请改用浏览器下载"
            }
            return
        }
        if (UpdateState.downloading) return
        val appContext = context.applicationContext
        scope.launch {
            UpdateState.downloading = true
            UpdateState.downloadError = null
            UpdateState.downloadProgress = 0
            val download = downloadApk(appContext, asset) { pct ->
                scope.launch { UpdateState.downloadProgress = pct }
            }
            UpdateState.downloading = false
            download.onSuccess { file ->
                UpdateState.downloadedApk = file
                LogRepository.log(
                    LogLevel.SUCCESS,
                    "更新包下载完成",
                    "${asset.name} (${file.length()} bytes)"
                )
                installApk(appContext, file).onFailure { err ->
                    UpdateState.downloadError = err.message ?: "调起系统安装器失败，请改用浏览器下载"
                }
            }.onFailure { err ->
                UpdateState.downloadProgress = null
                val msg = err.message ?: "下载失败"
                UpdateState.downloadError = msg
                LogRepository.log(LogLevel.ERROR, "更新包下载失败", msg)
            }
        }
    }

    suspend fun checkLatest(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                fetchAndCompare(currentVersion)
            }.getOrElse { t ->
                val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                UpdateCheckResult.Error(
                    message = "无法连接 GitHub，请检查网络或代理设置",
                    detail = detail,
                    pageUrl = RELEASES_PAGE_URL
                )
            }
        }

    private fun fetchAndCompare(currentVersion: String): UpdateCheckResult {
        val latestResp = httpGet(API_LATEST_URL, accept = "application/vnd.github+json")
        if (latestResp.code == 200) {
            val release = parseReleaseObject(JSONObject(latestResp.body))
            return evaluateRelease(currentVersion, release)
        }

        // `/releases/latest` 对预发布版本或空发布列表返回 404，补充检查发布列表。
        if (latestResp.code == 404) {
            val listResp = httpGet(API_LIST_URL, accept = "application/vnd.github+json")
            if (listResp.code == 200) {
                val arr = JSONArray(listResp.body)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    return evaluateRelease(currentVersion, parseReleaseObject(obj))
                }
                return UpdateCheckResult.Unavailable(
                    message = "仓库暂无已发布的正式 Release",
                    pageUrl = RELEASES_PAGE_URL
                )
            }
        }

        // 未携带 Token 时 API 每小时限流 60 次（403/429），尝试公开网页重定向兜底。
        val webRelease = fetchLatestViaWebRedirect()
        if (webRelease != null) {
            return evaluateRelease(currentVersion, webRelease)
        }

        return when (latestResp.code) {
            403, 429 -> UpdateCheckResult.Error(
                message = "GitHub 接口请求受限，请稍后再试或直接前往浏览器查看",
                detail = "HTTP ${latestResp.code}",
                pageUrl = RELEASES_PAGE_URL
            )

            404 -> UpdateCheckResult.Unavailable(
                message = "未检测到公开 Release（仓库当前可能未公开或尚未发布版本）",
                pageUrl = RELEASES_PAGE_URL
            )

            else -> UpdateCheckResult.Error(
                message = "检查更新失败（HTTP ${latestResp.code}）",
                detail = latestResp.body.take(240),
                pageUrl = RELEASES_PAGE_URL
            )
        }
    }

    private fun evaluateRelease(currentVersion: String, release: ReleaseInfo): UpdateCheckResult {
        val cmp = compareVersions(release.versionName, currentVersion)
        return if (cmp > 0) {
            UpdateCheckResult.UpdateAvailable(currentVersion = currentVersion, release = release)
        } else {
            UpdateCheckResult.UpToDate(
                currentVersion = currentVersion,
                release = release,
                aheadOfRemote = cmp < 0
            )
        }
    }

    private fun parseReleaseObject(obj: JSONObject): ReleaseInfo {
        val tagName = obj.optString("tag_name").trim().ifBlank { obj.optString("name").trim() }
        val versionName = normalizeVersion(tagName)
        val title = obj.optString("name").trim().ifBlank { tagName }
        val notes = obj.optString("body").trim()
        val publishedAt = obj.optString("published_at").trim().take(10)
        val htmlUrl = obj.optString("html_url").trim().ifBlank { RELEASES_PAGE_URL }

        val assets = obj.optJSONArray("assets")
        var apkAsset: ReleaseAsset? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val dl = item.optString("browser_download_url").trim()
                if (!name.endsWith(".apk", ignoreCase = true) || dl.isBlank()) continue
                val candidate = ReleaseAsset(
                    name = name,
                    downloadUrl = dl,
                    sizeBytes = item.optLong("size", -1L)
                )
                if (apkAsset == null || name.contains("C17", ignoreCase = true)) {
                    apkAsset = candidate
                    if (name.contains("C17", ignoreCase = true)) break
                }
            }
        }

        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            notes = notes,
            publishedAt = publishedAt,
            htmlUrl = htmlUrl,
            apkAsset = apkAsset
        )
    }

    /**
     * 通过 `/releases/latest` 的 302 跳转地址获取最新 tag（避免匿名 REST API 限流），
     * 并尝试从 expanded_assets 页面中提取 APK 下载链接。
     */
    private fun fetchLatestViaWebRedirect(): ReleaseInfo? = runCatching {
        val resp = httpGet(LATEST_WEB_URL, accept = "text/html")
        if (resp.code !in 200..299) return@runCatching null
        val marker = "/releases/tag/"
        val idx = resp.finalUrl.indexOf(marker)
        if (idx < 0) return@runCatching null
        val rawTag = resp.finalUrl.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (rawTag.isBlank()) return@runCatching null
        val tagName = URLDecoder.decode(rawTag, "UTF-8")

        val expandedUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/expanded_assets/$rawTag"
        val assetsHtml = runCatching {
            val r = httpGet(expandedUrl, accept = "text/html")
            if (r.code in 200..299) r.body else resp.body
        }.getOrDefault(resp.body)

        val apkMatch = Regex("""href=["']([^"']*?/releases/download/[^"']+?\.apk)["']""", RegexOption.IGNORE_CASE)
            .find(assetsHtml)
        val apkAsset = apkMatch?.groupValues?.getOrNull(1)?.let { href ->
            val absUrl = if (href.startsWith("http")) href else "https://github.com$href"
            val name = absUrl.substringAfterLast('/').substringBefore('?')
            ReleaseAsset(name = name, downloadUrl = absUrl, sizeBytes = -1L)
        }

        ReleaseInfo(
            tagName = tagName,
            versionName = normalizeVersion(tagName),
            title = "C17 玻璃工坊 $tagName",
            notes = "",
            publishedAt = "",
            htmlUrl = resp.finalUrl,
            apkAsset = apkAsset
        )
    }.getOrNull()

    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (Int?) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && (old.name.endsWith(".apk", ignoreCase = true) || old.name.endsWith(".part", ignoreCase = true))) {
                    runCatching { old.delete() }
                }
            }
            val safeName = asset.name.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
                .ifBlank { "C17-Glass-update.apk" }
            val outFile = File(updatesDir, safeName)

            val conn = openFollowedConnection(asset.downloadUrl, accept = "*/*")
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("下载服务器返回 HTTP $code")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L }
                    ?: asset.sizeBytes.takeIf { it > 0L }
                    ?: -1L
                onProgress(if (total > 0L) 0 else null)

                val tmpFile = File(updatesDir, "$safeName.part")
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (total > 0L) {
                                val pct = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // 校验下载文件确为 ZIP/APK 容器而非网页报错页。
                if (tmpFile.length() < 1024L || !hasZipMagic(tmpFile)) {
                    tmpFile.delete()
                    error("下载内容校验失败，请改用浏览器下载")
                }
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
                onProgress(100)
                outFile
            } finally {
                conn.disconnect()
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Unit> = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    /**
     * 语义版本号比较：`a > b` 返回正数，`a < b` 返回负数，相等返回 0。
     * 按数值段比较，避免出现 `1.0.9 > 1.0.16` 的字典序误判。
     */
    fun compareVersions(a: String, b: String): Int {
        val parsedA = parseSemver(a)
        val parsedB = parseSemver(b)
        val maxLen = maxOf(parsedA.numbers.size, parsedB.numbers.size)
        for (i in 0 until maxLen) {
            val va = parsedA.numbers.getOrElse(i) { 0 }
            val vb = parsedB.numbers.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        val preA = parsedA.preRelease
        val preB = parsedB.preRelease
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        return comparePreRelease(preA, preB)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private data class ParsedSemver(val numbers: List<Int>, val preRelease: String?)

    private fun parseSemver(raw: String): ParsedSemver {
        val clean = normalizeVersion(raw).substringBefore('+')
        val dashIdx = clean.indexOf('-')
        val corePart = if (dashIdx >= 0) clean.substring(0, dashIdx) else clean
        val prePart = if (dashIdx >= 0) clean.substring(dashIdx + 1).takeIf { it.isNotBlank() } else null
        val nums = corePart.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedSemver(nums, prePart)
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val partsA = a.split('.')
        val partsB = b.split('.')
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            if (i >= partsA.size) return -1
            if (i >= partsB.size) return 1
            val sa = partsA[i]
            val sb = partsB[i]
            val na = sa.toIntOrNull()
            val nb = sb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> sa.compareTo(sb, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun hasZipMagic(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val hdr = ByteArray(2)
            ins.read(hdr) == 2 && hdr[0] == 0x50.toByte() && hdr[1] == 0x4B.toByte()
        }
    }.getOrDefault(false)

    private data class HttpResponse(val code: Int, val finalUrl: String, val body: String)

    private fun httpGet(url: String, accept: String): HttpResponse {
        val conn = openFollowedConnection(url, accept)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(code = code, finalUrl = conn.url.toString(), body = body)
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowedConnection(startUrl: String, accept: String): HttpURLConnection {
        var currentUrl = startUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", USER_AGENT)
                if (currentUrl.contains("api.github.com")) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            val code = conn.responseCode
            if (code in 301..308 && code != 304) {
                val loc = conn.getHeaderField("Location")
                if (loc.isNullOrBlank()) return conn
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                return conn
            }
        }
        error("重定向次数过多")
    }
}
