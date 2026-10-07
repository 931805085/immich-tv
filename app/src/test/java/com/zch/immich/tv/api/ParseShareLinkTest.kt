package com.zch.immich.tv.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归保护：parseShareLink 的边界情况。
 *
 * 之前线上踩过的坑：用户输入 ,https://host/share/xxx（前面多了个逗号），
 * 函数因为不以 https:// 开头就盲目前面加 https://，拼出 https://,https://...，
 * OkHttp 把 ,https 当成 hostname 去解析，报 UnknownHostException。
 */
class ParseShareLinkTest {

    @Test
    fun fullHttpsLink() {
        val result = ImmichClient.parseShareLink(
            "https://example.com/share/aBcD123XYZ",
        )
        assertNotNull(result)
        assertEquals("https://example.com", result?.host ?: "")
        assertEquals("aBcD123XYZ", result?.key ?: "")
    }

    @Test
    fun hostOnly_autoPrefixHttps() {
        val result = ImmichClient.parseShareLink("example.com/share/abc123")
        assertNotNull(result)
        assertEquals("https://example.com", result?.host ?: "")
        assertEquals("abc123", result?.key ?: "")
    }

    @Test
    fun leadingComma_rejected() {
        // 这就是 UnknownHostException: Unable to resolve host ",https" 的根因
        assertNull(ImmichClient.parseShareLink(",https://example.com/share/abc"))
    }

    @Test
    fun leadingCommaAndSpaces_rejected() {
        assertNull(ImmichClient.parseShareLink("  , example.com/share/abc"))
    }

    @Test
    fun queryParameters_onlyTakeKey() {
        val result = ImmichClient.parseShareLink(
            "https://example.com/share/xyz?key=extra&foo=bar",
        )
        assertNotNull(result)
        assertEquals("xyz", result?.key ?: "")
    }

    @Test
    fun apiPrefix_stripped() {
        val result = ImmichClient.parseShareLink(
            "https://example.com/api/share/abc123",
        )
        assertNotNull(result)
        assertEquals("https://example.com", result?.host ?: "")
        assertEquals("abc123", result?.key ?: "")
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull(ImmichClient.parseShareLink(""))
        assertNull(ImmichClient.parseShareLink("   "))
    }

    @Test
    fun noSharePath_returnsNull() {
        assertNull(ImmichClient.parseShareLink("https://example.com"))
        assertNull(ImmichClient.parseShareLink("example.com"))
    }
}
