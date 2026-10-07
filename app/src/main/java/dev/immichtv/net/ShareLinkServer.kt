package dev.immichtv.net

import dev.immichtv.api.ImmichClient
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 电视内置的「接收服务器」。
 *
 * 电视没有摄像头、遥控器输入一长串链接也很痛苦，所以流程是：
 *  1. 电视起一个 HTTP 服务，把 http://电视IP:端口/ 生成二维码显示在屏幕上；
 *  2. 家人用手机扫码 → 手机浏览器打开这个页面 → 粘贴共享链接 → 点发送；
 *  3. 电视收到链接后配置会话，切到照片页。
 *
 * 说明：Android 上没有内置的 HTTP 服务端（JDK 的 com.sun.net.httpserver 不含在 Android），
 * 所以这里用最底层的 ServerSocket 手写一个极简 HTTP 服务，避免引入额外依赖。
 */
object ShareLinkServer {

    private const val CONTENT_TYPE_HTML = "text/html; charset=utf-8"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var boundPort = 0

    @Volatile var localIp: String = ""
        private set

    /** 收到共享链接时的回调，返回 true 表示链接有效 */
    @Volatile var onLinkReceived: ((String) -> Boolean)? = null

    /**
     * 崩溃记录的提供者（由 MainActivity 注入）。
     * 用可插拔的 lambda 而不是直接依赖 CrashLogger，是为了让这个类保持纯 java.net，
     * 可以在普通 JVM 单元测试里跑通。
     * 提供 GET /debug 路由，手机上就能读到上次崩溃的完整栈。
     */
    @Volatile var debugReportProvider: (() -> String)? = null

    /** 二维码里要显示的内容：http://电视IP:端口/ */
    val serverUrl: String
        get() = if (boundPort == 0 || localIp.isEmpty()) "" else "http://$localIp:$boundPort/"

    /** serverUrl 为空时给 UI 的提示原因；能正常显示时返回空串 */
    val serverUrlStatus: String
        get() = when {
            boundPort == 0 -> "内置服务器未启动"
            localIp.isEmpty() -> "无法获取本机局域网地址，请检查网络"
            else -> ""
        }

    val isRunning: Boolean
        get() = running && server != null

