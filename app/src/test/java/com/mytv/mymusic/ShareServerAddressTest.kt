package com.mytv.mymusic

import com.mytv.mymusic.data.share.normalizeShareServerUrl
import com.mytv.mymusic.data.share.shareTokenName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareServerAddressTest {

    @Test
    fun normalizesHttpsOriginAndDropsDefaultPort() {
        assertEquals("https://share.example.com", normalizeShareServerUrl("https://share.example.com/"))
        assertEquals("https://share.example.com", normalizeShareServerUrl("  https://share.example.com  "))
        assertEquals("https://share.example.com", normalizeShareServerUrl("https://share.example.com:443"))
        assertEquals("https://share.example.com:8443", normalizeShareServerUrl("https://share.example.com:8443"))
        // 协议大小写不敏感，规范成小写
        assertEquals("https://share.example.com", normalizeShareServerUrl("HTTPS://share.example.com"))
    }

    @Test
    fun allowsPlainHttpOnlyForLoopback() {
        assertEquals("http://localhost:3000", normalizeShareServerUrl("http://localhost:3000"))
        assertEquals("http://127.0.0.1:3000", normalizeShareServerUrl("http://127.0.0.1:3000"))
        assertEquals("http://[::1]:3000", normalizeShareServerUrl("http://[::1]:3000"))
        // 公网必须走 TLS，否则令牌会明文过网
        assertNull(normalizeShareServerUrl("http://share.example.com"))
        // 看着像回环但不是 IP 字面量：按域名处理，不做 DNS 解析
        assertNull(normalizeShareServerUrl("http://127.0.0.2.nip.io"))
    }

    @Test
    fun rejectsAnythingButABareOrigin() {
        assertNull(normalizeShareServerUrl(""))
        assertNull(normalizeShareServerUrl("   "))
        assertNull(normalizeShareServerUrl("share.example.com"))
        assertNull(normalizeShareServerUrl("ftp://share.example.com"))
        assertNull(normalizeShareServerUrl("https://share.example.com/base/path"))
        assertNull(normalizeShareServerUrl("https://share.example.com?token=1"))
        assertNull(normalizeShareServerUrl("https://share.example.com#top"))
        assertNull(normalizeShareServerUrl("https://user:secret@share.example.com"))
    }

    @Test
    fun tokenNameFollowsOriginSoAddressesDoNotShareSecrets() {
        val first = shareTokenName("https://share.example.com")
        assertEquals(first, shareTokenName("https://share.example.com"))
        assertNotEquals(first, shareTokenName("https://other.example.com"))
        assertNotEquals(first, shareTokenName("https://share.example.com:8443"))
        // 要能直接当 SharedPreferences 的键：只有十六进制字符
        assertFalse(first.any { it.isWhitespace() })
    }
}
