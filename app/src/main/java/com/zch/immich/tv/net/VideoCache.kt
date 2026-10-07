package com.zch.immich.tv.net

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 进程级视频磁盘缓存（全局单例）。
 *
 * 用 SimpleCache + LeastRecentlyUsedCacheEvictor 做流式落盘缓存：
 *  - 播放器通过 CacheDataSource 读视频，边播边写盘，切回去直接从磁盘起播；
 *  - 播放器的「预读下一视频」也写进同一个缓存，切到下一集时读到的是磁盘而不是网络。
 *
 * 缓存大小按可用空间自适应（128MB ~ 2GB）：设备存储多就多缓存，
 * 弱网（延迟大、带宽足）下既能秒起播，也不至于写爆存储。
 *
 * SimpleCache 对同一目录要求独占访问，同一时间只允许一个实例，所以做成进程级单例，
 * 整个 App 生命周期复用，不用每次进播放器都新建/释放。
 */
object VideoCache {

    private const val MIN_BYTES = 128L * 1024 * 1024
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    /** 缓存占可用空间的份额（25%） */
    private const val SPACE_RATIO = 0.25

    @Volatile private var cache: SimpleCache? = null

    @UnstableApi
    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        val dir = File(context.cacheDir, "video_cache").apply { mkdirs() }
        val size = (dir.usableSpace * SPACE_RATIO)
            .coerceIn(MIN_BYTES.toDouble(), MAX_BYTES.toDouble())
            .toLong()
        val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(size), StandaloneDatabaseProvider(context))
        cache = created
        return created
    }
}