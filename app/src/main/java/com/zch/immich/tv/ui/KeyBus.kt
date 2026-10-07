package com.zch.immich.tv.ui

import android.view.KeyEvent

/**
 * 全局按键总线。
 *
 * 为什么需要它：幻灯片 / 播放器这类全屏页面想要「按遥控器任意键都生效」，
 * 但 Compose 的 onKeyEvent 只有在节点真正拿到焦点时才会被调用。
 *
 * 实测里 ImageViewerScreen 加 .focusable() + FocusRequester.requestFocus()
 * 之后，uiautomator dump 仍然显示整棵语义树里 focused="true" 的节点数为 0
 * ——HorizontalPager 内部的 ScrollableNode 会参与焦点归属，把焦点拦在自己手里，
 * 结果按键派发不到 onKeyEvent，遥控器看起来完全失灵。
 *
 * 改成在 Activity.dispatchKeyEvent 里统一拦截，跟焦点归属彻底解耦：
 * 谁在显示就注册谁的处理器，页面销毁时摘掉，未注册时按键照常走系统默认行为。
 */
object KeyBus {

    /**
     * 当前活动的按键处理器。返回 true 表示已消费，不再往下传系统默认处理
     * （比如 BACK 的返回、方向键的焦点导航）。
     */
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null
}
