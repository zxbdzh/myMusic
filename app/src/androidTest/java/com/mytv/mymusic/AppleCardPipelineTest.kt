package com.mytv.mymusic

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.native.AppleCardApi
import com.mytv.mymusic.data.native.AppleCardFmp4
import com.mytv.mymusic.data.native.TemariNative
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实链路验证：卡密取流 → 下载 init/首段 → Temari 解密 → MediaExtractor 读出 PCM。
 * 卡密从 `/data/local/tmp/apple-card.txt` 读，避免写进仓库或测试报告。
 */
@RunWith(AndroidJUnit4::class)
class AppleCardPipelineTest {
    @Test
    fun decryptsFirstSegment() {
        val cardFile = File("/data/local/tmp/apple-card.txt")
        assumeTrue("未放卡密文件，跳过", cardFile.exists())
        val card = cardFile.readText().trim()
        assumeTrue("卡密为空，跳过", card.isNotEmpty())

        val api = AppleCardApi()
        val source = api.playbackSource(card, PROBE_SONG, "320k")
        val variant = api.pickAacVariant(source.url, api.fetchText(source.url))
        val media = api.parseMedia(variant, api.fetchText(variant))
        val init = AppleCardFmp4.rewriteInit(
            api.fetchRange(media.fileUrl, media.init.offset, media.init.length),
            media.durationMs * 44100 / 1000,
        )
        val segment = media.segments.first()
        val raw = api.fetchRange(media.fileUrl, segment.offset, segment.length)
        val musicId = if (segment.keyUri.startsWith("skd://itunes.apple.com/P000000000/")) "0" else source.adamId
        val handle = TemariNative.create(api.template(card, musicId, segment.keyUri, source.keyEndpoint))
        assertTrue("模板句柄为空", handle != 0L)
        val plain = AppleCardFmp4.decryptSegment(raw) { TemariNative.decrypt(handle, it) }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "apple-card-first.mp4")
        file.writeBytes(init + plain)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertTrue("没有音轨", extractor.trackCount > 0)
            assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            assertTrue("首段读不出 sample", extractor.readSampleData(buffer, 0) > 0)
        } finally {
            extractor.release()
            file.delete()
        }
    }

    private companion object {
        const val PROBE_SONG = "255921025"
    }
}
