package com.zch.immich.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.zch.immich.tv.diagnose.CrashLogger
import com.zch.immich.tv.net.ShareLinkServer
import com.zch.immich.tv.ui.App
import com.zch.immich.tv.ui.KeyBus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 现代全屏写法。targetSdk 36 上系统强制 edge-to-edge，
        // 旧的 android:windowFullscreen 标志会跟它冲突。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 先装上崩溃记录：电视上看不到 logcat，崩了得留证据
        CrashLogger.install(applicationContext)
        ShareLinkServer.debugReportProvider = { CrashLogger.report }

        // 在 UI 之前就起好内置服务器，连接页首次绘制时二维码和地址就能正确显示
        ShareLinkServer.start()

        setContent {
            App()
        }
    }

    /**
     * 全屏页面（幻灯片 / 播放器）在这里接住方向键和确认键，不用依赖 Compose 焦点。
     * 页面没注册处理器时原样交给系统，BACK、焦点导航等默认行为不受影响。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = KeyBus.handler
        if (handler != null && handler(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
