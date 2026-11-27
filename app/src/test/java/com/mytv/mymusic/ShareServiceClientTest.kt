package com.mytv.mymusic

import com.mytv.mymusic.data.share.ShareLyricLine
import com.mytv.mymusic.data.share.ShareMetadata
import com.mytv.mymusic.data.share.ShareMode
import com.mytv.mymusic.data.share.ShareQuality
import com.mytv.mymusic.data.share.ShareServiceClient
import com.mytv.mymusic.data.share.ShareServiceErrorKind
import com.mytv.mymusic.data.share.ShareServiceException
import com.mytv.mymusic.data.share.ShareState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ShareServiceClientTest {
    private val server = MockWebServer()
    private lateinit var audio: File
    private lateinit var client: ShareServiceClient

    @Before
    fun setUp() {
        server.start()
        audio = File.createTempFile("share-audio", ".mp3").apply { writeBytes(ByteArray(2048) { 7 }) }
        client = ShareServiceClient(server.url("/").toString(), TOKEN, OkHttpClient())
    }

    @After
    fun tearDown() {
        audio.delete()
        server.close()
    }

    @Test
    fun createUploadsCoverThenAudioWhenServerAwaitsIt() = runTest {
        server.enqueue(json(201, """{"id":"share-1","status":"awaiting_upload"}"""))
        server.enqueue(json(201, """{"id":"share-1","status":"cover_uploaded"}"""))
        server.enqueue(json(202, """{"id":"share-1","status":"processing"}"""))

        val result = client.create(
            idempotencyKey = "idempotency-key-1",
            metadata = metadata(),
            audio = audio,
            audioType = "audio/mpeg",
            cover = COVER,
            coverType = "image/png",
        )

        assertEquals("share-1", result.id)
        assertEquals(ShareState.Processing, result.state)

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/shares", create.url.encodedPath)
        assertEquals("Bearer $TOKEN", create.headers["Authorization"])
        assertEquals(server.url("/").toString().trimEnd('/'), create.headers["Origin"])
        assertEquals("idempotency-key-1", create.headers["Idempotency-Key"])
        // 文本类型会被 OkHttp 补上 charset，只比较主类型
        assertTrue(create.headers["Content-Type"].orEmpty().startsWith("application/json"))
        // 整曲模式不能带片段字段，否则服务端判 invalid_metadata
        val body = Json.parseToJsonElement(create.body?.utf8().orEmpty()).jsonObject
        assertNotNull(body["lyrics"])
        assertNotNull(body["selectedLineIndexes"])
        assertNull(body["clipStartMs"])
        assertNull(body["clipEndMs"])

        val cover = server.takeRequest()
        assertEquals("/api/shares/share-1/cover", cover.url.encodedPath)
        assertEquals("image/png", cover.headers["Content-Type"])

        val upload = server.takeRequest()
        assertEquals("/api/shares/share-1/audio", upload.url.encodedPath)
        assertEquals("audio/mpeg", upload.headers["Content-Type"])
        assertEquals(audio.length(), upload.body!!.size.toLong())
    }

    @Test
    fun createReturnsReadyWithoutUploadingAgain() = runTest {
        server.enqueue(json(200, """{"id":"share-2","status":"ready","url":"https://share.example/s/token"}"""))

        val result = client.create("idempotency-key-2", metadata(), audio, "audio/mpeg")

        assertEquals(ShareState.Ready, result.state)
        assertEquals("https://share.example/s/token", result.url)
        // 已经发布过：不再上传封面和音频
        assertEquals(1, server.requestCount)
    }

    @Test
    fun clipModeCarriesRangeIncludingZeroStart() = runTest {
        server.enqueue(json(201, """{"id":"share-3","status":"awaiting_upload"}"""))
        server.enqueue(json(202, """{"id":"share-3","status":"processing"}"""))

        client.create("idempotency-key-3", metadata(mode = ShareMode.Clip), audio, "audio/mpeg")

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
        // 0 是合法片段起点：默认值 null 不能把 0 当成"没填"省掉
        assertEquals("0", body["clipStartMs"].toString())
        assertEquals("4500", body["clipEndMs"].toString())
        assertEquals("\"clip\"", body["mode"].toString())
    }

    @Test
    fun createRejectsRevokedShare() = runTest {
        server.enqueue(json(200, """{"id":"share-4","status":"revoked"}"""))

        val error = runCatching { client.create("idempotency-key-4", metadata(), audio, "audio/mpeg") }
            .exceptionOrNull()

        assertTrue(error is ShareServiceException)
        assertEquals(ShareServiceErrorKind.Gone, (error as ShareServiceException).kind)
    }

    @Test
    fun mapsHttpFailuresToKinds() = runTest {
        val expected = listOf(
            401 to ShareServiceErrorKind.Unauthorized,
            403 to ShareServiceErrorKind.Forbidden,
            404 to ShareServiceErrorKind.NotFound,
            409 to ShareServiceErrorKind.Conflict,
            410 to ShareServiceErrorKind.Gone,
            413 to ShareServiceErrorKind.PayloadTooLarge,
            415 to ShareServiceErrorKind.UnsupportedMediaType,
            429 to ShareServiceErrorKind.TooManyRequests,
            507 to ShareServiceErrorKind.InsufficientStorage,
            500 to ShareServiceErrorKind.Http,
        )
        for ((code, kind) in expected) {
            server.enqueue(json(code, """{"error":"code-$code"}"""))
            val error = runCatching { client.get("share-1") }.exceptionOrNull()
            assertTrue("$code 应该失败", error is ShareServiceException)
            error as ShareServiceException
            assertEquals("$code 的类型", kind, error.kind)
            assertEquals(code, error.status)
            assertEquals("code-$code", error.code)
        }
    }

    @Test
    fun reuseReturnsNullWhenServiceHasNothingToReuse() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertNull(client.reuse(metadata()))

        server.enqueue(json(200, """{"id":"share-5","status":"ready","url":"https://share.example/s/reused"}"""))
        val reused = client.reuse(metadata())
        assertEquals("share-5", reused?.id)
        assertEquals(ShareState.Ready, reused?.state)
    }

    @Test
    fun listMapsSharesAndDetailLine() = runTest {
        server.enqueue(
            json(
                200,
                """{"shares":[{"id":"share-6","title":"Blue Hour","artists":"A. Example",
                "status":"ready","url":"https://share.example/s/token"}]}""",
            )
        )

        val shares = client.list()

        assertEquals(1, shares.size)
        assertEquals("Blue Hour", shares.single().title)
        assertEquals(ShareState.Ready, shares.single().state)
        assertEquals("A. Example · 可收听", shares.single().detail)
        assertEquals("/api/shares", server.takeRequest().url.encodedPath)
    }

    @Test
    fun revokeSendsDeleteAndSurfacesConflict() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.revoke("share-7")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/shares/share-7", delete.url.encodedPath)

        server.enqueue(json(409, """{"error":"already_revoked"}"""))
        val error = runCatching { client.revoke("share-7") }.exceptionOrNull()
        assertEquals(ShareServiceErrorKind.Conflict, (error as ShareServiceException).kind)
        assertEquals("already_revoked", error.code)
    }

    @Test
    fun constructorRejectsIncompleteConfiguration() {
        val insecure = runCatching { ShareServiceClient("http://share.example.com", TOKEN, OkHttpClient()) }
            .exceptionOrNull()
        assertEquals(ShareServiceErrorKind.InvalidConfig, (insecure as ShareServiceException).kind)

        val blankToken = runCatching { ShareServiceClient(server.url("/").toString(), "  ", OkHttpClient()) }
            .exceptionOrNull()
        assertEquals(ShareServiceErrorKind.InvalidConfig, (blankToken as ShareServiceException).kind)
    }

    private fun metadata(mode: ShareMode = ShareMode.Full) = ShareMetadata(
        title = "Blue Hour",
        artists = "A. Example",
        sourceId = "catalog",
        trackId = "track-1",
        quality = ShareQuality.High,
        template = "rushed",
        cardSquare = false,
        mode = mode,
        durationMs = 180_000,
        lyrics = listOf(ShareLyricLine(text = "Blue hour", atMs = 1_000, endMs = 4_000)),
        selectedLineIndexes = listOf(0),
        clipStartMs = if (mode == ShareMode.Clip) 0 else null,
        clipEndMs = if (mode == ShareMode.Clip) 4_500 else null,
    )

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private companion object {
        const val TOKEN = "local-test-token"
        val COVER = byteArrayOf(1, 2, 3)
    }
}
