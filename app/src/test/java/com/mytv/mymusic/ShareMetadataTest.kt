package com.mytv.mymusic

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.ShareCardStyle
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.share.ShareClipRange
import com.mytv.mymusic.data.share.ShareLyricSource
import com.mytv.mymusic.data.share.ShareMode
import com.mytv.mymusic.data.share.ShareQuality
import com.mytv.mymusic.data.share.buildShareMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 分享快照的规则（docs/online-sharing/DESIGN.md 的「与分享服务的契约」）：
 * 整曲模式不能带片段字段、选句下标必须落在歌词范围内、模板名与音质映射固定。
 */
class ShareMetadataTest {

    private val song = Song(
        id = "2150000001",
        name = "Blue Hour",
        artist = "A. Example",
        source = "netease",
        duration = 228,
        link = "https://music.163.com/song?id=2150000001",
    )

    private val lines = listOf(
        ShareLyricSource(text = "第一句", atMs = 10_000),
        ShareLyricSource(text = "第二句", atMs = 30_000, translation = "second"),
        ShareLyricSource(text = "第三句", atMs = 60_000),
    )

    @Test
    fun fullModeCarriesNoClipFields() {
        val metadata = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        )
        assertEquals(ShareMode.Full, metadata.mode)
        assertNull(metadata.clipStartMs)
        assertNull(metadata.clipEndMs)
    }

    @Test
    fun clipModeCarriesTheConfirmedRange() {
        val metadata = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0, 1),
            durationMs = 228_000,
            mode = ShareMode.Clip,
            clip = ShareClipRange(startMs = 9_000, endMs = 61_000),
        )
        assertEquals(9_000L, metadata.clipStartMs)
        assertEquals(61_000L, metadata.clipEndMs)
    }

    @Test
    fun clipModeWithoutRangeIsRejected() {
        // 片段模式没有范围就发出去，服务端会判 invalid_metadata；在本地就要拦住
        assertThrows(IllegalArgumentException::class.java) {
            buildShareMetadata(
                song = song,
                style = ShareCardStyle.Rushed,
                quality = AudioQuality.High,
                lines = lines,
                selectedIndexes = setOf(0),
                durationMs = 228_000,
                mode = ShareMode.Clip,
                clip = null,
            )
        }
    }

    @Test
    fun lyricEndUsesNextLineStartAndSongEnd() {
        val metadata = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        )
        assertEquals(listOf(30_000L, 60_000L, 228_000L), metadata.lyrics.map { it.endMs })
        assertEquals("第二句", metadata.lyrics[1].text)
        assertEquals("second", metadata.lyrics[1].translation)
        // 没有翻译的句子不能带空串过去（服务端会当成多一格）
        assertNull(metadata.lyrics[0].translation)
    }

    @Test
    fun selectedIndexesAreSortedAndOutOfRangeRejected() {
        val metadata = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(2, 0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        )
        assertEquals(listOf(0, 2), metadata.selectedLineIndexes)

        assertThrows(IllegalArgumentException::class.java) {
            buildShareMetadata(
                song = song,
                style = ShareCardStyle.Rushed,
                quality = AudioQuality.High,
                lines = lines,
                selectedIndexes = setOf(3),
                durationMs = 228_000,
                mode = ShareMode.Full,
            )
        }
    }

    @Test
    fun templateNamesFollowCardStyles() {
        fun templateOf(style: ShareCardStyle) = buildShareMetadata(
            song = song,
            style = style,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        ).template

        assertEquals("player", templateOf(ShareCardStyle.Player))
        assertEquals("album-art", templateOf(ShareCardStyle.AlbumArt))
        assertEquals("spotify", templateOf(ShareCardStyle.Spotify))
        assertEquals("brat", templateOf(ShareCardStyle.Brat))
    }

    @Test
    fun qualityFallsIntoThreeTiers() {
        fun qualityOf(quality: AudioQuality) = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = quality,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        ).quality

        assertEquals(ShareQuality.Lossless, qualityOf(AudioQuality.Lossless))
        assertEquals(ShareQuality.Lossless, qualityOf(AudioQuality.HiRes))
        assertEquals(ShareQuality.High, qualityOf(AudioQuality.Ultra))
        assertEquals(ShareQuality.Standard, qualityOf(AudioQuality.Auto))
    }

    @Test
    fun songIdentityComesFromTheSongItself() {
        val metadata = buildShareMetadata(
            song = song,
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        )
        assertEquals("Blue Hour", metadata.title)
        assertEquals("A. Example", metadata.artists)
        assertEquals("netease", metadata.sourceId)
        assertEquals("2150000001", metadata.trackId)
        assertEquals(228_000L, metadata.durationMs)
        assertEquals("https://music.163.com/song?id=2150000001", metadata.sourceUrl)
    }

    @Test
    fun blankSourceUrlIsOmitted() {
        // 空串会被服务端当成非法 URL（400），无链接的本地歌必须不带这个字段
        val metadata = buildShareMetadata(
            song = song.copy(link = ""),
            style = ShareCardStyle.Rushed,
            quality = AudioQuality.High,
            lines = lines,
            selectedIndexes = setOf(0),
            durationMs = 228_000,
            mode = ShareMode.Full,
        )
        assertNull(metadata.sourceUrl)
    }
}
