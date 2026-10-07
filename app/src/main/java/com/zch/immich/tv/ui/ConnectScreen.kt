package com.zch.immich.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                                    settings.removeShareLink(entry.url)
                                    if (settings.currentShareLink == entry.url) settings.currentShareLink = ""
                                    reloadHistory()
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
}