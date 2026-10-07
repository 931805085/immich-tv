package com.zch.immich.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme

/**
 * 给 tv-material3 组件补上触屏点按。
 *
 * tv-material3 的 Button / Card(onClick) 只响应焦点 + 按键事件
 * （遥控器 D-pad、键盘 Tab+Enter），完全不处理触摸——
 * 手机上只能靠键盘 Tab 切到按钮再按回车，没法直接点。
 *
 * 用 pointerInput + detectTapGestures，而不是 Modifier.clickable：
 *   - clickable 会消费 Enter 键，把电视端的按键交互吃掉；
 *   - clickable 自带 focusable，会抢走 tv-material3 Button 的焦点，
 *     破坏遥控器上那个聚焦放大效果。
 * pointerInput 只处理触摸、不碰焦点也不碰按键，
 * 所以触屏和遥控器各走各的路径，互不干扰。
 *
 * key 用 Unit：pointerInput 只创建一次，回调闭包捕获的是状态引用
 * （比如 input 是 MutableState），调用的时候读到的始终是最新值。
 */
fun Modifier.tvTap(onTap: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures { onTap() }
    }

/**
 * tv-material3 的 [Button]，额外支持手指直接点按。
 *
 * 键盘 Tab+Enter、遥控器 D-pad 的行为完全不变；
 * 手机上可以直接点击。调用方写法和原来的 Button 一样，只是换成 TvButton。
 */
/**
 * 只做视觉展示 + 支持触屏点按的按钮外观，**不参与遥控器的焦点搜索**。
 *
 * 用在「按键要在页面层统一接管」的界面（比如播放器）：左右 / 确定需要在全页
 * 生效。如果按钮也参与焦点搜索，遥控器按左右就会在几个按钮之间挪焦点，
 * 根本走不到页面的按键处理上——按下去毫无反应，用户会觉得遥控坏了。
 *
 * tvTap 只处理触摸、不碰焦点也不碰按键，所以手指点按照常可用，
 * 遥控器和触屏各走各的通道，互不干扰。
 *
 * 外观对齐 tv-material3 的 Button（surfaceVariant 底 + 8dp 圆角 + 16/10dp 内边距），
 * 这样换成非焦点态时视觉上看不出差别。
 */
@Composable
fun TvStatusButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .then(if (enabled) Modifier.tvTap(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * tv-material3 的 [Button]，额外支持手指直接点按。
 *
 * 键盘 Tab+Enter、遥控器 D-pad 的行为完全不变；
 * 手机上可以直接点击。调用方写法和原来的 Button 一样，只是换成 TvButton。
 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.tvTap(onClick),
    ) { content() }
}
