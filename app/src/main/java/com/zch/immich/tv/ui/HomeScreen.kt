package com.zch.immich.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.zch.immich.tv.api.AssetDto
import com.zch.immich.tv.api.ImmichClient
import com.zch.immich.tv.api.TimelineBucketDto
import com.zch.immich.tv.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 时间线里"一天"的分组 */
private data class DayGroup(
    /** 日期键，如 "2026-08-30"；无拍摄时间用 UNKNOWN_DAY */
    val date: String,
    /** 显示用的日期标签，如 "2026年8月30日 周六" */
    val label: String,
    val assets: List<AssetDto>,
    /** 该天第一张照片在完整扁平列表中的下标（打开幻灯片用） */
    val startIndex: Int,
)

/** 年份快速跳转项（右侧常驻时间线用） */
private data class YearJump(
    /** 年份键，如 "2026"；无拍摄时间的照片归到 UNKNOWN_YEAR */
    val year: String,
    /** 显示用标签，如 "2026年"；无拍摄时间的显示「日期未知」 */
    val label: String,
    /** 该年第一天在 days 列表中的下标 */
    val firstDayIndex: Int,
    val photoCount: Int,
)

private const val UNKNOWN_DAY = "0000-00-00"
private const val UNKNOWN_YEAR = "0000"
private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/**
 * 首页：共享相册照片按天聚集的时间线，最新在前，向下翻是更早的照片。
 *
 * 数据来源（Immich v3 公开共享接口，无需登录）：
 *  - GET /shared-links/me?key=xxx  → 相册信息
 *  - 相册类共享：GET /timeline/buckets + /timeline/bucket 枚举照片（并发拉取各时间桶）
 *    bucket 响应里的 fileCreatedAt 是本地时间，客户端据此按天分组；
 *  - 单资产类共享：直接用返回的 assets 列表。
 *
 * 交互：
 *  - 上下键在日期行之间移动，左右键横向翻当天照片；按住下键可快速滚向旧照片；
 *  - 右侧常驻年份时间线：顶部实时显示当前所在年月，点某一年一键跳到那一年；
 *  - 点照片进预览，返回时滚动回这张所在的日期行与行内列（预览页是覆盖层，
 *    列表不销毁，所以返回不会重新拉取相册）。
 *
 * 注意：网络请求和 JSON 解析全部放到 IO 线程。3000+ 张照片的相册会拉 24 个时间桶、
 * 几 MB 的列式 JSON，在主线程解析会让 looper 长时间不响应，电视上直接被判无响应。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    generation: Long,
    settings: SettingsStore,
    onOpenAsset: (List<AssetDto>, Int) -> Unit,
    onSwitchLink: () -> Unit,
    locateRequest: LocateRequest?,
) {
    var title by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var days by remember { mutableStateOf<List<DayGroup>>(emptyList()) }
    var assets by remember { mutableStateOf<List<AssetDto>>(emptyList()) }
    var years by remember { mutableStateOf<List<YearJump>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // 流式加载：照片出了第一批（最新月份）就已可用，更早的月份在后台继续补
    var streaming by remember { mutableStateOf(false) }
    var loadedMonths by remember { mutableIntStateOf(0) }
    var totalMonths by remember { mutableIntStateOf(0) }
    // 部分月份加载失败时的提示（其余照片已正常显示），点「重试」整体重拉
    var partialError by remember { mutableStateOf<String?>(null) }
    // 重试令牌：值变化会触发下面的 LaunchedEffect 重新跑加载
    var retryToken by remember { mutableIntStateOf(0) }
    val imageLoader = rememberImageLoader()
    val context = LocalContext.current
    val timelineState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 右侧年份时间线顶部显示的「当前所在年月」，跟随时间线滚动实时更新
    var currentMonth by remember { mutableStateOf("") }
    // 从预览页返回后待定位的目标（天索引, 行内列号）；定位完成后清空
    var pendingLocate by remember { mutableStateOf<PendingLocate?>(null) }
    // 初始焦点放到「第一天的第一张照片」上。
    // 不放的话焦点默认落在「切换共享链接」按钮，
    // 电视上用户一按上下键就被带进连接页，根本摸不到时间线。
    val firstTileFocus = remember { FocusRequester() }

    // 依赖 generation：从连接页或手机扫码换一个共享链接后，会重新拉取相册。
    // retryToken：加载失败后点「重试」也会重跑这一整段。
    LaunchedEffect(generation, retryToken) {
        // 先清空上一轮的残留（否则换链接失败时会一直显示旧错误、旧照片）
        title = ""
        hint = ""
        days = emptyList()
        assets = emptyList()
        years = emptyList()
        currentMonth = ""
        error = null
        partialError = null
        loadedMonths = 0
        totalMonths = 0
        loading = true
        streaming = false
        try {
            // 1) 相册信息（快）：一拿到就能定标题、有效期提示，并把相册名回填到历史
            val me = withContext(Dispatchers.IO) {
                ImmichClient.apiForShare().getSharedLinkMe(ImmichClient.shareKey)
            }
            ImmichClient.albumName = me.album?.albumName ?: "共享相册"
            ImmichClient.shareType = me.type
            ImmichClient.shareAlbumId = me.album?.id ?: ""
            ImmichClient.shareExpiresAt = me.expiresAt ?: ""
            title = ImmichClient.albumName
            hint = if (me.expiresAt.isNullOrBlank()) "" else "共享链接有效期: ${me.expiresAt}"
            settings.updateAlbumName(ImmichClient.currentShareLinkUrl(), ImmichClient.albumName)

            when {
                me.type == "ALBUM" && !me.album?.id.isNullOrBlank() -> {
                    // 2) 相册类共享：先拿月份桶列表（一个请求，很快），再流式拉每个桶的照片
                    val albumId = me.album?.id.orEmpty()
                    val buckets = withContext(Dispatchers.IO) {
                        ImmichClient.apiForShare()
                            .getTimelineBuckets(albumId, ImmichClient.shareKey)
                    }.sortedByDescending { it.timeBucket } // 最新的月份在前
                    totalMonths = buckets.size
                    loading = false
                    if (buckets.isEmpty()) return@LaunchedEffect // 空相册
                    streaming = true
                    val allSoFar = mutableListOf<AssetDto>()
                    var failedMonths = 0
                    streamBucketAssets(albumId, buckets) { _, bucketAssets, ok ->
                        if (ok) allSoFar.addAll(bucketAssets) else failedMonths++
                        loadedMonths = (loadedMonths + 1).coerceAtMost(totalMonths)
                        // 每到一个桶就整体重新按天分组：
                        // 流式顺序保证新桶只会是「更早的月份」，追加在末尾，
                        // 已显示的天下标不变 → 预览页返回定位不会错位。
                        val grouped = groupByDay(allSoFar)
                        days = grouped
                        assets = grouped.flatMap { it.assets }
                        years = buildYears(grouped)
                    }
                    streaming = false
                    if (failedMonths > 0) partialError = "有 $failedMonths 个月的照片加载失败，其余已正常显示"
                }
                else -> {
                    // 3) 单资产 / 自定义列表共享：没有时间桶，直接用返回的 assets
                    loading = false
                    val grouped = groupByDay(me.assets)
                    days = grouped
                    assets = grouped.flatMap { it.assets }
                    years = buildYears(grouped)
                }
            }
        } catch (e: CancellationException) {
            throw e // 正常的切屏取消
        } catch (e: Throwable) {
            // 用 Throwable 而不是 Exception：加载阶段的 AssertionError / OOM 也要落到 UI 上，
            // 而不是直接把整个 App 崩掉（电视上看不到 logcat，崩了就只能干等）
            error = "${e::class.simpleName}: ${e.message ?: e::class.simpleName}"
        } finally {
            loading = false
            streaming = false
        }
    }

    // 跟随时间线滚动，实时更新右侧年份时间线顶部的「当前所在年月」
    LaunchedEffect(timelineState, days) {
        if (days.isEmpty()) {
            currentMonth = ""
            return@LaunchedEffect
        }
        snapshotFlow {
            val idx = visibleDayIndex(timelineState)
            days.getOrNull(idx)?.date?.take(7).orEmpty()
        }.distinctUntilChanged().collect { currentMonth = it }
    }

    // 从预览页返回后：竖向滚到那天，行内列号交给 DayRow 消费
    LaunchedEffect(locateRequest) {
        val req = locateRequest ?: return@LaunchedEffect
        val flat = req.flatIndex
        val dayIdx = days.indexOfFirst { flat in it.startIndex until it.startIndex + it.assets.size }
        if (dayIdx < 0) return@LaunchedEffect
        val day = days[dayIdx]
        pendingLocate = PendingLocate(dayIdx, (flat - day.startIndex).coerceIn(0, day.assets.lastIndex))
        // 每个天占 2 项（吸顶日期头 + 照片行），照片行是奇数项
        scope.launch { timelineState.scrollToItem(dayIdx * 2 + 1) }
    }

    // 照片加载出来后才请求焦点：瓦片必须先被布局出来才是 focusable
    LaunchedEffect(days.isEmpty()) {
        if (days.isEmpty()) return@LaunchedEffect
        delay(300)
        firstTileFocus.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 24.dp, bottom = 32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (title.isBlank()) "共享相册" else title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TvButton(onClick = onSwitchLink) { Text("切换共享链接") }
        }
        hint.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        // 流式加载进度：照片已开始显示，更早的月份还在后台补
        if (streaming && totalMonths > 0) {
            Text(
                "已加载 ${loadedMonths.coerceAtMost(totalMonths)} / $totalMonths 个月…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        // 加载失败（整体或部分月份）：给出错误原因 + 重试按钮
        val failure = error ?: partialError
        if (failure != null) {
            Text(
                failure,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TvButton(onClick = { retryToken++ }, modifier = Modifier.padding(top = 8.dp)) { Text("重试") }
        }
        if (loading) {
            Text("正在加载照片…", style = MaterialTheme.typography.bodyMedium)
        } else if (days.isEmpty() && error == null) {
            Text(
                if (streaming) "正在加载照片…" else "这张共享相册里没有照片",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            // 左：按天的照片时间线；右：常驻年份时间线（顶部显示当前年月，点年份一键跳转）
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LazyColumn(
                    state = timelineState,
                    modifier = Modifier.fillMaxHeight().weight(1f),
                ) {
                    days.forEachIndexed { index, day ->
                        stickyHeader(key = "header_${day.date}") {
                            DayHeader(label = day.label, count = day.assets.size)
                        }
                        item(key = "row_${day.date}") {
                            DayRow(
                                day = day,
                                allAssets = assets,
                                // 只有命中定位目标的那天才有行内列号，其余传 -1
                                targetColumn = pendingLocate?.takeIf { it.dayIndex == index }?.column ?: -1,
                                onLocated = { pendingLocate = null },
                                onOpenAsset = onOpenAsset,
                                imageLoader = imageLoader,
                                context = context,
                                // 只有第一天的第一张接初始焦点请求
                                focusRequester = if (index == 0) firstTileFocus else null,
                            )
                        }
                    }
                }
                YearRail(
                    years = years,
                    currentMonth = currentMonth,
                    modifier = Modifier.fillMaxHeight(),
                    onJump = { year ->
                        // 每个日期行在 LazyColumn 里占 2 项（吸顶日期头 + 照片行）
                        scope.launch { timelineState.scrollToItem(year.firstDayIndex * 2) }
                    },
                )
            }
        }
    }
}

/** 从预览页返回后的定位目标：dayIndex 是天的下标，column 是当天行内的列 */
private data class PendingLocate(val dayIndex: Int, val column: Int)

