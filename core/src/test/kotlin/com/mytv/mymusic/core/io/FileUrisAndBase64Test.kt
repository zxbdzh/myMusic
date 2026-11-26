package com.mytv.mymusic.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 搬进 :core 之后新增的两个平台等价物的行为约束：
 * [Base64Codec] 要能顶替 `android.util.Base64`，[fileUri] 要能顶替 `android.net.Uri.fromFile`。
 *
 * 路径断言都基于 `File.absolutePath` 现算，避免在 Windows 上跑时把 `/data/...` 当成绝对路径
 * （那里会被解析成 `当前盘符:/data/...`）。
 */
class FileUrisAndBase64Test {
    @Test
    fun `file uri keeps separators and percent encodes non ascii`() {
        val file = File(File(System.getProperty("java.io.tmpdir")), "天晴 a.flac")
        val uri = fileUri(file)
        assertTrue("必须以 file:/// 开头：$uri", uri.startsWith("file:///"))
        // 斜杠保留，空格与中文按 UTF-8 百分号编码（与 android.net.Uri.fromFile 一致）
        assertTrue("斜杠不应被转义：$uri", !uri.contains("%2F"))
        assertTrue("空格要编码：$uri", uri.contains("%20"))
        assertTrue("中文要编码：$uri", uri.contains("%E5%A4%A9%E6%99%B4"))
        assertTrue("中文与空格都要编码：$uri", uri.endsWith("/%E5%A4%A9%E6%99%B4%20a.flac"))
    }

    @Test
    fun `metro style windows path becomes a valid file uri`() {
        val uri = fileUri(File("C:\\Users\\demo\\Music\\a b.flac"))
        assertEquals("file:///C:/Users/demo/Music/a%20b.flac", uri)
        // 盘符的冒号不编码，否则不是可打开的文件 URI
        assertTrue(uri.startsWith("file:///C:/"))
    }

    @Test
    fun `base64 round trips utf8 without wrapping`() {
        val text = "myMusic 晴天 · 2026"
        val encoded = Base64Codec.encodeUtf8(text)
        assertEquals(false, encoded.contains('\n'))
        assertEquals(text, Base64Codec.decodeUtf8(encoded))
        // 与 android.util.Base64.NO_WRAP 的已知输出一致
        assertEquals("aGVsbG8=", Base64Codec.encodeUtf8("hello"))
        // 无补齐的输入也要能解（Base64.DEFAULT 接受）
        assertEquals("hello", Base64Codec.decodeUtf8("aGVsbG8"))
    }

    @Test
    fun `base64 decode ignores line breaks and rejects garbage`() {
        val bytes = ByteArray(200) { (it % 251).toByte() }
        val wrapped = Base64Codec.encode(bytes, wrap = true)
        assertTrue(wrapped.contains('\n'))
        assertEquals(Base64Codec.encode(bytes), wrapped.replace("\n", ""))
        assertEquals(bytes.toList(), Base64Codec.decode(wrapped)!!.toList())
        assertNull(Base64Codec.decode("这不是 base64"))
        assertEquals(0, Base64Codec.decode("")!!.size)
    }
}
