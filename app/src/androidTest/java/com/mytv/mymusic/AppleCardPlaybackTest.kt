package com.mytv.mymusic

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytv.mymusic.data.native.AppleCardApi
import com.mytv.mymusic.data.native.AppleCardStreamServer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 用 ExoPlayer 播放本地 HLS，确认清单、init、分片解密链路能被播放器接受。 */
@RunWith(AndroidJUnit4::class)
class AppleCardPlaybackTest {
    @Test
    fun playsLocalHls() {
        val cardFile = File("/data/local/tmp/apple-card.txt")
        assumeTrue("未放卡密文件，跳过", cardFile.exists())
        val card = cardFile.readText().trim()
        assumeTrue("卡密为空，跳过", card.isNotEmpty())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = AppleCardStreamServer(AppleCardApi(), card, PROBE_SONG, context.cacheDir)
        runBlocking { server.prepare("320k") }
        val url = server.startAndUrl()

        val ready = CountDownLatch(1)
        lateinit var player: ExoPlayer
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }
            })
            player.setMediaSource(
                HlsMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url))),
            )
            player.prepare()
        }
        try {
            assertTrue("播放器没有进入 READY", ready.await(40, TimeUnit.SECONDS))
            var duration = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync { duration = player.duration }
            // Apple 的 mvhd 原本是 0，必须补到整首；只读首段时这里会是 15 秒
            assertTrue("时长只有 ${duration}ms，说明只读到首段", duration > 300_000)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
        }
    }

    private companion object {
        const val PROBE_SONG = "255921025"
    }
}