/** 吸顶的日期头：如「2026年8月30日 周六 · 16 张」 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayHeader(label: String, count: Int) {
    Text(
        "$label · $count 张",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/** 某一天的照片行；从预览页返回后按需横向定位到行内那一列 */
@Composable
private fun DayRow(
    day: DayGroup,
    allAssets: List<AssetDto>,
    targetColumn: Int,
    onLocated: () -> Unit,
    onOpenAsset: (List<AssetDto>, Int) -> Unit,
    imageLoader: ImageLoader,
    context: android.content.Context,
    focusRequester: FocusRequester? = null,
) {
    val rowState = rememberLazyListState()

    // 返回后横向定位：先等行内条目真正摆出来，否则 LazyRow 还没测量、scrollToItem 会越界
    LaunchedEffect(day.date, targetColumn) {
        if (targetColumn < 0) return@LaunchedEffect
        if (targetColumn == 0) {
            onLocated()
            return@LaunchedEffect
        }
        snapshotFlow { rowState.layoutInfo.totalItemsCount }
            .first { it > targetColumn }
        rowState.scrollToItem(targetColumn)
        onLocated()
    }

    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(end = 16.dp),
    ) {
        itemsIndexed(day.assets, key = { _, asset -> asset.id }) { i, asset ->
            TimelineTile(
                asset = asset,
                onClick = { onOpenAsset(allAssets, day.startIndex + i) },
                imageLoader = imageLoader,
                context = context,
                // 只有行内第一张接焦点请求，避免多个瓦片同时抢
                focusRequester = if (i == 0) focusRequester else null,
            )
        }
    }
}

