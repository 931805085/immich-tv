package com.zch.immich.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 自动更新的下载记录：记住「哪个版本已经下载到本地」。
 *
 * 作用：下载不稳定/安装失败时，已下载的 APK 保留在 filesDir/updates/ 里，
 * 下次启动检查到同版本不再重新下载，直接提示安装，避免反复拉同一个包。
 */
class UpdateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("immich_tv_update", Context.MODE_PRIVATE)

    /** 已成功下载到本地的版本号；空表示没有 */
    var downloadedVersion: String
        get() = prefs.getString("downloaded_version", "") ?: ""
        set(value) = prefs.edit().putString("downloaded_version", value).apply()

    /** 已下载 APK 的文件名（位于 filesDir/updates/ 下） */
    var downloadedApkName: String
        get() = prefs.getString("downloaded_apk_name", "") ?: ""
        set(value) = prefs.edit().putString("downloaded_apk_name", value).apply()

    /** 记录某版本已下载完成 */
    fun markDownloaded(version: String, apkName: String) {
        prefs.edit()
            .putString("downloaded_version", version)
            .putString("downloaded_apk_name", apkName)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}