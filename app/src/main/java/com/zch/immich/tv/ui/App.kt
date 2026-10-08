package com.zch.immich.tv.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import com.zch.immich.tv.api.AssetDto
import com.zch.immich.tv.api.ImmichClient
import com.zch.immich.tv.data.SettingsStore
import com.zch.immich.tv.net.ShareLinkServer

/** 极简状态导航（脚手架阶段够用，之后可换 androidx.navigation） */
sealed interface Screen {
    data object Connect : Screen
    /** generation 每切换一次共享链接递增，用来强制重新拉取相册 */
    data class Home(val generation: Long) : Screen
}

/**
 * 预览页路由：叠在 Home 之上，而不是替换 Home —— 返回时列表不重建、不重新拉取相册。
 *
 * [startPaused] 是「上一次导航时的暂停状态要不要继承」：播放器暂停时按左右键跳到图片页，
 * 图片页就接着保持暂停，不会自己重新开始自动翻页。标志跟导航一起走、每次导航都是新值，
 * 所以不需要额外的清理逻辑。
 */
data class ViewerRoute(
    val assets: List<AssetDto>,
    val startIndex: Int,
    val startPaused: Boolean = false,
)

/** 从预览页返回后的定位请求；id 单调递增，连返回同一张也能触发 */
data class LocateRequest(val id: Long, val flatIndex: Int)

/**
 * 用共享链接配置会话（只解析 + 写内存，不落历史）。
 * 返回 true 表示链接格式有效、会话已配置好。
 */
fun applyShareLink(raw: String): Boolean = ImmichClient.configureShareLink(raw)

/** 链接确认可用后，写进历史并设为当前链接 */
fun rememberShareLink(settings: SettingsStore) {
    val url = ImmichClient.currentShareLinkUrl()
    settings.addShareLink(url, ImmichClient.albumName)
    settings.currentShareLink = url
}

@Composable
fun App() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var screen by remember {
        mutableStateOf<Screen>(if (settings.isConfigured()) {
            // serverUrl / shareKey 是内存变量，进程被杀后重启必须重新解析，
            // 否则 HomeScreen 调 apiForShare() 拿到空 base URL，
            // Retrofit 报 IllegalArgumentException: no scheme was found for /api/
            applyShareLink(settings.currentShareLink)
            Screen.Home(0L)
        } else {
            Screen.Connect
        })
    }

    // 记住当前相册的 generation，避免从播放器返回时又被当成"换了链接"重新加载
    var homeGeneration by remember { mutableLongStateOf(0L) }
    // 预览页作为覆盖层叠在列表之上：列表不销毁，返回时不重新拉取相册、不丢滚动位置
    var viewer by remember { mutableStateOf<ViewerRoute?>(null) }
    // 从预览页返回后待定位的那张照片（跨整个相册的扁平索引）
    var locate by remember { mutableStateOf<LocateRequest?>(null) }
    var locateSeq by remember { mutableLongStateOf(0L) }

    /** 回到照片页；reload=true 表示刚换了共享链接，需要重新拉取相册 */
    fun goHome(reload: Boolean) {
        if (reload) homeGeneration++
        viewer = null
        screen = Screen.Home(homeGeneration)
    }

    // 手机扫码后把共享链接发到电视内置服务器；回调在服务器线程上，切页要回到主线程
    LaunchedEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        ShareLinkServer.onLinkReceived = { link ->
            // 先解析并配置会话，再真正请求服务器确认链接有效，手机上就能拿到准确反馈
            val applied = applyShareLink(link)
            val ok = applied && ImmichClient.validateShareLink()
            if (ok) {
                rememberShareLink(settings)
                mainHandler.post { goHome(true) }
            }
            ok
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = screen) {
                    is Screen.Connect -> ConnectScreen(
                        settings = settings,
                        onUseLink = { link ->
                            if (applyShareLink(link)) {
                                rememberShareLink(settings)
                                goHome(true)
                                true
                            } else {
                                false
                            }
                        },
                    )
                    is Screen.Home -> Box(modifier = Modifier.fillMaxSize()) {
                        HomeScreen(
                            generation = s.generation,
                            settings = settings,
                            onOpenAsset = { assets, index -> viewer = ViewerRoute(assets, index) },
                            onSwitchLink = {
                                viewer = null
                                screen = Screen.Connect
                            },
                            locateRequest = locate,
                        )

                        // 预览页叠在列表之上：列表保持组合状态，返回即恢复，不用重新拉取相册
                        val v = viewer
                        if (v != null) {
                            ViewerScreen(
                                assets = v.assets,
                                startIndex = v.startIndex,
                                startPaused = v.startPaused,
                                // 图片页翻到视频、或播放器按左右翻到图片，都从这里换页面。
                                // 第二个参数带上调用方的暂停状态：播放器暂停时翻出去就保持暂停，
                                // 图片页翻到视频时传 false（反向不继承）。
                                onNavigateTo = { index, keepPaused ->
                                    viewer = ViewerRoute(v.assets, index, keepPaused)
                                },
                                onClose = { index ->
                                    viewer = null
                                    locateSeq++
                                    locate = LocateRequest(locateSeq, index)
                                },
                            )
                        }
                    }
                }

                // 自动更新宿主：启动时静默查 GitHub 最新版，有新版本弹提示下载安装
                UpdateCheckHost()
            }
        }
    }
}
