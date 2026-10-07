package dev.immichtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.immichtv.api.ImmichClient
import okhttp3.Cache
import okhttp3.Dispatcher
import java.io.File
import java.util.concurrent.TimeUnit

/** 图片磁盘缓存下限：至少 64MB */
private const val MIN_HTTP_CACHE_BYTES = 64L * 1024L * 1024L
/** 图片磁盘缓存上限：设备存储充足时最多 1GB */
private const val MAX_HTTP_CACHE_BYTES = 1L * 1024L * 1024L * 1024L
/** 磁盘缓存占可用空间的份额（20%），存储多就多缓存 */
private const val CACHE_SPACE_RATIO = 0.2
/**
 * 同主机并发请求上限。
 * 预览页会把高清图预取窗口开到 3~10 张并发下载，OkHttp 默认每主机只有 5 个并发，
 * 会掐住吞吐（弱网 = 延迟大但带宽 5MB/s 的场景，要的就是「用并发换延迟」），所以抬到 10。
 */
private const val MAX_REQUESTS_PER_HOST = 10
private const val MEMORY_CACHE_PERCENT = 0.4

/**
 * Coil 配置：复用 Immich 的 OkHttpClient，图片走公开共享接口（URL 带 ?key=），
 * 不需要登录 header。
 *
 * 弱网策略（延迟大、带宽足）：并发和缓存都往大了配。
 *  - 磁盘缓存按可用空间自适应（64MB ~ 1GB）：已看过的图/缩略图秒开；
 *  - 同主机并发抬到 10：预览页高清图预取窗口（3~10 张）并行拉取不互相排队。
 */
@Composable
fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        val cacheDir = File(context.cacheDir, "http_cache").apply { mkdirs() }
        val usable = cacheDir.usableSpace
        val cacheBytes = (usable * CACHE_SPACE_RATIO)
            .coerceIn(MIN_HTTP_CACHE_BYTES.toDouble(), MAX_HTTP_CACHE_BYTES.toDouble())
            .toLong()
        val httpCache = Cache(directory = cacheDir, maxSize = cacheBytes)
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }
        val client = ImmichClient.okHttpClient.newBuilder()
            .cache(httpCache)
            .dispatcher(dispatcher)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(client))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(Runtime.getRuntime().maxMemory() / 2)
                    .build()
            }
            .build()
    }
}

/**
 * Coil 图片请求（鉴权由共享链接 key 完成，无需登录 header）。
 *
 * 不显式指定解码尺寸：AsyncImage 会把自己的布局尺寸通过 SizeResolver 传给 Coil，
 * 解码结果自然被限制在网格单元大小，几千张照片的相册也不会吃爆内存。
 */
fun imageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .build()