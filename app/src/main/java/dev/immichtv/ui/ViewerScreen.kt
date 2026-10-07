package dev.immichtv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import dev.immichtv.api.AssetDto
import dev.immichtv.api.ImmichClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 幻灯片自动翻页间隔（毫秒） */
private const val SLIDESHOW_INTERVAL_MS = 5_000L

// ---------- 预取策略（弱网 = 延迟大、带宽高，核心是「用并发换延迟」） ----------

/** 缩略图预取窗口：当前页起预取 10 张（小图，快速翻页不空白） */
private const val THUMB_PREFETCH = 10
/** 高清图预取窗口：网络一般（延迟高）时只预取 3 张 */
private const val ORIGINALS_PREFETCH_SLOW = 3
/** 高清图预取窗口：网络较好（延迟低）时预取 10 张，把带宽用满 */
private const val ORIGINALS_PREFETCH_GOOD = 10
/** 高清图并行下载上限：5 路并发，配合缩略图预取足以吃满 5MB/s 带宽 */
private const val ORIGINALS_CONCURRENCY = 5
/** 判定「网络较好」的原图加载耗时阈值（毫秒），低于它认为延迟低 */
private const val GOOD_NETWORK_MS = 400

/**
 * 网络质量的粗略估计：记录「原图 HTTP 加载耗时」的滚动指数平均。
 * 缓存命中的（<30ms）不计入——那种耗时不代表网络。
 * 用来决定高清图预热窗口：延迟低 → 预取 10 张，延迟高 → 只预取 3 张。
 */
private class NetworkQuality {
    private var ewmaMs = 0.0
    private var samples = 0

    /** 是否判定为「网络较好」（有样本且平均耗时低于阈值） */
    val isGood: Boolean get() = samples > 0 && ewmaMs < GOOD_NETWORK_MS

    fun record(elapsedMs: Long) {
        if (elapsedMs < 30) return // 缓存命中，不代表网络
        samples++
        ewmaMs = if (samples == 1) elapsedMs.toDouble() else ewmaMs * 0.7 + elapsedMs * 0.3
    }
}

/**
 * 全屏查看页：按资产类型分流。
 *  - 视频 → ExoPlayer 播放（[PlayerScreen]）
 *  - 图片 → 幻灯片浏览（[ImageViewerScreen]），支持自动翻页 + D-pad / 滑动切换
 *
 * 关闭时回传当前索引，列表页据此滚动到这张所在位置。
 *
 * [onNavigateTo] 用来在两个页面之间跳转：图片页翻到视频时、
 * 播放器按左右键翻到图片时都走它——上层把起始下标换过去，这里再按类型重选页面。
 * 第二个参数是「暂停状态要不要继承」：播放器暂停时翻到图片页传 true，
 * 图片页翻到视频页传 false（只在播放器 → 图片这一方向继承）。
 *
 * [startPaused] 是本次导航带过来的暂停状态，只有图片页会用它做初始值。
 *
 * 预览期间会把窗口置为 KEEP_SCREEN_ON：电视上看幻灯片 / 视频时画面不该自动熄灭，
 * 关掉预览页时立刻清掉，回到列表后的熄屏策略跟系统设置一致。
 */
@Composable
fun ViewerScreen(
    assets: List<AssetDto>,
    startIndex: Int,
    startPaused: Boolean,
    onClose: (Int) -> Unit,
    onNavigateTo: (Int, Boolean) -> Unit,
) {
    // 预览期间禁止休眠：电视上看幻灯片 / 视频时画面不该自己黑掉。
    // keepScreenOn 底层就是给窗口加 FLAG_KEEP_SCREEN_ON；页面退出时在 onDispose 清掉，
    // 回到列表后恢复系统自己的熄屏策略。用 DisposableEffect 而不是 Activity 回调，
    // 开闭时机就跟这个页面的组合生命周期严格一致。
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    if (assets.isEmpty()) {
        // 空列表也要能返回，否则页面会卡住
        BackHandler { onClose(0) }
        return
    }

    // 当前显示的是视频还是图片，决定走哪个页面
    if (assets[startIndex].type == "VIDEO") {
        PlayerScreen(assets = assets, startIndex = startIndex, onClose = onClose, onNavigateTo = onNavigateTo)
    } else {
        ImageViewerScreen(
            assets = assets,
            startIndex = startIndex,
            onClose = onClose,
            onNavigateTo = onNavigateTo,
            initialPaused = startPaused,
        )
    }
}

