package com.zch.immich.tv.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.zch.immich.tv.api.ImmichClient
import com.zch.immich.tv.data.UpdateStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * 自动更新：检查 GitHub Release 里有没有比当前更新的版本，有就下载并调系统安装器。
 *
 * 发布物兼容两种形态（见 .github/workflows/build-apk.yml）：
 *  - 直接发布 `*.apk`（softprops/action-gh-release 的 files 字段）；
 *  - 只上传了 workflow artifact 打包的 `*.zip`（里面再装 apk）——当前仓库实际是这种，
 *    所以下载后如果是 zip 就解出里面的 .apk 再安装。
 *
 * 版本判断只看 tag（如 1.0.0 / v1.0.0），跟本机 versionName 做数字逐段比较；
 * versionCode 不在比较范围（发布侧不随 tag 携带）。
 */
object GitHubUpdater {

    private const val TAG = "GitHubUpdater"
    private const val REPO = "931805085/immich-tv"
    private const val RELEASES_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_ATTEMPTS = 3

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    /** 下载用客户端：复用 ImmichClient 的 OkHttp，另加整体超时 + 更宽松的读超时（GitHub 资产下载经常很慢） */
    private val client by lazy {
        ImmichClient.okHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** 当前安装版本（versionName），取不到返回空串 */
    fun currentVersionName(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 检查 GitHub latest release；网络失败 / 解析失败返回 null（调用方静默忽略） */
    suspend fun checkLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_LATEST)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "检查更新失败: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                json.decodeFromString<GitHubRelease>(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新异常", e)
            null
        }
    }

    /**
     * tag 是否比当前版本新。逐段数字比较（101 > 100），段数不足按 0 补全。
     * 当前版本解析不出来时视为有更新。
     */
    fun versionIsNewer(latestTag: String, currentVersionName: String): Boolean {
        val latest = parseVersion(latestTag) ?: return false
        val current = parseVersion(currentVersionName) ?: return true
        val max = maxOf(latest.size, current.size)
        for (i in 0 until max) {
            val a = latest.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 选要下载的发布物：优先 .apk，没有就退回 .zip（zip 里解出 apk） */
    fun resolveAsset(release: GitHubRelease): GitHubAsset? {
        release.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) && asset.downloadUrl.isNotBlank()
        }?.let { return it }
        return release.assets.firstOrNull { asset ->
            asset.name.endsWith(".zip", ignoreCase = true) && asset.downloadUrl.isNotBlank()
        }
    }

    /**
     * 下载发布物到 filesDir/updates/ 并返回 APK 文件。
     * 下载进度经 [onProgress] 回调（可能从 IO 线程回调）。失败返回 null。
     */
    suspend fun downloadApk(
        context: Context,
        release: GitHubRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File? = downloadApkTo(File(context.filesDir, "updates"), release, onProgress)

    /**
     * GitHub 资产下载在国内网络经常抖动，这里最多重试 [MAX_ATTEMPTS] 次
     * （退避 1s/2s），确实下不动才返回 null。
     */
    internal suspend fun downloadApkTo(
        dir: File,
        release: GitHubRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val asset = resolveAsset(release) ?: return@withContext null
        dir.mkdirs()
        val apk = File(dir, "immich-tv-${sanitizeTag(release.tagName)}.apk")
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            // 每次都从干净的空 .part 开始，避免上一次残留污染这次结果
            val part = File(dir, apk.name + ".part")
            try {
                downloadTo(part, asset, onProgress)
                if (!part.isFile || part.length() == 0L) {
                    Log.w(TAG, "下载内容为空，准备重试")
                    part.delete()
                    return@repeat
                }
                val result = if (asset.name.endsWith(".zip", ignoreCase = true)) {
                    unzipApk(part, apk)
                } else {
                    if (!part.renameTo(apk)) {
                        part.copyTo(apk, overwrite = true)
                        part.delete()
                    }
                    apk
                }
                if (result == null) Log.w(TAG, "更新包处理失败: asset=${asset.name}")
                part.delete()
                if (result != null) cleanStaleApks(dir, result)
                return@withContext result
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "第 ${attempt + 1} 次下载失败", e)
                part.delete()
            }
            delay(1000L * (attempt + 1))
        }
        Log.w(TAG, "重试 ${MAX_ATTEMPTS} 次后下载仍失败", lastError)
        null
    }

    /** 清理目录里其它版本的旧 APK（避免越积越多占空间） */
    private fun cleanStaleApks(dir: File, keep: File) {
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("immich-tv-") && f.name.endsWith(".apk") && f != keep
        }?.forEach { it.delete() }
    }

    private fun downloadTo(part: File, asset: GitHubAsset, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(asset.downloadUrl).build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body ?: throw java.io.IOException("空响应体")
            val total = if (asset.size > 0) asset.size else body.contentLength().coerceAtLeast(0)
            var downloaded = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        }
    }

    /**
     * 发布的某个版本的 APK 在本地应有的文件名（按版本区分，天然避免混用）。
     */
    fun apkFileName(tagName: String): String = "immich-tv-${sanitizeTag(tagName)}.apk"

    /**
     * 该版本是否已经下载好（元数据匹配且文件真实存在）。
     * 返回本地 APK 文件；没下载 / 文件被清了返回 null（会自动清掉过期的元数据）。
     */
    fun downloadedApk(context: Context, store: UpdateStore, release: GitHubRelease): File? {
        if (store.downloadedVersion != release.tagName) return null
        val file = File(
            context.filesDir,
            "updates/${store.downloadedApkName.ifBlank { apkFileName(release.tagName) }}",
        )
        if (file.isFile && file.length() > 0) return file
        // 记录在但文件没了（被系统清缓存等）：清掉过期记录，让上层走正常下载
        store.clear()
        return null
    }

    /** 调系统安装器安装 APK；返回是否成功启动了安装器 */
    fun installApk(context: Context, apk: File): Boolean = try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    // ---------- 内部工具 ----------

    /** zip 里解出第一个 .apk（独立成函数便于单元测试） */
    internal fun unzipApk(zip: File, target: File): File? = try {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val names = mutableListOf<String>()
            while (entry != null) {
                names.add(entry.name)
                if (entry.name.lowercase().endsWith(".apk")) {
                    target.outputStream().use { out -> zis.copyTo(out) }
                    if (target.length() > 0) return@use target
                    target.delete()
                }
                entry = zis.nextEntry
            }
            Log.w(TAG, "zip 里没有可用的 .apk，条目: ${names.joinToString()}")
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "解包失败", e)
        target.delete()
        null
    }

    private fun parseVersion(text: String): List<Int>? {
        val clean = text.trim().removePrefix("v").removePrefix("V")
        val parts = clean.split('.')
        val nums = parts.map { it.takeWhile(Char::isDigit).toIntOrNull() ?: return null }
        if (nums.isEmpty()) return null
        return nums
    }

    private fun sanitizeTag(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
}