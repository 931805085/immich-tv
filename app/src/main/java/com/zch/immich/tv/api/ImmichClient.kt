package com.zch.immich.tv.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 一个已解析的共享链接：如 https://example.com/share/aBcD123
 */
data class ShareLink(
    /** 服务器地址，如 https://example.com（不含 /api） */
    val host: String,
    /** 共享链接的 key（/share/ 后面的那一段） */
    val key: String,
    /** 规范化的完整链接，如 https://example.com/share/aBcD123 */
    val url: String,
)

/**
 * 当前共享相册会话。
 * 家庭场景用共享链接访问，不需要账号 / API key / token。
 * Coil 与 ExoPlayer 取媒体时，在 URL 里带 ?key= 即可（公开接口）。
 */
object ImmichClient {

    /** 服务器地址，如 https://example.com */
    var serverUrl: String = ""

    /** 共享链接 key（/share/ 后面的那一段） */
    var shareKey: String = ""

    /** 相册名称（连接后填充，用于首页标题） */
    var albumName: String = ""

    /** 相册 id（相册类共享枚举照片用） */
    var shareAlbumId: String = ""

    /** 共享类型："ALBUM" | "INDIVIDUAL" */
    var shareType: String = "ALBUM"

    /** 共享链接过期时间（ISO 格式，可能为空表示长期有效） */
    var shareExpiresAt: String = ""

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 把任意输入解析成规范的共享链接。
     * 支持完整链接（https://host/share/xxx），也可只给 host/share/xxx。
     * 返回 null 表示不是有效的共享链接。
     */
    fun parseShareLink(raw: String): ShareLink? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            // 只给 host/share/xxx 时补协议头。
            // 必须以小写字母或数字开头（合法主机名首字符），否则用户可能多打了标点
            // （比如 ,https://...），硬拼 https:// 前缀会把标点变成 authority，
            // 导致 OkHttp 报 UnknownHostException: Unable to resolve host ",https"
            text.first().isLetterOrDigit() && text.contains("/share/") -> "https://$text"
            else -> return null
        }
        val idx = url.indexOf("/share/")
        if (idx < 0) return null
        // 兼容用户误贴 https://host/api/share/xxx（去掉 /api）
        val host = url.substring(0, idx).removeSuffix("/api/").removeSuffix("/api")
        val key = url
            .substring(idx + "/share/".length)
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('/')
            .trim()
        if (host.isBlank() || key.isBlank()) return null
        return ShareLink(host = host, key = key, url = "$host/share/$key")
    }

    /**
     * 用共享链接配置当前会话。成功返回 true。
     * 调用后：serverUrl / shareKey 已就绪，可用 [apiForShare] 拉取数据。
     */
    fun configureShareLink(raw: String): Boolean {
        val parsed = parseShareLink(raw) ?: return false
        serverUrl = parsed.host
        shareKey = parsed.key
        shareAlbumId = ""
        albumName = ""
        shareType = "ALBUM"
        shareExpiresAt = ""
        return true
    }

    /** 当前配置对应的完整共享链接（写回历史用） */
    fun currentShareLinkUrl(): String = "$serverUrl/share/$shareKey"

    /**
     * 同步校验当前共享链接是否真的可用（调 GET /shared-links/me）。
     * 主要用于手机扫码回传链接时，给一个准确的成功 / 失败反馈。
     */
    fun validateShareLink(): Boolean =
        runBlocking {
            try {
                // withTimeout 兜底：OkHttp 的 connectTimeout 不覆盖 DNS 解析，
                // 如果服务器域名解析不出来，runBlocking 会一直挂着，
                // 把 POST /link 的响应拖到 60s+。15s 够了，够了还不回就判失败。
                withTimeout(15_000L) { apiForShare().getSharedLinkMe(shareKey) }
                true
            } catch (e: Exception) {
                false
            }
        }

    // ---------- HTTP 客户端 ----------

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val apis = mutableMapOf<String, ImmichApi>()

    private fun buildApi(baseUrl: String): ImmichApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ImmichApi::class.java)

    /** 当前共享链接对应的 API（baseUrl = 服务器/api/） */
    fun apiForShare(): ImmichApi =
        apis.getOrPut("$serverUrl/api/") { buildApi("$serverUrl/api/") }

    // ---------- 媒体 URL（公开接口，带 ?key=） ----------

    /**
     * 缩略图。
     * 注意：Immich v3 对 size 大小写敏感，必须小写（thumbnail / preview / original），
     * 传大写 THUMBNAIL 会返回 400（"size must be one of the following values: original, fullsize, preview, thumbnail"）。
     */
    fun thumbnailUrl(assetId: String, size: String = "thumbnail"): String =
        "$serverUrl/api/assets/$assetId/thumbnail?key=$shareKey&size=$size"

    /** 视频直连（支持 byte-range 拖进度，实测服务器返回 206 Partial Content） */
    fun videoUrl(assetId: String): String =
        "$serverUrl/api/assets/$assetId/video/playback?key=$shareKey"

    /**
     * 原图。
     *
     * 注意：共享链接没有 asset.download 权限，下面的两种写法一律返回 400
     * （"Not found or no asset.download access"）：
     *   - /api/assets/{id}/original?key=xxx
     *   - /api/assets/{id}/thumbnail?size=original&key=xxx
     * size=fullsize 又能被服务端 302 重定向回 size=preview，本身取不到东西。
     *
     * 实测 size=preview 是共享链接能拿到的最大分辨率（1080x1920），
     * 正好覆盖电视屏幕，所以"原图"就用它。URL 里 size= 参数和缩略图不同，
     * Coil 会当成两个独立缓存项，不会互相覆盖。
     */
    fun originalUrl(assetId: String): String =
        "$serverUrl/api/assets/$assetId/thumbnail?key=$shareKey&size=preview"
}