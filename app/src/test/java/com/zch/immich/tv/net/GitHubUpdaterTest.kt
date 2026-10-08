package com.zch.immich.tv.net

import com.zch.immich.tv.net.GitHubUpdater.GitHubAsset
import com.zch.immich.tv.net.GitHubUpdater.GitHubRelease
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动更新的纯逻辑用例：版本比较 / 发布物选择 / GitHub JSON 解析 */
class GitHubUpdaterTest {

    // ---------- 版本比较 ----------

    @Test
    fun `same version is not newer`() {
        assertFalse(GitHubUpdater.versionIsNewer("1.0.0", "1.0.0"))
        assertFalse(GitHubUpdater.versionIsNewer("v1.2.3", "1.2.3"))
    }

    @Test
    fun `patch and minor bumps are newer`() {
        assertTrue(GitHubUpdater.versionIsNewer("1.0.1", "1.0.0"))
        assertTrue(GitHubUpdater.versionIsNewer("1.1.0", "1.0.0"))
        assertTrue(GitHubUpdater.versionIsNewer("v2.0.0", "1.9.9"))
    }

    @Test
    fun `shorter tag segments pad with zero`() {
        // "0.9" == 0.9.0 < 0.9.1
        assertFalse(GitHubUpdater.versionIsNewer("0.9", "0.9.1"))
        // "1.1" == 1.1.0 > 1.0.9
        assertTrue(GitHubUpdater.versionIsNewer("1.1", "1.0.9"))
    }

    @Test
    fun `unknown current version counts as newer available`() {
        assertTrue(GitHubUpdater.versionIsNewer("1.0.0", ""))
    }

    @Test
    fun `unparseable latest tag is never newer`() {
        assertFalse(GitHubUpdater.versionIsNewer("", "1.0.0"))
        assertFalse(GitHubUpdater.versionIsNewer("beta", "1.0.0"))
    }

    // ---------- 发布物选择 ----------

    @Test
    fun `prefers apk asset over zip`() {
        val release = GitHubRelease(
            tagName = "1.0.0",
            assets = listOf(
                GitHubAsset(name = "immich-tv-apk.zip", downloadUrl = "http://x/1.zip", size = 10),
                GitHubAsset(name = "app-release.apk", downloadUrl = "http://x/app.apk", size = 9),
            ),
        )
        assertEquals("app-release.apk", GitHubUpdater.resolveAsset(release)?.name)
    }

    @Test
    fun `falls back to zip when no apk asset`() {
        val release = GitHubRelease(
            tagName = "1.0.0",
            assets = listOf(GitHubAsset(name = "immich-tv-apk.zip", downloadUrl = "http://x/1.zip")),
        )
        assertEquals("immich-tv-apk.zip", GitHubUpdater.resolveAsset(release)?.name)
    }

    @Test
    fun `ignores assets without download url`() {
        val release = GitHubRelease(
            tagName = "1.0.0",
            assets = listOf(
                GitHubAsset(name = "notes.txt", downloadUrl = "http://x/n"),
                GitHubAsset(name = "app-release.apk", downloadUrl = ""),
            ),
        )
        assertNull(GitHubUpdater.resolveAsset(release))
    }

    // ---------- JSON 解析（对齐 GitHub API 字段） ----------

    @Test
    fun `parses the real releases-latest json shape`() {
        val sample = """
        {
          "tag_name": "1.0.0",
          "name": "1.0.0",
          "body": "更新内容",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "immich-tv-apk.zip",
              "size": 10434328,
              "browser_download_url": "https://github.com/931805085/immich-tv/releases/download/1.0.0/immich-tv-apk.zip"
            }
          ]
        }
        """.trimIndent()
        val release = Json { ignoreUnknownKeys = true }.decodeFromString<GitHubRelease>(sample)
        assertEquals("1.0.0", release.tagName)
        assertEquals("更新内容", release.body)
        assertEquals(1, release.assets.size)
        assertEquals("immich-tv-apk.zip", release.assets[0].name)
        assertEquals(10434328L, release.assets[0].size)
        assertTrue(release.assets[0].downloadUrl.startsWith("https://github.com/931805085/immich-tv"))
    }

    // ---------- 下载 + 解包管线 ----------

    @Test
    fun `unzip extracts apk from the real release zip layout`() {
        val dir = Files.createTempDirectory("upd-test").toFile()
        try {
            // 真实发布物 zip 的结构：release/app-release.apk
            val payload = "APK-BYTES".repeat(2000).toByteArray()
            val zip = File(dir, "asset.zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                // 先写一个非 apk 的条目，确保只挑 .apk
                zos.putNextEntry(ZipEntry("META-INF/note.txt"))
                zos.write("not the apk".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("release/app-release.apk"))
                zos.write(payload)
                zos.closeEntry()
            }
            val out = File(dir, "out.apk")
            val result = GitHubUpdater.unzipApk(zip, out)
            assertEquals(out, result)
            assertTrue(out.isFile)
            assertTrue(out.length() > 0)
            assertArrayEquals(payload, out.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unzip returns null when zip has no apk`() {
        val dir = Files.createTempDirectory("upd-test").toFile()
        try {
            val zip = File(dir, "empty.zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("readme.txt"))
                zos.write("hi".toByteArray())
                zos.closeEntry()
            }
            assertNull(GitHubUpdater.unzipApk(zip, File(dir, "out.apk")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `downloadApkTo streams zip from server and extracts apk with progress`() = runBlocking {
        val dir = Files.createTempDirectory("upd-test").toFile()
        val zipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("release/app-release.apk"))
                zos.write("FAKE-APK-CONTENT".toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            // 极简 HTTP/1.1 响应器：读完请求头后回应 zip 内容（注意不能提前关输入流，
            // Socket 的流 close 会连带关掉 socket，导致响应发不出去）
            Thread {
                runCatching {
                    server.accept().use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        var line = reader.readLine()
                        while (line != null && line.isNotEmpty()) line = reader.readLine()
                        sock.getOutputStream().use { out ->
                            out.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${zipBytes.size}\r\n" +
                                    "Content-Type: application/octet-stream\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                            out.write(zipBytes)
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            val url = "http://127.0.0.1:${server.localPort}/immich-tv-apk.zip"
            val release = GitHubRelease(
                tagName = "1.0.0",
                assets = listOf(
                    GitHubAsset(name = "immich-tv-apk.zip", downloadUrl = url, size = zipBytes.size.toLong()),
                ),
            )
            var downloaded = 0L
            var total = -1L
            val apk = GitHubUpdater.downloadApkTo(dir, release) { d, t -> downloaded = d; total = t }
            assertNotNull(apk)
            assertTrue(apk!!.isFile)
            assertEquals("FAKE-APK-CONTENT", apk.readText())
            assertEquals(zipBytes.size.toLong(), total)
            assertEquals(zipBytes.size.toLong(), downloaded)
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }
}