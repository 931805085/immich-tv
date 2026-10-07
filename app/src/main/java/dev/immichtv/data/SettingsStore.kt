package dev.immichtv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 历史条目：URL + 相册名称（相册名称在加载后填充） */
@Serializable
data class ShareLinkEntry(
    val url: String,
    val albumName: String = "",
)

/**
 * 保存当前使用的共享链接 + 历史共享链接列表。
 *
 * 家庭场景下主要是共享链接访问（无需账号），
 * 这里只负责持久化：记住上次用过的链接，并维护一个历史列表，
 * 让换设备/换了服务器后不用总是重新粘贴共享链接。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("immich_tv_settings", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前选中的共享链接（https://服务器/share/xxx），空表示尚未配置 */
    var currentShareLink: String
        get() = prefs.getString("current_share_link", "") ?: ""
        set(value) = prefs.edit().putString("current_share_link", value).apply()

    /** 历史共享链接列表（最新的在最前） */
    fun shareLinks(): List<ShareLinkEntry> {
        val raw = prefs.getString("share_links", "[]") ?: "[]"
        // 新格式优先
        val entries = runCatching { json.decodeFromString<List<ShareLinkEntry>>(raw) }
        if (entries.isSuccess) return entries.getOrThrow()
        // 迁移：旧格式是 List<String>
        val urls = runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        return urls.map { ShareLinkEntry(url = it) }
    }

    /** 加入历史（去重、最新在前、最多保留 20 条） */
    fun addShareLink(link: String, albumName: String = "") {
        val normalized = link.trim()
        if (normalized.isBlank()) return
        val entry = ShareLinkEntry(url = normalized, albumName = albumName)
        val list = shareLinks().filter { it.url != normalized }.toMutableList()
        list.add(0, entry)
        while (list.size > 20) list.removeAt(list.size - 1)
        prefs.edit().putString("share_links", json.encodeToString(list)).apply()
    }

    /** 从历史中删除某条 */
    fun removeShareLink(link: String) {
        val list = shareLinks().filter { it.url != link }
        prefs.edit().putString("share_links", json.encodeToString(list)).apply()
    }

    /** 加载相册后回填相册名称到历史条目 */
    fun updateAlbumName(url: String, name: String) {
        if (name.isBlank()) return
        val list = shareLinks().map {
            if (it.url == url) it.copy(albumName = name) else it
        }
        prefs.edit().putString("share_links", json.encodeToString(list)).apply()
    }

    /** 是否已有可用配置（有当前共享链接就算配置好了） */
    fun isConfigured(): Boolean = currentShareLink.isNotBlank()
}
