package com.mytv.mymusic

import com.mytv.mymusic.data.share.ShareAudioPreparer
import com.mytv.mymusic.data.share.SharePrepareException
import com.mytv.mymusic.data.share.SharePrepareFailure
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 音频准备（docs/online-sharing/DESIGN.md「音频准备」）：把取流地址落到临时文件，
 * 地址被 CDN 拒绝时要能区分出来交给上层重新取流，空响应不能当成成功。
 */
class ShareAudioPreparerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var preparer: ShareAudioPreparer

    @Before
    fun setUp() {
        server.start()
        preparer = ShareAudioPreparer(temporary.root.resolve("share"), OkHttpClient())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun downloadsTheWholeStreamIntoATempFile() = runTest {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(payload)).build())

        val file = preparer.prepare(server.url("/audio.mp3").toString(), "mp3")

        assertTrue(file.isFile)
        assertEquals(".mp3", ".${file.extension}")
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun reportsProgressWithTotalSize() = runTest {
        val payload = ByteArray(2048) { 7 }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(payload)).build())
        val seen = mutableListOf<Pair<Long, Long>>()

        preparer.prepare(server.url("/audio.flac").toString(), "flac") { read, total -> seen += read to total }

        assertEquals(2048L to 2048L, seen.last())
    }

    @Test
    fun rejectsWhenTheCdnRefusesTheAddress() = runTest {
        // 401/403/404/410 都是地址本身失效，交给上层重新取流，不是「这首歌不能分享」
        for (code in listOf(401, 403, 404, 410)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            val error = runCatching {
                preparer.prepare(server.url("/audio.mp3").toString(), "mp3")
            }.exceptionOrNull()
            assertTrue("$code 应该算地址被拒", error is SharePrepareException)
            assertEquals(SharePrepareFailure.Rejected, (error as SharePrepareException).failure)
        }
    }

    @Test
    fun reportsOtherHttpFailuresAsIo() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        val error = runCatching { preparer.prepare(server.url("/audio.mp3").toString(), "mp3") }.exceptionOrNull()
        assertEquals(SharePrepareFailure.Io, (error as SharePrepareException).failure)
    }

    @Test
    fun emptyResponseIsNotSuccess() = runTest {
        server.enqueue(MockResponse.Builder().body("").build())
        val error = runCatching { preparer.prepare(server.url("/audio.mp3").toString(), "mp3") }.exceptionOrNull()
        assertEquals(SharePrepareFailure.Empty, (error as SharePrepareException).failure)
    }

    @Test
    fun failedDownloadLeavesNoPartialFile() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        runCatching { preparer.prepare(server.url("/audio.mp3").toString(), "mp3") }

        val files = temporary.root.resolve("share").listFiles().orEmpty()
        assertFalse("失败不能留下半截文件", files.any { it.isFile })
    }

    @Test
    fun sendsTheHeadersTheProviderRequires() = runTest {
        // B 站音频 CDN 不带 Referer 和浏览器 UA 会 403，分享下载必须和正常下载一样带上
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(64))).build())

        preparer.prepare(
            server.url("/audio.m4a").toString(),
            "m4a",
            headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to "ua-test"),
        )

        val request = server.takeRequest()
        assertEquals("https://www.bilibili.com/", request.headers["Referer"])
        assertEquals("ua-test", request.headers["User-Agent"])
    }

    @Test
    fun truncatedDownloadIsReportedAsIo() = runTest {
        // 服务端声明 4096 字节，实际只发 1024 就断开：不能当成功，也不能把半截文件传上去
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(ByteArray(1024) { 3 }))
                .setHeader("Content-Length", "4096")
                .build(),
        )

        val error = runCatching { preparer.prepare(server.url("/audio.mp3").toString(), "mp3") }.exceptionOrNull()

        assertTrue("截断必须归到 Io，上层才好提示重试：${error?.javaClass?.simpleName}", error is SharePrepareException)
        assertEquals(SharePrepareFailure.Io, (error as SharePrepareException).failure)
        val files = temporary.root.resolve("share").listFiles().orEmpty()
        assertFalse("截断的文件不能留在缓存里", files.any { it.isFile })
    }
}

