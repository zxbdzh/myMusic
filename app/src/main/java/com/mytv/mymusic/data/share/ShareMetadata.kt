package com.mytv.mymusic.data.share

import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.native.AudioQuality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 分享服务的元数据快照。字段名与服务端 `share-api.ts` 的 `parseMetadata` 一一对应，服务端逐项校验、
 * 缺一即 400，所以这里的默认值直接决定"哪些字段可以不发"：
 *
 * - 片段字段（[clipStartMs]、[clipEndMs]）在整曲模式下**必须完全不出现**：服务端见到整曲模式带这两个
 *   字段会报 `invalid_metadata`。靠默认值 null + `encodeDefaults = false` 省略，不要在调用方拼可选。
 * - 数组字段（[lyrics]、[selectedLineIndexes]）不能省，服务端不接受 JSON 的 null。
 */
@Serializable
internal data class ShareMetadata(
    val title: String,
    val artists: String,
    val sourceId: String,
    val trackId: String,
    val quality: ShareQuality,
    val template: String,
    val cardSquare: Boolean,
    val mode: ShareMode,
    val durationMs: Long,
    val lyrics: List<ShareLyricLine>,
    val selectedLineIndexes: List<Int>,
    val accountId: String? = null,
    val playbackId: String? = null,
    /** 接收页默认打开歌词还是舞台；不传由服务端用默认值。 */
    val defaultView: String? = null,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    /** 歌曲在原平台的页面链接，只用于接收页的辅助入口。 */
    val sourceUrl: String? = null,
    /** 封面字节的 sha256（hex），服务端用它判断封面是否换过。 */
    val coverHash: String? = null,
)

/** 一句歌词。`endMs` 必须大于 `atMs` 且不超过整曲时长，否则服务端拒绝整条快照。 */
@Serializable
internal data class ShareLyricLine(
    val text: String,
    val atMs: Long,
    val endMs: Long,
    val translation: String? = null,
    val words: List<ShareLyricWord>? = null,
    val stageEndMs: Long? = null,
)

/** 逐字时间。只有来源给了真实逐字时间才发，推算出来的不能冒充。 */
@Serializable
internal data class ShareLyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** 服务端只认这三档；应用内的音质枚举要落到其中一档再发。 */
@Serializable
internal enum class ShareQuality {
    @SerialName("standard")
    Standard,

    @SerialName("high")
    High,

    @SerialName("lossless")
    Lossless,
}

@Serializable
internal enum class ShareMode {
    @SerialName("full")
    Full,

    @SerialName("clip")
    Clip,
}

/**
 * 应用内的音质落到服务端的三档。档位只用于接收页显示，不影响音频本身，
 * 所以把多个接近的档并到同一档即可，不需要在服务端再细分。
 */
internal fun AudioQuality.shareQuality(): ShareQuality = when (this) {
    AudioQuality.Master, AudioQuality.HiRes, AudioQuality.Lossless -> ShareQuality.Lossless
    AudioQuality.High, AudioQuality.Higher, AudioQuality.Ultra,
    AudioQuality.Spatial, AudioQuality.Vivid, AudioQuality.Atmos, AudioQuality.Atmos51 -> ShareQuality.High
    AudioQuality.Auto, AudioQuality.Preview, AudioQuality.Low, AudioQuality.Standard -> ShareQuality.Standard
}

/**
 * 卡片样式对应的服务端模板名。10 款样式一一对应，服务端白名单在
 * `oh-mygo-share` 的 `SHARE_TEMPLATES`（旧的 5 个桌面模板名也在里面）。
 */
internal val ShareCardStyle.serviceTemplate: String
    get() = when (this) {
        ShareCardStyle.Spotify -> "spotify"
        ShareCardStyle.Rushed -> "rushed"
        ShareCardStyle.Vertical -> "vertical"
        ShareCardStyle.Couplet -> "couplet"
        ShareCardStyle.AlbumArt -> "album-art"
        ShareCardStyle.Hypnotic -> "hypnotic"
        ShareCardStyle.Quote -> "quote"
        ShareCardStyle.Messy -> "messy"
        ShareCardStyle.Brat -> "brat"
        ShareCardStyle.Player -> "player"
    }

/** 服务端返回的分享状态与界面用词。顺序照桌面版的「我的分享」，未知状态按原样显示。 */
internal enum class ShareState(val wire: String, val label: String) {
    AwaitingUpload("awaiting_upload", "等待音频"),
    Uploading("uploading", "上传中"),
    Processing("processing", "准备中"),
    Ready("ready", "可收听"),
    Failed("failed", "失败"),
    Expired("expired", "已过期"),
    Revoked("revoked", "已撤销");

    /** 只有这两种状态能继续上传音频。 */
    val acceptsUpload: Boolean
        get() = this == AwaitingUpload || this == Failed

    /** 已撤销、已过期不再提供撤销动作。 */
    val canRevoke: Boolean
        get() = this != Revoked && this != Expired

    companion object {
        fun of(wire: String): ShareState? = entries.firstOrNull { it.wire == wire.trim().lowercase() }
    }
}

/** 创建/查询接口的响应。`url` 只在就绪后有值，`error` 只在失败时有值。 */
@Serializable
internal data class ShareResult(
    val id: String = "",
    val status: String = "",
    val url: String = "",
    val expiresAt: String = "",
    val error: String = "",
) {
    val state: ShareState? get() = ShareState.of(status)
}

/** 「我的分享」列表项。服务端按状态省略部分字段，缺省都算空。 */
@Serializable
internal data class ShareListItem(
    val id: String = "",
    val title: String = "",
    val artists: String = "",
    val mode: String = "",
    val template: String = "",
    val status: String = "",
    val url: String = "",
    val expiresAt: String = "",
    val lastOpenedAt: String = "",
    val error: String = "",
) {
    val state: ShareState? get() = ShareState.of(status)

    /** 列表第二行：歌手 · 状态词；歌手为空时只留状态。 */
    val detail: String
        get() = listOf(artists, state?.label ?: status).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 封面字节的 sha256，服务端用来判断封面是否换过。 */
internal fun shareCoverHash(cover: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(cover).joinToString("") { "%02x".format(it) }

/**
 * 音频文件后缀 → 上传用的 Content-Type。服务端按白名单校验，认不出的一律按 mp3 发，
 * 免得因为一个奇怪的后缀被 415 挡在门外。
 */
internal fun shareMediaType(audioPath: String): String =
    when (audioPath.substringAfterLast('.', "").lowercase()) {
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

/** 一次发布用的幂等键：16 字节随机 hex，满足服务端 `^[\w-]{8,128}$`。 */
internal fun newShareIdempotencyKey(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