/** 时间线里的一张照片瓦片（方形、裁剪显示缩略图）；视频额外带播放标记 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimelineTile(
    asset: AssetDto,
    onClick: () -> Unit,
    imageLoader: ImageLoader,
    context: android.content.Context,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    // 焦点态从 interactionSource 读，不用 Modifier.onFocusChanged：
    // onFocusChanged 必须写在 clickable 之前（链上更外层）才收得到焦点变化，
    // 位置错了回调永远不触发。collectIsFocusedAsState 和 modifier 顺序无关，稳。
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // 不用 tv-material3 的 Card：它的点击只走内部的 tvClickable（focusable + 按键事件），
    // 完全不处理触摸；再补 tvTap 又会用 detectTapGestures 把 pointer down 消费掉，
    // 父级 LazyColumn/LazyRow 收不到这个 down，竖向/横向滚动就整个失效
    // （现象是点按正常，但手指一划时间线纹丝不动）。
    // 这里改用 Box + clickable：clickable 同时提供触屏点按和遥控器确认键，
    // 而且它不消费 pointer 事件，滚动和点按能并存。
    Box(
        modifier = Modifier
            .size(160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .clip(shape)
            // focusRequester 必须写在 clickable 之前：实测写在之后 requestFocus() 静默失败
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(onClick = onClick, interactionSource = interactionSource)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = imageRequest(context, ImmichClient.thumbnailUrl(asset.id)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                imageLoader = imageLoader,
            )
            // 视频标记：不用点开就能分辨哪些能播（判断口径与 ViewerScreen 保持一致）
            if (asset.type == "VIDEO") {
                PlayBadge()
            }
        }
    }
}

/** 视频缩略图上的播放标记：半透明深色圆底 + 白色小三角 */
@Composable
private fun PlayBadge() {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        // 三角形比几何中心略右移一点，视觉上才居中
        Canvas(modifier = Modifier.padding(start = 2.dp).size(16.dp)) {
            val w = size.width
            val h = size.height
            val triangle = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, h / 2f)
                lineTo(0f, h)
                close()
            }
            drawPath(triangle, Color.White)
        }
    }
}

