package com.mytv.mymusic.desktop

import com.mytv.mymusic.desktop.state.AudioStream
import com.mytv.mymusic.desktop.state.DefaultMusicStore
import com.mytv.mymusic.desktop.state.DemoMusicCatalog
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.audio.AudioOutputs
import com.mytv.mymusic.desktop.state.audio.UnavailableAudioOutput
import com.mytv.mymusic.desktop.state.audio.VlcAudioOutput
import com.mytv.mymusic.desktop.state.audio.VlcInstallation
import com.mytv.mymusic.playback.core.CrossfadeMode
import com.mytv.mymusic.playback.core.CrossfadeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 音频后端直连契约测试（ADR-0001）：
 * 1. 原生库不可用时绝不静默假播（playing=false、positionMs=0、错误文案明确）；
 * 2. 随包 libVLC 就绪时强类型直连 [VlcAudioOutput]，透出 EQ / 交叉淡化 / 定时关闭并实时生效。
 */
class AudioOutputContractTest {

    private val sampleTrack = Track(
        id = "t-1",
        title = "测试曲目",
        artist = "测试歌手",
        album = "测试专辑",
        durationMs = 180_000L,
        providerId = "qq",
        providerName = "QQ 音乐",
    )

    @Test
    fun `unavailable audio output never simulates playback`() {
        val output = UnavailableAudioOutput("缺少 libvlc.dll")
        assertFalse(output.ready)
        assertEquals("libVLC 未就绪", output.label)

        output.load(sampleTrack, AudioStream(url = "https://example.invalid/a.flac"))
        output.play()
        output.seekTo(12_000L)

        val snap = output.snapshot.value
        assertFalse("后端不可用时绝不能报告 playing=true", snap.playing)
        assertEquals("后端不可用时不能推进假进度", 0L, snap.positionMs)
        assertTrue("必须返回可读错误原因", snap.error.contains("缺少 libvlc.dll"))
    }

    @Test
    fun `explicit missing vlc dir fails cleanly instead of falling back to simulation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val missing = File(System.getProperty("java.io.tmpdir"), "mymusic-nonexistent-vlc-${System.nanoTime()}")
            assertFalse(VlcInstallation.isValidVlcDir(missing))
            val created = VlcAudioOutput.create(scope, explicitHome = missing)
            // 如果环境恰好装了系统/随包 VLC，resolveHome 会兜底；这里至少保证返回类型合法且绝不是静默模拟
            if (created.isSuccess) {
                val out = created.getOrThrow()
                assertTrue(out.ready)
                out.release()
            } else {
                assertTrue(created.exceptionOrNull()?.message.orEmpty().contains("libvlc.dll"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bundled libVLC wires real engine and exposes eq crossfade sleep timer`() {
        val resolved = VlcInstallation.resolveHome()
        assumeTrue("当前环境未缓存随包或系统 libVLC，跳过真机引擎契约测试", resolved != null)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = AudioOutputs.create(scope)
        try {
            assertTrue("检测到 libVLC 后必须直连 VlcAudioOutput：${output.detail}", output is VlcAudioOutput)
            assertTrue(output.ready)

            val eq = output.equalizer
            val xfade = output.crossfade
            val timer = output.sleepTimer
            assertNotNull("均衡器子契约必须透出", eq)
            assertNotNull("交叉淡化子契约必须透出", xfade)
            assertNotNull("定时关闭子契约必须透出", timer)

            eq!!.setEnabled(true)
            eq.selectPreset("pop")
            assertTrue(eq.state.value.enabled)
            assertEquals("pop", eq.state.value.presetId)

            xfade!!.configure(CrossfadeSettings(enabled = true, seconds = 8, mode = CrossfadeMode.Fade))
            assertTrue(xfade.settings.value.enabled)
            assertEquals(8, xfade.settings.value.seconds)
            assertEquals(CrossfadeMode.Fade, xfade.settings.value.mode)

            timer!!.stopAfterCurrent()
            assertTrue(timer.state.value.active)
            assertTrue(timer.state.value.endOfTrack)
            timer.cancel()
            assertFalse(timer.state.value.active)
        } finally {
            output.release()
            scope.cancel()
        }
    }

    @Test
    fun `store exposes engine capabilities when backed by real vlc`() {
        assumeTrue("当前环境无 libVLC，跳过 Store 透出验证", VlcInstallation.resolveHome() != null)
        val store = DefaultMusicStore(catalog = DemoMusicCatalog())
        try {
            assertTrue(store.state.value.engine.ready)
            assertEquals("libVLC", store.state.value.engine.backend)
            assertNotNull(store.equalizer)
            assertNotNull(store.crossfade)
            assertNotNull(store.sleepTimer)
        } finally {
            store.shutdown()
        }
    }
}
