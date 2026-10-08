package com.zch.immich.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.zch.immich.tv.data.UpdateStore
import com.zch.immich.tv.net.GitHubUpdater
import java.io.File
import kotlinx.coroutines.launch

/** 自动更新的界面状态 */
sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    /** 发现新版本，本地还没有介质 → 弹「下载并更新」 */
    data class Prompt(val release: GitHubUpdater.GitHubRelease) : UpdateState
    /** 发现新版本，本地已有下载好的介质 → 直接弹「安装」 */
    data class PromptInstall(
        val release: GitHubUpdater.GitHubRelease,
        val apk: File,
    ) : UpdateState
    /** 正在后台下载（非模态横幅，不阻塞操作） */
    data class Downloading(
        val release: GitHubUpdater.GitHubRelease,
        val downloaded: Long,
        val total: Long,
    ) : UpdateState
    /** 下载完成，弹安装确认 */
    data class ReadyToInstall(
        val release: GitHubUpdater.GitHubRelease,
        val apk: File,
    ) : UpdateState
    data class Fail(val message: String) : UpdateState
}

/**
 * 自动更新宿主：
 *  1. 启动时静默检查 GitHub 最新版；
 *  2. 有新版（且本地没有介质）→ 弹提示 → 确认后**后台下载**（不打断使用，只显示小横幅）；
 *  3. 下载完成 → 弹安装确认 → 调系统安装器；
 *  4. 已下载过的版本（含安装失败后）直接弹安装，不再重复下载；
 *     下载的 APK 留在 filesDir/updates/ 里，进度、失败都不删，防止网络不好时反复拉同一个包。
 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
fun UpdateCheckHost() {
    val context = LocalContext.current
    val store = remember { UpdateStore(context) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val scope = rememberCoroutineScope()

    // 每次进程启动检查一次
    LaunchedEffect(Unit) {
        state = UpdateState.Checking
        val release = GitHubUpdater.checkLatest()
        state = if (release == null) {
            UpdateState.Idle
        } else if (!GitHubUpdater.versionIsNewer(release.tagName, GitHubUpdater.currentVersionName(context))) {
            UpdateState.UpToDate
        } else {
            // 该版本已下载过就直接装，不再重复下载
            val local = GitHubUpdater.downloadedApk(context, store, release)
            if (local != null) UpdateState.PromptInstall(release, local) else UpdateState.Prompt(release)
        }
    }

    /** 后台下载：完成后记住版本并弹安装；失败弹可重试的失败提示 */
    fun startDownload(release: GitHubUpdater.GitHubRelease) {
        scope.launch {
            state = UpdateState.Downloading(release, 0, 0)
            val apk = GitHubUpdater.downloadApk(context, release) { downloaded, total ->
                state = UpdateState.Downloading(release, downloaded, total)
            }
            if (apk == null) {
                state = UpdateState.Fail("下载失败，请确认电视能访问 GitHub 后重试")
                return@launch
            }
            // 记下来：下次同一版本直接弹安装，不重新下载
            store.markDownloaded(release.tagName, apk.name)
            state = UpdateState.ReadyToInstall(release, apk)
        }
    }

    /** 调系统安装器；起不来就给错误（APK 与记录都保留，供下次再装） */
    fun tryInstall(release: GitHubUpdater.GitHubRelease, apk: File) {
        state = UpdateState.Idle
        if (!GitHubUpdater.installApk(context, apk)) {
            state = UpdateState.Fail("无法启动系统安装器，APK 已保存在电视上")
        }
    }

    when (val s = state) {
        // ---------- 后台下载：只在底部显示一条小横幅，不弹窗、不抢焦点 ----------
        is UpdateState.Downloading -> {
            val progress = if (s.total > 0) (s.downloaded * 100f / s.total).coerceIn(0f, 100f) else 0f
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("正在后台下载更新 ${s.release.tagName}…", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${progress.toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }

        // ---------- 模态提示 / 安装 ----------
        is UpdateState.Prompt -> {
            TwoActionDialog(
                title = "发现新版本 ${s.release.tagName}",
                message = s.release.body?.trim()?.take(500) ?: "有新的版本可用，将从 GitHub 下载。",
                primaryText = "下载并更新",
                primaryFocus = true,
                onPrimary = { startDownload(s.release) },
                secondaryText = "稍后再说",
                onSecondary = { state = UpdateState.Idle },
                onDismiss = { state = UpdateState.Idle },
            )
        }

        is UpdateState.PromptInstall -> {
            TwoActionDialog(
                title = "新版本 ${s.release.tagName} 已下载",
                message = s.release.body?.trim()?.take(500) ?: "更新包已下载好，可以直接安装。",
                primaryText = "安装",
                primaryFocus = true,
                onPrimary = { tryInstall(s.release, s.apk) },
                secondaryText = "稍后再说",
                onSecondary = { state = UpdateState.Idle },
                onDismiss = { state = UpdateState.Idle },
            )
        }

        is UpdateState.ReadyToInstall -> {
            TwoActionDialog(
                title = "下载完成",
                message = "新版本 ${s.release.tagName} 已下载完成，是否现在安装？",
                primaryText = "安装",
                primaryFocus = true,
                onPrimary = { tryInstall(s.release, s.apk) },
                secondaryText = "稍后安装",
                onSecondary = { state = UpdateState.Idle },
                onDismiss = { state = UpdateState.Idle },
            )
        }

        is UpdateState.Fail -> {
            val cancelFocus = remember { FocusRequester() }
            UpdateDialog(
                title = "更新失败",
                message = s.message,
                actions = @Composable {
                    TvButton(
                        onClick = { state = UpdateState.Idle },
                        modifier = Modifier.focusRequester(cancelFocus),
                    ) { Text("知道了") }
                },
                onDismiss = { state = UpdateState.Idle },
            )
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        }

        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

/** 两个按钮的提示/安装对话框，默认焦点在主操作上 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun TwoActionDialog(
    title: String,
    message: String,
    primaryText: String,
    primaryFocus: Boolean,
    onPrimary: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryFocusRequester = remember { FocusRequester() }
    val secondaryFocusRequester = remember { FocusRequester() }
    UpdateDialog(
        title = title,
        message = message,
        actions = @Composable {
            TvButton(
                onClick = onPrimary,
                modifier = Modifier
                    .then(if (primaryFocus) Modifier.focusRequester(primaryFocusRequester) else Modifier),
            ) { Text(primaryText) }
            TvButton(
                onClick = onSecondary,
                modifier = Modifier
                    .then(if (!primaryFocus) Modifier.focusRequester(secondaryFocusRequester) else Modifier),
            ) { Text(secondaryText) }
        },
        onDismiss = onDismiss,
    )
    val focusRequester = if (primaryFocus) primaryFocusRequester else secondaryFocusRequester
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** 更新对话框的通用弹窗（仿确认框样式） */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun UpdateDialog(
    title: String,
    message: String,
    actions: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) { actions() }
            }
        }
    }
}