package dev.immichtv.ui

import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.immichtv.api.AssetDto
import dev.immichtv.api.ImmichClient
import dev.immichtv.net.VideoCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 预读下一视频的前缀字节数：约 10~15 秒常见码率的视频，够 ExoPlayer 秒起播 */
private const val VIDEO_PRELOAD_BYTES = 8L * 1024 * 1024
/** 预读拷贝缓冲区大小 */
private const val PRELOAD_BUFFER_BYTES = 64 * 1024

/** 播放器回调在 ExoPlayer 内部线程上，改 Compose 状态必须切回主线程 */
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * 视频播放页：Media3 ExoPlayer + 底部控制条。
 *
 * 传整条时间线的扁平列表 + 当前下标，而不是单个 asset，这样才能：
 *  1. 「上一个 / 下一个」翻的是相邻的任意资源（图片和视频混排）——
 *     翻到视频就在原地切换地址、播放器不重建；翻到图片就交给图片页显示；
 *  2. 播完自动往后走一个资源，跟左右键是同一套逻辑；
 *  3. 关掉时回传当前所在的扁平下标，列表页据此定位回这张。
 *
 * 视频地址带共享链接 key（公开接口），支持 byte-range 边下边播。
 *
 * 控制条只有 上一个 / 播放暂停 / 下一个——按需求不做快进快退，
 * 版式跟图片页的状态条对齐（BottomCenter、同底色同内边距、同款「x / y」计数）。
 *
 * 按键交互跟图片幻灯片保持一致：
 *   - 「确定」暂停 / 继续播放
 *   - 左右键切上一个 / 下一个资源（图片或视频）
 * 按键走 Activity.dispatchKeyEvent（KeyBus）统一接管，不依赖焦点。
 *
 * 翻到图片时通过 [onNavigateTo] 把「当前的暂停状态」一起带过去：暂停着翻出去，
 * 图片页就接着保持暂停，不用用户再按一次确定。反向（图片页翻到视频）不继承，
 * 视频照常从播放开始。
 *
 * 用 PlayerView + AndroidView 而不是 PlayerSurface：
 * PlayerSurface 不暴露 resizeMode 参数，而 PlayerView 可以直接设
 * RESIZE_MODE_FIT 保证视频按原始比例显示（等比缩放 + 留黑边），
 * 不会拉伸变形也不会裁掉画面。
 */
