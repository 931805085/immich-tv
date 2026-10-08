package com.zch.immich.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.zch.immich.tv.data.SettingsStore
import com.zch.immich.tv.data.ShareLinkEntry
import com.zch.immich.tv.net.ShareLinkServer

/**
 * 连接页。电视的主路径是「扫码」：
 *
 *  1. 电视把内置服务器的地址（http://电视IP:端口/）做成二维码显示在屏幕上；
 *  2. 家人用手机扫码 → 手机浏览器打开这个页面 → 粘贴共享链接 → 点「发送到电视」；
 *  3. 电视收到链接，配置会话并写进历史，切到照片页。
 *
 * 下面还保留「历史共享链接」和「电视上直接输入」作为兜底。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectScreen(settings: SettingsStore, onUseLink: (String) -> Boolean) {
    val serverUrl = ShareLinkServer.serverUrl
    var history by remember { mutableStateOf(settings.shareLinks()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // 等待用户确认删除的历史条目；非空时弹出确认对话框
    var pendingDelete by remember { mutableStateOf<ShareLinkEntry?>(null) }

    fun reloadHistory() {
        history = settings.shareLinks()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("连接共享相册", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---------- 左：二维码 + 服务器地址 ----------
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (serverUrl.isEmpty()) {
                    Text(ShareLinkServer.serverUrlStatus, color = MaterialTheme.colorScheme.error)
                } else {
                    QrCode(serverUrl, modifier = Modifier.size(300.dp))
                    Text(
                        serverUrl,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        "用手机扫码，在手机上粘贴共享链接",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "手机和电视需要连在同一个 Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---------- 右：历史 + 手动输入 ----------
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                Text("历史共享链接", style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) {
                    Text(
                        "暂无历史，扫码或输入一次后会自动记住",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(history, key = { it.url }) { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.albumName.ifBlank { "共享相册" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        entry.url,
                                        // 尽量少占空间：小号字 + 单行截断，不给 URL 换行
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TvButton(onClick = {
                                    if (onUseLink(entry.url)) {
                                        error = null
                                        reloadHistory()
                                    } else {
                                        error = "该链接已失效，请重新获取共享链接"
                                    }
                                }) { Text("使用") }
                                TvButton(onClick = {
                                    pendingDelete = entry
                                }) { Text("删除") }
                            }
                        }
                    }
                }

                Text(
                    "电视上直接输入",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 14.dp),
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    // 光标显式上色：默认颜色在暗色背景上几乎看不见
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 6.dp)
                        // 描边让输入框在触屏上明确「点这里输入」
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvButton(onClick = {
                        if (onUseLink(input)) {
                            error = null
                            input = ""
                            reloadHistory()
                        } else {
                            error = "不是有效的共享链接（需要 https://服务器/share/xxx）"
                        }
                    }) { Text("使用此链接") }
                }
                error?.let {
                    Text(
                        "错误: $it",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // 删除确认对话框：叠在页面之上，等用户确认后才真正删除
    val toDelete = pendingDelete
    if (toDelete != null) {
        ConfirmDeleteDialog(
            entry = toDelete,
            onConfirm = {
                settings.removeShareLink(toDelete.url)
                if (settings.currentShareLink == toDelete.url) settings.currentShareLink = ""
                reloadHistory()
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 历史链接删除确认框。
 *
 * tv-material 1.x 没有现成的 AlertDialog，这里用 compose-ui 的 [Dialog] 弹一层
 * 独立窗口：背景自动压暗，内容用 Surface 仿 AlertDialog 的版式（标题 + 说明 + 操作区）。
 * 按钮直接用 [TvButton]（触屏 + 遥控器都可操作），默认聚焦「取消」避免误删。
 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun ConfirmDeleteDialog(
    entry: ShareLinkEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 初始焦点放到「取消」上：遥控器按确定/回车默认就是取消，防误删
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                Text(
                    "删除这条共享链接？",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    entry.albumName.ifBlank { "共享相册" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
                Text(
                    entry.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "删除后需重新扫码或手动输入才能恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocus),
                    ) { Text("取消") }
                    TvButton(onClick = onConfirm) { Text("删除") }
                }
            }
        }
    }
    // 等对话框内容组合完成后，把焦点交给「取消」按钮
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}