/**
 * 右侧常驻的年份时间线：顶部实时显示当前所在年月，下面按年倒序列出，
 * 点某一年就跳到那一年第一行。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YearRail(
    years: List<YearJump>,
    currentMonth: String,
    modifier: Modifier = Modifier,
    onJump: (YearJump) -> Unit,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .fillMaxHeight()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // 顶部：当前所在年月 + 位置指示线，跟随时间线滚动实时更新
        Text(
            if (currentMonth.isBlank()) "—" else monthLabel(currentMonth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                .padding(bottom = 20.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(years, key = { _, year -> year.year }) { _, year ->
                YearItem(
                    year = year,
                    isCurrent = currentMonth.take(4) == year.year,
                    onPick = { onJump(year) },
                )
            }
        }
    }
}

/** 年份时间线里的一年：纯文字 + 右侧小圆点，当前年份/焦点态高亮 */
@Composable
private fun YearItem(
    year: YearJump,
    isCurrent: Boolean,
    onPick: (YearJump) -> Unit,
) {
    // 焦点态从 interactionSource 读；Modifier.onFocusChanged 只有写在 clickable 之前才触发
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val active = isFocused || isCurrent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = { onPick(year) }, interactionSource = interactionSource)
            .padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        Text(
            year.label,
            style = if (active) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                    CircleShape,
                ),
        )
    }
}

/**
 * 把所有照片按天聚合，天之间按日期倒序（最新在前），
 * 同一天内按拍摄时间倒序。无拍摄时间的归到「日期未知」并排到最末。
 */
private fun groupByDay(assets: List<AssetDto>): List<DayGroup> {
    val byDay = LinkedHashMap<String, MutableList<AssetDto>>()
    for (asset in assets) {
        val day = asset.fileCreatedAt?.takeIf { it.length >= 10 }?.substring(0, 10) ?: UNKNOWN_DAY
        byDay.getOrPut(day) { mutableListOf() }.add(asset)
    }
    // ISO 日期字符串直接字典序即可：降序 = 最新在前
    val sorted = byDay.entries.sortedByDescending { it.key }
    var offset = 0
    return sorted.map { (day, list) ->
        val sortedAssets = list.sortedByDescending { it.fileCreatedAt }
        val group = DayGroup(
            date = day,
            label = dayLabel(day),
            assets = sortedAssets,
            startIndex = offset,
        )
        offset += sortedAssets.size
        group
    }
}

