package dev.immichtv.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证电视内置接收服务器的路由与表单解析。
 *
 * 电视上没法真机验证「手机扫码 → 页面输入 → 电视收到」这条链路，
 * 所以在 JVM 上直接起服务、用 HTTP 客户端打一遍。
 * ShareLinkServer 只依赖 java.net，没有 Android 运行时依赖，可以在普通单元测试里跑。
 */
class ShareLinkServerTest {

    private var port = 0

    @Before
    fun setUp() {
        ShareLinkServer.stop()
        ShareLinkServer.onLinkReceived = { true }
        port = ShareLinkServer.start()
    }

    @After
    fun tearDown() {
        ShareLinkServer.debugReportProvider = null
        ShareLinkServer.stop()
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): Pair<Int, String> = request("GET", path, null)
    private fun post(path: String, body: String): Pair<Int, String> = request("POST", path, body)

    @Test
    fun `server binds a listening port`() {
        assertTrue(port > 0)
        assertTrue(ShareLinkServer.isRunning)
    }

    @Test
    fun `GET root returns the link input page`() {
        val (code, html) = get("/")
        assertEquals(200, code)
        assertTrue(html.contains("action=\"/link\""))
        assertTrue(html.contains("输入共享链接"))
    }

    @Test
    fun `POST with a valid share link is accepted`() {
        val link = "https://p.example.com:9070/share/abcdefghijKLMN"
        val (code, html) = post("/link", "link=" + URLEncoder.encode(link, "UTF-8"))
        assertEquals(200, code)
        assertTrue(html.contains("已发送到电视"))
    }

    @Test
    fun `POST with a malformed link is rejected`() {
        val (code, html) = post("/link", "link=not%20a%20link")
        assertEquals(400, code)
        assertTrue(html.contains("发送失败"))
    }

    @Test
    fun `POST with an empty body is rejected`() {
        val (code, _) = post("/link", "")
        assertEquals(400, code)
    }

    @Test
    fun `unknown path returns 404`() {
        val (code, _) = get("/does-not-exist")
        assertEquals(404, code)
    }

    @Test
    fun `api state endpoint reports the bound port`() {
        val (code, json) = get("/api/state")
        assertEquals(200, code)
        assertTrue(json.contains("\"running\":true"))
        assertTrue(json.contains("\"port\":$port"))
    }

    @Test
    fun `server url is built from the local ip and port`() {
        // 0.0.0.0 监听时 serverUrl 仍应给出可读的 http://ip:port/ 形式
        val url = ShareLinkServer.serverUrl
        if (url.isNotEmpty()) {
            assertTrue(url.startsWith("http://"))
            assertTrue(url.endsWith(":$port/"))
        }
    }

    @Test
    fun `debug endpoint serves the injected crash report`() {
        ShareLinkServer.debugReportProvider =
            { "java.lang.IllegalStateException: 模拟崩溃\n\tat dev.immichtv.ui.HomeScreen.loadAlbum" }
        val (code, text) = get("/debug")
        assertEquals(200, code)
        assertTrue(text.contains("模拟崩溃"))
        assertTrue(text.contains("HomeScreen"))
    }

    @Test
    fun `debug endpoint reports a clean state when nothing crashed`() {
        ShareLinkServer.debugReportProvider = null
        val (code, text) = get("/debug")
        assertEquals(200, code)
        assertTrue(text.contains("没有崩溃记录"))
        assertTrue(text.contains("running=true"))
    }
}