/**
 * 图片幻灯片：
 *  - 自动每 5 秒翻页，到底后回到第一张；按「确定」暂停 / 继续
 *  - D-pad 左右 / 键盘 ←→ / 触屏滑动手动切换
 *  - 底部显示「第 x / 共 y 张」+ 自动播放或暂停指示
 *
 * 弱网策略：先上缩略图，原图和后续两页在后台预热；
 * 这样切页时不会空白，也不会一上来就把网络打满。
 *
 * 按键为什么必须显式接管：HorizontalPager 只处理触摸和拖拽手势，
 * 完全不消费 TV 的 D-pad 事件；而这个页面里没有别的可聚焦节点，
 * 焦点无处可去，遥控器按左右就是没反应。所以给根 Box 加 focusable()
 * 再挂 handleKeyEvent，触屏滑动和按键两条路互不影响。
 */
@Composable
private fun ImageViewerScreen(
    assets: List<AssetDto>,
    startIndex: Int,
    onClose: (Int) -> Unit,
    onNavigateTo: (Int, Boolean) -> Unit,
    initialPaused: Boolean = false,
) {
    val imageLoader = rememberImageLoader()
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { assets.size })
    val context = LocalContext.current
    // 网络质量估计：跟随原图加载实时更新，决定高清图预取窗口（好网络 10 张 / 差网络 3 张）
    val quality = remember { NetworkQuality() }
    // 记录「原图已加载」的页：页面先显示缩略图，原图就绪后无缝替换
    val originalsReady = remember { mutableStateMapOf<Int, Boolean>() }
    // 全屏显示尺寸：原图预加载按此解码，切换时可直接命中内存缓存、避免全分辨率解码
    val displaySize = with(context.resources.displayMetrics) { Size(widthPixels, heightPixels) }
    // 「确定」键暂停自动翻页；true 时计时器不起，图片停在当前页。
    // initialPaused：从暂停的视频播放器翻进来时保持暂停，不用用户再按一次确定。
    val paused = remember { mutableStateOf(initialPaused) }
    // 手动翻页用 composition 作用域的协程，而不是 LaunchedEffect(pagerState.currentPage)：
    // 后者会在 currentPage 变化时被取消，动画跑一半 Pager 就卡在两页之间。
    // scope 的生命周期跟页面一致，翻页动画不会被中途打断。
    val scope = rememberCoroutineScope()

    /** 翻到指定页（模运算保证翻到底自动回到第一张） */
    fun goto(page: Int) {
        if (assets.size <= 1) return
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // 遥控器按键走 Activity.dispatchKeyEvent（KeyBus），不走 Compose 的 onKeyEvent。
    // onKeyEvent 只在节点真正有焦点时才被调用，而 HorizontalPager 内部的 ScrollableNode
    // 会参与焦点归属，实测整屏 focused 节点数为 0，按键根本派发不过来。
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = { event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        goto((pagerState.currentPage + assets.size - 1) % assets.size); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        goto((pagerState.currentPage + 1) % assets.size); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (assets.size > 1) paused.value = !paused.value
                        true
                    }
                    else -> false
                }
            }
        }
        KeyBus.handler = handler
        onDispose { if (KeyBus.handler === handler) KeyBus.handler = null }
    }

    // 返回时带上当前页，列表页据此定位到这张
    BackHandler { onClose(pagerState.currentPage) }

    // 翻到视频页就交给播放器播放。传 false：反向不继承暂停状态，
    // 图片页的暂停只往「播放器 → 图片页」这一方向带
    LaunchedEffect(pagerState.currentPage) {
        if (assets[pagerState.currentPage].type == "VIDEO") onNavigateTo(pagerState.currentPage, false)
    }

    // 自动翻页：currentPage 或 paused 任一变化都重启本 Effect。
    //   - currentPage 变：手动翻了页要重新计时
    //   - paused 变 true：直接退出、不计时
    // 注意：animateScrollToPage 启动后 currentPage 会中途变成目标页并重启本 Effect，
    // 动画协程一旦被取消，Pager 就停在两页中间的偏移上，图片会偏左/偏右不居中。
    // 因此用 NonCancellable 保证自动翻页动画一定完整跑完。
    LaunchedEffect(pagerState.currentPage, paused.value) {
        if (assets.size <= 1 || paused.value) return@LaunchedEffect
        delay(SLIDESHOW_INTERVAL_MS)
        val next = (pagerState.currentPage + 1) % assets.size
        withContext(NonCancellable) {
            pagerState.animateScrollToPage(next)
        }
    }

    // 预取策略（弱网 = 延迟大、带宽高，核心是「用并发换延迟」）：
    //  - 缩略图：当前页起预取 10 张，小图并行不心疼，快速翻页不空白；
    //  - 高清图：窗口随网络质量自适应 —— 低延迟 10 张、高延迟 3 张；
    //    当前页原图先单独拉（幻灯片里的无缝升级靠它，最先到位），
    //    窗口内其余原图限并发 5 并行拉取，把这条 5MB/s 带宽用起来
    //    （OkHttp 每主机并发已在 ImageSupport 抬到 10，不会在客户端排队）。
    LaunchedEffect(pagerState.currentPage) {
        val current = pagerState.currentPage
        val pages = (0 until THUMB_PREFETCH).map { (current + it).mod(assets.size) }.distinct()
        pages.forEach { page ->
            imageLoader.enqueue(imageRequest(context, ImmichClient.thumbnailUrl(assets[page].id)))
        }

        delay(200)
        // 视频没有原图（originalUrl 指向视频文件本身，当图片解码必然失败），只留缩略图
        val originalWindow = if (quality.isGood) ORIGINALS_PREFETCH_GOOD else ORIGINALS_PREFETCH_SLOW
        val originals = (0 until originalWindow)
            .map { (current + it).mod(assets.size) }
            .distinct()
            .filter { assets[it].type != "VIDEO" }
        if (originals.isEmpty()) return@LaunchedEffect

        suspend fun loadOriginal(page: Int) {
            val request = ImageRequest.Builder(context)
                .data(ImmichClient.originalUrl(assets[page].id))
                .size(displaySize)
                .build()
            val start = System.nanoTime()
            // runCatching：单张图失败不影响其余（Coil 正常不抛，但 OOM/解码异常要兜住）
            val result = runCatching { imageLoader.execute(request) }
            quality.record((System.nanoTime() - start) / 1_000_000)
            if (result.getOrNull() is SuccessResult) originalsReady[page] = true
        }

        // 当前页原图先单独拉：无缝升级最优先，不等其余并行任务
        if (assets[current].type != "VIDEO") loadOriginal(current)

        // 窗口内其余原图限并发（5）并行拉取
        val rest = originals.filter { it != current }
        val semaphore = Semaphore(ORIGINALS_CONCURRENCY)
        coroutineScope {
            rest.map { page ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { loadOriginal(page) }
                }
            }.awaitAll()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
        ) { page ->
            val thumbnailModel = remember(page) {
                imageRequest(context, ImmichClient.thumbnailUrl(assets[page].id))
            }
            val originalModel = remember(page) {
                imageRequest(context, ImmichClient.originalUrl(assets[page].id))
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = if (originalsReady[page] == true) originalModel else thumbnailModel,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    imageLoader = imageLoader,
                )
            }
        }

        // 底部半透明状态条：张数 + 自动播放 / 暂停提示
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${assets.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "  ${if (paused.value) "已暂停" else "自动播放中"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
            // 只有一张图时「确定」无意义，不显示按键提示
            if (assets.size > 1) {
                Text(
                    "  确定 暂停/继续",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