/** 从按天的时间线里提取「年份 → 第一天」的跳转表 */
private fun buildYears(days: List<DayGroup>): List<YearJump> {
    val byYear = LinkedHashMap<String, MutableList<Int>>()
    days.forEachIndexed { index, day ->
        byYear.getOrPut(day.date.take(4)) { mutableListOf() }.add(index)
    }
    return byYear.entries
        .sortedByDescending { it.key }
        .map { (year, indexes) ->
            YearJump(
                year = year,
                label = if (year == UNKNOWN_YEAR) "日期未知" else "${year}年",
                firstDayIndex = indexes.min(),
                photoCount = indexes.sumOf { days[it].assets.size },
            )
        }
}

/**
 * 算出时间线当前视口里显示的是 days 的第几天。
 * 时间线里每一天占 2 项：吸顶日期头（偶数下标）+ 照片行（奇数下标）。
 * 优先取第一个「照片行」项；它还没出现在视口里时（比如刚停在最顶部），退回第一项。
 */
private fun visibleDayIndex(state: LazyListState): Int {
    val visible = state.layoutInfo.visibleItemsInfo
    visible.firstOrNull { it.index % 2 == 1 }?.let { return (it.index - 1) / 2 }
    return (visible.firstOrNull()?.index ?: 0) / 2
}

private fun dayLabel(day: String): String {
    if (day == UNKNOWN_DAY) return "日期未知"
    val parts = day.split("-")
    if (parts.size != 3) return day
    val y = parts[0].toIntOrNull() ?: return day
    val m = parts[1].toIntOrNull() ?: return day
    val d = parts[2].toIntOrNull() ?: return day
    return "${y}年${m}月${d}日 ${WEEKDAYS[dayOfWeek(y, m, d)]}"
}

private fun monthLabel(month: String): String {
    val parts = month.split("-")
    if (parts.size != 2) return ""
    val y = parts[0].toIntOrNull() ?: return ""
    val m = parts[1].toIntOrNull() ?: return ""
    if (y <= 0 || m <= 0) return ""
    return "${y}年${m}月"
}

/** 计算星期：返回 0=周日 … 6=周六（Sakamoto 算法，格里高利历） */
private fun dayOfWeek(y: Int, m: Int, d: Int): Int {
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val yy = if (m < 3) y - 1 else y
    return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
}

/**
 * 把一组时间桶限并发地拉取出来，但**按桶顺序**逐个把结果交给 [onBatch]（in-order 冲刷）。
 *
 * 为什么按顺序而不是等全部拉完：几千张照片的相册有二十几个时间桶，全等回来用户要盯着
 * 「正在加载…」很久。这里让最新的月份先出结果先显示，更早的月份在后台继续拉、到了就补上，
 * 界面即时可用。按桶顺序提交 ensure 新数据永远是一段「更早」的照片，追加在时间线末尾，
 * 已经显示的那些天在扁平列表里的下标不变，预览页返回定位不会错位。
 *
 * 单个桶失败不拖垮整体：跳过它继续后面的，失败与否由 [onBatch] 的 ok 参数告知调用方。
 *
 * 在协程里调用（内部有 Retrofit suspend 调用 + async 并发）。
 */
private suspend fun streamBucketAssets(
    albumId: String,
    buckets: List<TimelineBucketDto>,
    onBatch: (bucket: TimelineBucketDto, assets: List<AssetDto>, ok: Boolean) -> Unit,
) {
    val semaphore = Semaphore(4) // 并发度上限，避免同时打爆服务器和内存
    coroutineScope {
        val deferred = buckets.map { bucket ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ImmichClient.apiForShare()
                        .getTimelineBucket(albumId, bucket.timeBucket, ImmichClient.shareKey)
                        .toAssets()
                }
            }
        }
        deferred.forEachIndexed { i, job ->
            var bucketAssets = emptyList<AssetDto>()
            var ok = true
            try {
                bucketAssets = job.await()
            } catch (e: CancellationException) {
                throw e // 切屏 / 换链接取消，原样往上抛
            } catch (_: Exception) {
                ok = false
            }
            onBatch(buckets[i], bucketAssets, ok)
        }
    }
}
