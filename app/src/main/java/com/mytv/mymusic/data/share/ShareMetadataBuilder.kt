package com.mytv.mymusic.data.share

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.native.AudioQuality

/** 涉分享的歌词行：只保留排版与裁剪需要的时间与文字。 */
internal data class ShareLyricSource(
    val text: String,
    val atMs: Long,
    val translation: String = "",
)

/**
 * 把一次分享的输入拼成服务端快照。
 *
 * 两条硬规则（服务端会 400，必须在这里拦住）：
 * - 整曲模式不能携带片段字段，片段模式必须有有效范围；
 * - 选句下标必须落在歌词范围内——它和卡片上的行号不是同一套，越界说明调用方传错了。
 *
 * 每句的结束时间取下一句起点，最后一句取歌曲长度：应用内的歌词只有行起点，
 * 没有来源级的结束时间，不能把它当成精准裁剪点，但作为展示区间够用。
 */
internal fun buildShareMetadata(
    song: Song,
    style: ShareCardStyle,
    quality: AudioQuality,
    lines: List<ShareLyricSource>,
    selectedIndexes: Set<Int>,
    durationMs: Long,
    mode: ShareMode,
    clip: ShareClipRange? = null,
    cardSquare: Boolean = false,
    coverHash: String? = null,
): ShareMetadata {
    require(durationMs > 0L) { "分享快照需要歌曲长度" }
    require(selectedIndexes.all { it in lines.indices }) { "选句下标越界：$selectedIndexes" }
    if (mode == ShareMode.Clip) {
        require(clip != null && clip.endMs > clip.startMs) { "片段模式需要有效的片段范围" }
    }

    val lyrics = lines.mapIndexed { index, line ->
        ShareLyricLine(
            text = line.text,
            atMs = line.atMs,
            endMs = lines.getOrNull(index + 1)?.atMs?.takeIf { it > line.atMs } ?: durationMs,
            // 空串会被服务端当成一格空翻译，没有就完全不发这个字段
            translation = line.translation.takeIf { it.isNotBlank() },
        )
    }

    return ShareMetadata(
        title = song.title,
        artists = song.artist,
        sourceId = song.source,
        trackId = song.id,
        quality = quality.shareQuality(),
        template = style.serviceTemplate,
        cardSquare = cardSquare,
        mode = mode,
        durationMs = durationMs,
        lyrics = lyrics,
        selectedLineIndexes = selectedIndexes.sorted(),
        clipStartMs = clip?.takeIf { mode == ShareMode.Clip }?.startMs,
        clipEndMs = clip?.takeIf { mode == ShareMode.Clip }?.endMs,
        // 空链接不是合法 URL，服务端会 400；本地歌就完全不带这个字段
        sourceUrl = song.link.takeIf { it.isNotBlank() },
        coverHash = coverHash,
    )
}
