package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.core.CrossfadeMode
import com.mytv.mymusic.playback.core.CrossfadeSettings
import com.mytv.mymusic.playback.core.PlaybackState
import com.mytv.mymusic.playback.core.toPlaybackSource
import com.mytv.mymusic.playback.vlc.VlcTestSupport.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 真放声音的集成用例：本地 flac / mp3 各放一遍，验证
 * 播放 / 暂停 / seek / 音量 / 交叉淡化 / 定时关闭 都能在 libVLC 上观测到。
 *
 * 依赖两样本机资源，缺一个就跳过（CI 上不会红）：
 *  - `MYMUSIC_VLC_HOME`：VLC 3.0.x 目录（含 libvlc.dll）
 *  - `MYMUSIC_TEST_AUDIO_DIR`：`short-a.flac` / `short-b.mp3` 两个 6 秒夹具
 *
 * 30 秒连续播放日志见 `docs/pc/PLAYBACK-ENGINE.md`（由命令行 runner 生成）。
 */
class VlcPlaybackEngineTest {
    private var engine: VlcPlaybackEngine? = null

    @Before
    fun setUp() {
        assumeTrue("本机没装 VLC，跳过 native 播放用例", VlcTestSupport.available)
    }

    @After
    fun tearDown() {
        engine?.release()
        engine = null
    }

    private fun engine(): VlcPlaybackEngine = VlcPlaybackEngine(options = VlcTestSupport.options).also { engine = it }

    private fun fixture(name: String): File {
        val file = VlcTestSupport.audio(name)
        assumeTrue("缺少音频夹具 $name（设 MYMUSIC_TEST_AUDIO_DIR）", file != null)
        return file!!
    }

    private fun song(file: File, title: String): Song = Song(
        id = title,
        name = title,
        artist = "验收",
        album = "playback-vlc",
        duration = 6,
        source = "local",
        url = file.toURI().toString(),
        ext = file.extension,
    )

    @Test
    fun `播放本地 flac`() {
        val flac = fixture("short-a.flac")
        val engine = engine()

        engine.prepare(song(flac, "A").toPlaybackSource())
        engine.play()

        assertTrue("没有进入 Playing", await { engine.snapshot.value.state == PlaybackState.Playing })
        assertTrue("位置没有推进", await { engine.snapshot.value.positionMs > 1_500L })
        assertTrue("时长不对：${engine.snapshot.value.durationMs}", engine.snapshot.value.durationMs in 5_000L..7_000L)
        assertEquals("A", engine.snapshot.value.trackTitle)

        engine.pause()
        assertTrue("没有暂停", await { engine.snapshot.value.state == PlaybackState.Paused })
    }

    @Test
    fun `播放本地 mp3 并 seek`() {
        val mp3 = fixture("short-b.mp3")
        val engine = engine()

        engine.prepare(song(mp3, "B").toPlaybackSource())
        engine.play()
        assertTrue(await { engine.snapshot.value.state == PlaybackState.Playing })

        engine.seekTo(3_000L)
        assertTrue("seek 没生效：${engine.snapshot.value.positionMs}", await { engine.snapshot.value.positionMs >= 2_800L })
    }

    @Test
    fun `音量与静音`() {
        val flac = fixture("short-a.flac")
        val engine = engine()
        engine.prepare(song(flac, "A").toPlaybackSource())
        engine.play()
        assertTrue(await { engine.snapshot.value.state == PlaybackState.Playing })

        engine.setVolume(0.5f)
        assertEquals(0.5f, engine.snapshot.value.volume, 1e-6f)
        assertTrue("libVLC 音量不是 50：${engine.primaryVolumePercent()}", await { engine.primaryVolumePercent() in 45..55 })

        engine.setMuted(true)
        assertEquals(0f, engine.volumeState.value.effective, 1e-6f)
        assertTrue(await { engine.primaryVolumePercent() == 0 })

        engine.setMuted(false)
        assertEquals(0.5f, engine.volumeState.value.effective, 1e-6f)
        assertTrue(await { engine.primaryVolumePercent() in 45..55 })
    }

