package com.mytv.mymusic.data.native

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 卡密服务返回的一条播放源：Apple CDN 清单 + 解密模板地址。 */
internal data class AppleCardSource(
    val url: String,
    val adamId: String,
    val keyEndpoint: String,
    val codec: String,
)

internal data class AppleCardRange(val offset: Long, val length: Long)

/** 单个 byterange 分片；keyUri 决定用哪个模板。 */
internal data class AppleCardSegment(
    val offset: Long,
    val length: Long,
    val keyUri: String,
    val durationSeconds: Double,
)

internal data class AppleCardMedia(
    val fileUrl: String,
    val init: AppleCardRange,
    val segments: List<AppleCardSegment>,
    val durationMs: Long,
)

/**
 * 聆澜 Apple Music 卡密服务的客户端。
 *
 * 目录搜索仍走 Apple 公开接口（见 [AppleCardProvider] 里的 AppleProvider），
 * 这里只负责卡密服务：取播放清单、取 Temari 模板、按 Range 拉 Apple CDN。
 */
internal class AppleCardApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun playbackSource(card: String, songId: String, quality: String = "320k"): AppleCardSource {
        val url = "$BASE/api/music/url?source=am&songId=${enc(songId)}&quality=${enc(quality)}"
        val root = jsonObject(get(url, card))
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 200
        if (code >= 400) throw IllegalStateException(root["message"]?.jsonPrimitive?.content ?: "卡密无效或已过期")
        val data = root["data"]?.jsonObject ?: root
        val hls = data.string("url") ?: data.string("hlsPlaylistUrl") ?: data.string("m3u8")
            ?: throw IllegalStateException("服务端没有返回播放清单地址")
        return AppleCardSource(
            url = hls,
            adamId = data.string("adamId") ?: data.string("adam_id") ?: songId,
            keyEndpoint = data.string("keyEndpoint") ?: data.string("key_endpoint") ?: "$BASE/api/music/key",
            codec = data.string("codec").orEmpty(),
        )
    }

    /** 返回模板 JSON 原始字节，直接交给 [TemariNative.create]。 */
    fun template(card: String, musicId: String, keyUri: String, endpoint: String): ByteArray {
        val body = """{"source":"am","musicId":${Json.encodeToString(String.serializer(), musicId)},"uri":${Json.encodeToString(String.serializer(), keyUri)}}"""
        val root = jsonObject(post(endpoint, body, card))
        val data = root["data"]?.jsonObject ?: root
        return data.toString().toByteArray(Charsets.UTF_8)
    }

    fun fetchRange(url: String, offset: Long, length: Long): ByteArray =
        get(url, null, mapOf("Range" to "bytes=$offset-${offset + length - 1}"))

    fun fetchText(url: String): String = String(get(url, null), Charsets.UTF_8)

    /** 主清单里挑 AAC 256k；找不到 mp4a 就取第一个档位。 */
    fun pickAacVariant(masterUrl: String, master: String): String {
        val lines = master.lineSequence().toList()
        var bestBitrate = -1L
        var bestUri: String? = null
        var first: String? = null
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (uri.isBlank() || uri.startsWith("#")) continue
            val resolved = resolve(masterUrl, uri)
            if (first == null) first = resolved
            if (!line.contains("mp4a", ignoreCase = true)) continue
            val bitrate = BANDWIDTH.find(line)?.groupValues?.let {
                it[1].ifBlank { it[2] }.toLongOrNull()
            } ?: 0L
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUri = resolved
            }
        }
        return bestUri ?: first ?: throw IllegalStateException("Apple 清单没有可用音轨")
    }

    /** 解析媒体清单：单文件 + byterange + skd key。 */
    fun parseMedia(mediaUrl: String, media: String): AppleCardMedia {
        var init: AppleCardRange? = null
        var fileUrl: String? = null
        var currentKey = ""
        var pendingRange: AppleCardRange? = null
        var pendingDuration = 0.0
        val segments = mutableListOf<AppleCardSegment>()

        for (raw in media.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = ATTR_URI.find(line)?.groupValues?.getOrNull(1)
                        ?: throw IllegalStateException("初始化段缺少地址")
                    val range = ATTR_BYTERANGE.find(line)?.groupValues
                        ?: throw IllegalStateException("初始化段缺少字节区间")
                    init = AppleCardRange(range[2].toLong(), range[1].toLong())
                    fileUrl = resolve(mediaUrl, uri)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if (!line.contains("METHOD=NONE")) {
                        ATTR_URI.find(line)?.groupValues?.getOrNull(1)
                            ?.takeIf { it.startsWith("skd:") }
                            ?.let { currentKey = it }
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val match = BYTERANGE.find(line.removePrefix("#EXT-X-BYTERANGE:"))
                        ?: throw IllegalStateException("分片字节区间无效")
                    pendingRange = AppleCardRange(match.groupValues[2].toLong(), match.groupValues[1].toLong())
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val range = pendingRange
                    if (range != null && fileUrl == null) fileUrl = resolve(mediaUrl, line)
                    if (range != null) {
                        segments += AppleCardSegment(range.offset, range.length, currentKey, pendingDuration)
                    }
                    pendingRange = null
                }
            }
        }
        val url = fileUrl ?: throw IllegalStateException("媒体清单没有分片")
        return AppleCardMedia(
            fileUrl = url,
            init = init ?: throw IllegalStateException("媒体清单缺少初始化段"),
            segments = segments,
            durationMs = (segments.sumOf { it.durationSeconds } * 1000).toLong(),
        )
    }

    private fun jsonObject(bytes: ByteArray): JsonObject =
        runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }
            .getOrElse { throw IllegalStateException("卡密服务返回了无效 JSON") }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun get(url: String, card: String?, headers: Map<String, String> = emptyMap()): ByteArray =
        execute(Request.Builder().url(url).apply {
            header("user-agent", UA)
            header("accept", "*/*")
            if (!card.isNullOrBlank()) header("x-api-key", card)
            headers.forEach { (name, value) -> header(name, value) }
        }.get().build())

    private fun post(url: String, body: String, card: String): ByteArray =
        execute(Request.Builder().url(url)
            .header("user-agent", UA)
            .header("accept", "application/json")
            .header("x-api-key", card)
            .post(body.toRequestBody(JSON_MEDIA))
            .build())

    private fun execute(request: Request): ByteArray {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty().take(120)}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolve(base: String, value: String): String =
        runCatching { base.toHttpUrlOrNull()?.resolve(value)?.toString() }.getOrNull()
            ?: value

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        const val BASE = "https://source.shiqianjiang.cn"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val ATTR_URI = Regex("URI=\"([^\"]+)\"")
        private val ATTR_BYTERANGE = Regex("BYTERANGE=\"(\\d+)@(\\d+)\"")
        private val BYTERANGE = Regex("^(\\d+)(?:@(\\d+))?$")
        private val BANDWIDTH = Regex("AVERAGE-BANDWIDTH=(\\d+)|BANDWIDTH=(\\d+)")
    }
}
