package com.mytv.mymusic.data.native

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把卡密流包装成本机 HLS：`/init.mp4` 是重写后的 init，`/seg/N` 按需下载并解密。
 * ExoPlayer 只把它当普通 m3u8，拖动进度时自然会请求对应分片。
 */
internal class AppleCardStreamServer(
    private val api: AppleCardApi,
    private val card: String,
    private val songId: String,
    private val cacheRoot: File,
) : NanoHTTPD("127.0.0.1", 0) {
    private lateinit var source: AppleCardSource
    private lateinit var media: AppleCardMedia
    private lateinit var initBytes: ByteArray
    private lateinit var playlist: String
    private val templates = HashMap<String, Long>()
    private val segmentLocks = ConcurrentHashMap<Int, Any>()
    private val dir: File by lazy { File(cacheRoot, "apple-card-$songId").apply { mkdirs() } }

    suspend fun prepare(quality: String) = withContext(Dispatchers.IO) {
        source = api.playbackSource(card, songId, quality)
        val masterUrl = source.url
        val variant = api.pickAacVariant(masterUrl, api.fetchText(masterUrl))
        media = api.parseMedia(variant, api.fetchText(variant))
        val rawInit = api.fetchRange(media.fileUrl, media.init.offset, media.init.length)
        // mvhd/tkhd/mdhd 的 timescale 是 44100；EXTINF 只有秒，换算成采样数足够定位总长
        initBytes = AppleCardFmp4.rewriteInit(rawInit, media.durationMs * 44100 / 1000)
    }

    /** 启动后返回给 ExoPlayer 的地址；同一个实例重复调用不会重复监听。 */
    fun startAndUrl(): String {
        if (!isAlive) start(5000, true)
        playlist = buildPlaylist(listeningPort)
        return "http://127.0.0.1:$listeningPort/index.m3u8"
    }

    private fun buildPlaylist(port: Int): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-TARGETDURATION:15")
        appendLine("#EXT-X-MAP:URI=\"http://127.0.0.1:$port/init.mp4\"")
        media.segments.forEachIndexed { index, segment ->
            appendLine("#EXTINF:${segment.durationSeconds},")
            appendLine("http://127.0.0.1:$port/seg/$index")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?')
        return try {
            when {
                path == "/index.m3u8" -> respond(
                    playlist.toByteArray(Charsets.UTF_8),
                    "application/vnd.apple.mpegurl",
                    session,
                )
                path == "/init.mp4" -> respond(initBytes, "video/mp4", session)
                path.startsWith("/seg/") -> {
                    val index = path.removePrefix("/seg/").toIntOrNull()
                    if (index == null || index !in media.segments.indices) notFound()
                    else respond(segment(index), "video/mp4", session)
                }
                else -> notFound()
            }
        } catch (error: Throwable) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                error.message ?: "Apple 卡密流错误",
            )
        }
    }

    private fun segment(index: Int): ByteArray {
        val target = File(dir, "seg-$index.m4s")
        if (target.exists()) return target.readBytes()
        synchronized(segmentLocks.computeIfAbsent(index) { Any() }) {
            if (target.exists()) return target.readBytes()
            val spec = media.segments[index]
            val raw = api.fetchRange(media.fileUrl, spec.offset, spec.length)
            val handle = templateHandle(spec.keyUri)
            val plain = AppleCardFmp4.decryptSegment(raw) { data ->
                if (handle != null) TemariNative.decrypt(handle, data) else data
            }
            target.writeBytes(plain)
            return plain
        }
    }

    private fun templateHandle(keyUri: String): Long? {
        if (keyUri.isBlank()) return null
        synchronized(templates) {
            templates[keyUri]?.let { return it }
            // 默认 key 服务端要 musicId="0"，轮换 key 用当前歌曲
            val musicId = if (keyUri.startsWith("skd://itunes.apple.com/P000000000/")) "0" else songId
            val handle = TemariNative.create(api.template(card, musicId, keyUri, source.keyEndpoint))
            if (handle == 0L) throw IllegalStateException("Apple 解密模板无效")
            templates[keyUri] = handle
            return handle
        }
    }

    private fun respond(data: ByteArray, mime: String, session: IHTTPSession): Response {
        val range = session.headers.entries
            .firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value
        if (range.isNullOrBlank()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(data), data.size.toLong())
                .apply { addHeader("Accept-Ranges", "bytes") }
        }
        val match = Regex("bytes=(\\d*)-(\\d*)").find(range) ?: return notFound()
        val start = match.groupValues[1].toLongOrNull() ?: 0L
        val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
        if (start < 0 || start >= data.size || end < start) return notFound()
        val length = (end - start + 1).toInt()
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mime,
            ByteArrayInputStream(data, start.toInt(), length),
            length.toLong(),
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$end/${data.size}")
        }
    }

    private fun notFound(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
}