    /** 启动服务（幂等），返回监听端口；失败返回 0 */
    @Synchronized
    fun start(): Int {
        if (running) return boundPort
        localIp = lanAddress()
        try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
            server = socket
            boundPort = socket.localPort
            running = true
            val thread = Thread({ acceptLoop(socket) }, "immich-tv-link-server")
            thread.isDaemon = true
            thread.start()
        } catch (e: Exception) {
            running = false
            boundPort = 0
        }
        return boundPort
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        boundPort = 0
    }

    // ---------- 连接处理 ----------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!running) break else continue
            } ?: continue
            Thread({ handleClient(client) }, "immich-tv-client").start()
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return

            var contentLength = 0
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }
            val body = if (contentLength in 1..65536) {
                val buf = CharArray(contentLength)
                val sb = StringBuilder(contentLength)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = reader.read(buf, 0, remaining)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    remaining -= n
                }
                sb.toString()
            } else ""

            val parts = requestLine.trim().split("\\s+".toRegex())
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(socket, 200, "OK", CONTENT_TYPE_HTML, inputPage())
                method == "POST" && path == "/link" ->
                    handleLink(socket, body)
                method == "GET" && path == "/api/state" ->
                    respond(socket, 200, "OK", "application/json; charset=utf-8", stateJson())
                method == "GET" && path == "/debug" ->
                    respond(socket, 200, "OK", "text/plain; charset=utf-8", debugText())
                else ->
                    respond(socket, 404, "Not Found", CONTENT_TYPE_HTML, genericPage("页面不存在"))
            }
        } catch (e: Exception) {
            // 单个客户端异常不影响服务端
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleLink(socket: Socket, body: String) {
        val link = formValue(body, "link").trim()
        if (link.isEmpty()) {
            respond(socket, 400, "Bad Request", CONTENT_TYPE_HTML, resultPage(false, link, "链接为空"))
            return
        }
        if (ImmichClient.parseShareLink(link) == null) {
            respond(
                socket, 400, "Bad Request", CONTENT_TYPE_HTML,
                resultPage(false, link, "不是有效的共享链接，需要形如 https://服务器/share/xxxx"),
            )
            return
        }
        val ok = onLinkReceived?.invoke(link) ?: false
        respond(
            socket,
            if (ok) 200 else 500,
            if (ok) "OK" else "Internal Server Error",
            CONTENT_TYPE_HTML,
            resultPage(ok, link, if (ok) "" else "电视端保存失败，请再看一次"),
        )
    }

    private fun respond(socket: Socket, status: Int, reason: String, contentType: String, html: String) {
        try {
            val writer = socket.getOutputStream().bufferedWriter()
            writer.write("HTTP/1.1 $status $reason\r\n")
            writer.write("Content-Type: $contentType\r\n")
            writer.write("Content-Length: ${html.toByteArray().size}\r\n")
            writer.write("Cache-Control: no-store\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(html)
            writer.flush()
        } catch (_: Exception) {
        }
    }

    // ---------- 页面 ----------

    /** 手机扫码后看到的输入页 */
    private fun inputPage(): String {
        val current = ImmichClient.currentShareLinkUrl().ifBlank { "" }
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Immich TV - 输入共享链接</title>
<style>
html,body{margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;background:#0f1115;color:#f5f5f5;padding:24px 18px}
.card{background:#1b1f27;border-radius:16px;padding:24px;max-width:560px;margin:0 auto;box-shadow:0 8px 30px rgba(0,0,0,.45)}
h1{font-size:23px;margin:0 0 10px;line-height:1.35}
.hint{color:#9aa0a6;font-size:15px;line-height:1.7;margin:0}
code{background:#0f1115;padding:2px 6px;border-radius:6px;font-size:13px}
input{width:100%;box-sizing:border-box;font-size:17px;padding:15px;border-radius:10px;border:1px solid #3a4048;background:#0f1115;color:#f5f5f5;margin-top:16px}
button{width:100%;box-sizing:border-box;font-size:18px;font-weight:600;padding:16px;border:0;border-radius:10px;background:#3A7BFF;color:#fff;margin-top:14px}
button:disabled{background:#555}
.tiny{color:#6b7075;font-size:12px;margin-top:16px;text-align:center}
</style>
</head>
<body>
<div class="card">
<h1>&#128250; Immich TV · 输入共享链接</h1>
<p class="hint">下面这台电视正在等待共享链接。请粘贴 Immich 的共享链接，形如 <code>https://服务器/share/xxxx</code></p>
<form method="post" action="/link">
<input name="link" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/share/xxxx" value="${escapeHtml(current)}">
<button type="submit">发送到电视</button>
</form>
<p class="tiny">仅在本机局域网内使用，链接不会经过任何第三方</p>
</div>
</body>
</html>
"""
    }

    /** 发送成功 / 失败的反馈页 */
    private fun resultPage(ok: Boolean, link: String, message: String): String {
        val title = if (ok) "&#9989; 已发送到电视" else "&#10060; 发送失败"
        val tip = if (ok) "请看电视屏幕，照片墙马上就会刷新出来。" else (message.ifBlank { "电视端保存失败" })
        return genericPage("""<h1>$title</h1>
<p class="hint">${escapeHtml(tip)}</p>
<p class="hint" style="word-break:break-all">${escapeHtml(link)}</p>
<p class="tiny">如果电视没有反应：确认手机和电视在同一个 Wi-Fi，然后重试。</p>""")
    }

    private fun genericPage(body: String): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Immich TV</title>
<style>
html,body{margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;background:#0f1115;color:#f5f5f5;padding:24px 18px}
.card{background:#1b1f27;border-radius:16px;padding:28px;max-width:560px;margin:0 auto}
h1{font-size:23px;margin:0 0 10px;line-height:1.4}
.hint{color:#9aa0a6;font-size:15px;line-height:1.7;margin:0 0 10px}
.tiny{color:#6b7075;font-size:12px;text-align:center;margin-top:18px}
</style>
</head>
<body><div class="card">$body</div></body>
</html>
"""

    private fun stateJson(): String = buildString {
        append("{")
        append("\"ok\":true,")
        append("\"running\":$running,")
        append("\"port\":$boundPort,")
        append("\"host\":\"$localIp\",")
        append("\"app\":\"${escapeJson(APP_NAME)}\"")
        append("}")
    }

    /**
     * GET /debug 的内容：最近一次崩溃的完整栈。
     * 电视上看不到 logcat，App 崩了就只剩这个页面能拿到证据。
     */
    private fun debugText(): String {
        val report = debugReportProvider?.invoke()
        if (report.isNullOrBlank()) {
            return "没有崩溃记录。\n\n当前状态: running=$running port=$boundPort host=$localIp\n"
        }
        return report
    }

    private const val APP_NAME = "Immich TV"

    // ---------- 工具 ----------

    private fun formValue(body: String, key: String): String {
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            if (k != key) continue
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            return urlDecode(v)
        }
        return ""
    }

    private fun urlDecode(text: String): String =
        try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escapeJson(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 取本机局域网 IPv4 地址（优先 wlan0）。
     * 只用 NetworkInterface，不需要额外权限。
     */
    private fun lanAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""
        var fallback = ""
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            for (inet in ni.inetAddresses) {
                if (inet is Inet4Address && !inet.isLoopbackAddress) {
                    val host = inet.hostAddress ?: continue
                    if (name.startsWith("wlan") || name.startsWith("wlp")) return host
                    if (fallback.isEmpty()) fallback = host
                }
            }
        }
        return fallback
    }
}