    @Test
    fun `响度归一化乘进输出音量`() {
        val flac = fixture("short-a.flac")
        val engine = engine()
        engine.prepare(song(flac, "A").toPlaybackSource())
        engine.play()
        assertTrue(await { engine.snapshot.value.state == PlaybackState.Playing })

        engine.setVolume(0.5f)
        engine.setNormalizationDb(6.02f) // ≈ 2 倍，夹到 1.0
        assertEquals(1f, engine.volumeState.value.effective, 1e-3f)
        assertTrue(await { engine.primaryVolumePercent() == 100 })

        engine.setNormalizationDb(-6.02f) // ≈ 0.5 倍
        assertEquals(0.25f, engine.volumeState.value.effective, 1e-3f)
        assertTrue(await { engine.primaryVolumePercent() in 20..30 })
    }

    @Test
    fun `交叉淡化把主播放器切到下一首`() {
        val first = fixture("short-a.flac")
        val second = fixture("short-b.mp3")
        val engine = engine()
        engine.queue.setQueue(listOf(song(first, "A"), song(second, "B")))
        engine.crossfade.configure(CrossfadeSettings(enabled = true, seconds = 4, mode = CrossfadeMode.Fade))
        engine.playAt(0)

        // 淡化过程中采样两路音量：等功率曲线在中间应该各 ~0.7
        val primarySamples = mutableListOf<Int>()
        val incomingSamples = mutableListOf<Int>()
        val fading = await(25_000L) {
            if (engine.crossfade.transition.value != null) {
                primarySamples += engine.primaryVolumePercent()
                incomingSamples += engine.incomingVolumePercent()
            }
            engine.crossfade.report.value != null
        }
        assertTrue("没有完成交叉淡化", fading)

        val report = engine.crossfade.report.value
        assertNotNull(report)
        assertEquals("等功率淡化", report!!.method)
        assertTrue("淡化时长不对：${report.seconds}", report.seconds > 1f)
        assertEquals("主播放器没切到下一首", 1, engine.snapshot.value.queueIndex)
        assertTrue("主路音量没有从 100 降下来：$primarySamples", primarySamples.any { it < 90 })
        assertTrue("次路音量没有从 0 升上去：$incomingSamples", incomingSamples.any { it > 10 })
    }

    @Test
    fun `关掉交叉淡化就整首放完再切`() {
        val first = fixture("short-a.flac")
        val second = fixture("short-b.mp3")
        val engine = engine()
        engine.queue.setQueue(listOf(song(first, "A"), song(second, "B")))
        engine.crossfade.configure(CrossfadeSettings(enabled = false, seconds = 6, mode = CrossfadeMode.None))
        engine.playAt(0)

        assertTrue("没有自动切到第二首", await(15_000L) { engine.snapshot.value.queueIndex == 1 })
        assertTrue(await { engine.snapshot.value.state == PlaybackState.Playing })
        // 关掉过渡不会有"上一次接歌"的报告
        assertEquals(null, engine.crossfade.report.value)
    }

    @Test
    fun `定时关闭播完当前歌曲就停`() {
        val flac = fixture("short-a.flac")
        val engine = engine()
        engine.prepare(song(flac, "A").toPlaybackSource())
        engine.play()
        assertTrue(await { engine.snapshot.value.state == PlaybackState.Playing })

        engine.sleepTimer.stopAfterCurrent()
        assertTrue(engine.sleepTimer.state.value.endOfTrack)

        assertTrue(
            "播完没有停：${engine.snapshot.value.state}",
            await(15_000L) { engine.snapshot.value.state == PlaybackState.Paused },
        )
        // 停了之后定时状态归零，不会下一次播放又立刻暂停
        assertTrue(await { !engine.sleepTimer.state.value.active })
    }

}