@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    assets: List<AssetDto>,
    startIndex: Int,
    onClose: (Int) -> Unit,
    onNavigateTo: (Int, Boolean) -> Unit,
) {
    val context = LocalContext.current

    // 当前所在的资源下标：图文混排时间线里的位置，左右键在这里步进
    val flatIndex = remember { mutableIntStateOf(startIndex) }
    // 这条时间线里所有视频所在的扁平下标，用来算「第几个视频」和起播哪个地址
    val videoIndexes = remember { assets.indices.filter { assets[it].type == "VIDEO" } }
    // 当前资源对应的播放槽位；从 flatIndex 派生，不用手动同步两边
    val videoSlot = remember { derivedStateOf { videoIndexes.indexOf(flatIndex.value).coerceAtLeast(0) } }
    val paused = remember { mutableStateOf(false) }
    val positionMs = remember { mutableLongStateOf(0L) }
    val durationMs = remember { mutableLongStateOf(0L) }

    // 播放走 CacheDataSource（进程级 SimpleCache 磁盘缓存）：视频边播边落盘，
    // 切回去直接从磁盘起播；「预读下一视频」也写进同一个缓存。
    val videoCache = remember { VideoCache.get(context) }
    val cacheDataSourceFactory = remember(videoCache) {
        CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build()
    }
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            // FIT = 等比缩放保留原始比例，不足的部分留黑边
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /**
     * 前后翻一个资源（图片和视频混排），到底回卷到第一张、跟图片页一致：
     *  - 目标是视频 → 只改 flatIndex，播放器原地切地址，不重建、播放连续
     *  - 目标是图片 → 交给图片页显示，并带上当前的暂停状态：
     *    暂停着翻出去，图片页就接着暂停，不用用户再按一次确定
     */
    val stepAsset: (Int) -> Unit = remember {
        { delta ->
            if (assets.size > 1) {
                val next = (flatIndex.value + delta).mod(assets.size)
                if (next != flatIndex.value) {
                    if (assets[next].type == "VIDEO") {
                        flatIndex.value = next
                    } else {
                        onNavigateTo(next, paused.value)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    mainHandler.post {
                        // 播完自动往后走一个资源，跟左右键是同一套逻辑（图文混排、到底回卷）
                        stepAsset(1)
                        paused.value = false
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            playerView.player = null
        }
    }

    // 每次换视频：起播新地址
    LaunchedEffect(videoSlot.value) {
        val asset = assets.getOrNull(videoIndexes.getOrNull(videoSlot.value) ?: startIndex)
            ?: return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri(ImmichClient.videoUrl(asset.id)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        paused.value = false
        positionMs.value = 0L
        durationMs.value = 0L
    }

    // 预读「时间线里下一个视频」的前缀字节进磁盘缓存：切过去时播放器读到的是
    // 磁盘而不是网络（秒起播）。用一次带 range 的长连接读透 CacheDataSource——
    // 读取会边读边写进 SimpleCache，与播放共用同一缓存。
    // 换视频 / 退出页面时本 Effect 被取消，读取协程随之中止。
    LaunchedEffect(videoSlot.value) {
        val nextIdx = videoIndexes.getOrNull(videoSlot.value + 1)
            ?: return@LaunchedEffect // 后面没有视频了
        val asset = assets.getOrNull(nextIdx) ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dataSource = cacheDataSourceFactory.createDataSource()
            try {
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(ImmichClient.videoUrl(asset.id)))
                    .setPosition(0)
                    .setLength(VIDEO_PRELOAD_BYTES)
                    .build()
                dataSource.open(spec)
                val buf = ByteArray(PRELOAD_BUFFER_BYTES)
                var total = 0L
                while (total < VIDEO_PRELOAD_BYTES) {
                    val n = dataSource.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    total += n
                    currentCoroutineContext().ensureActive() // 取消则立即中止，不浪费带宽
                }
            } catch (e: CancellationException) {
                throw e // 换视频/退出页面取消，原样往上抛
            } catch (e: Exception) {
                // 预读失败不影响正片（CacheDataSource 会按需回源网络）
            } finally {
                runCatching { dataSource.close() }
            }
        }
    }

    // 播放/暂停图标跟随播放器实时刷新。用 playWhenReady 而不是 isPlaying：
    // 缓冲时 isPlaying 会是 false，图标会误显示成「播放」，但用户的意图其实是「播放中」
    LaunchedEffect(videoSlot.value) {
        while (true) {
            positionMs.value = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
            paused.value = !exoPlayer.playWhenReady
            delay(250)
        }
    }

    // 当前资源在时间线里的扁平下标（回传给列表页定位用）
    val currentAssetIndex = flatIndex.value

    BackHandler { onClose(currentAssetIndex) }

    // playWhenReady 同步生效，直接取反就能立刻翻图标；
    // 不能读 isPlaying——那要等播放器状态机走完才变，图标会晚一拍
    val togglePause: () -> Unit = {
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
        paused.value = !exoPlayer.playWhenReady
    }

    // 遥控器按键统一走 KeyBus（Activity.dispatchKeyEvent），理由同图片页
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = { event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER -> { togglePause(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { stepAsset(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { stepAsset(1); true }
                    else -> false
                }
            }
        }
        KeyBus.handler = handler
        onDispose { if (KeyBus.handler === handler) KeyBus.handler = null }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx -> playerView.apply { player = exoPlayer } },
            modifier = Modifier.fillMaxSize(),
        )

        // 底部半透明控制条：跟图片页的状态条对齐——同样的位置（BottomCenter）、同样的底色和内边距、
        // 同样「第 x / 共 y」的计数写法。图片页是「x / y + 自动播放中」，这里换成「x / y + 时间」，
        // 后面跟上 上一个 / 播放暂停 / 下一个。按需求不做快进快退。
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${flatIndex.value + 1} / ${assets.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "  ${formatTime(positionMs.value)} / ${formatTime(durationMs.value)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
            // 暂停时给个文字提示，光靠 ⏸ 图标在电视上太不显眼
            if (paused.value) {
                Text(
                    "  已暂停",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Row(
                modifier = Modifier.padding(start = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 非焦点态按钮：只留视觉和触屏，遥控器的左右/确定由 KeyBus 统一接管
                TvStatusButton(
                    onClick = { stepAsset(-1) },
                    enabled = assets.size > 1,
                ) { Text("上一个") }
                TvStatusButton(onClick = togglePause) {
                    Text(if (paused.value) "▶" else "⏸")
                }
                TvStatusButton(
                    onClick = { stepAsset(1) },
                    enabled = assets.size > 1,
                ) { Text("下一个") }
            }

            // 跟图片页一样给出按键提示，电视上不用猜怎么暂停
            if (assets.size > 1) {
                Text(
                    "  确定 暂停/继续 · 左右 上一个/下一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** 播放进度格式化成「分:秒」，比如 83000 → 01:23 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
