package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.EqualizerAvailability
import com.mytv.mymusic.playback.core.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * 10 段均衡器打到 libVLC：预设真的落到 `libvlc_audio_equalizer_*` 上，
 * 而不是只改了界面状态。
 */
class VlcEqualizerTest {
    private var engine: VlcPlaybackEngine? = null

    @Before
    fun setUp() {
        assumeTrue("本机没装 VLC，跳过 native 均衡器用例", VlcTestSupport.available)
    }

    @After
    fun tearDown() {
        engine?.release()
        engine = null
        PlaybackLog.sink = { line -> println(line) }
    }

    private fun engine(): VlcPlaybackEngine = VlcPlaybackEngine(options = VlcTestSupport.options).also {
        PlaybackLog.sink = { line -> println(line) }
        engine = it
    }

    @Test
    fun `libVLC 报告 10 段`() {
        val engine = engine()
        // 触发 native 初始化并挂上均衡器
        engine.warmUp()
        val bands = engine.equalizer.state.value.bands
        assertEquals(10, bands.size)
        assertEquals(listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000), bands.map { it.frequencyHz })
        assertEquals(EqualizerAvailability.Available, engine.equalizer.state.value.availability)
    }

    @Test
    fun `预设落到 native 均衡器`() {
        val engine = engine()
        engine.warmUp()
        engine.equalizer.setEnabled(true)
        engine.equalizer.selectPreset("pop")

        val state = engine.equalizer.state.value
        assertTrue(state.enabled)
        assertTrue(state.effective)
        assertEquals("pop", state.presetId)

        val amps = (engine.equalizer as VlcEqualizer).currentAmpsFromNative()
        assertEquals(10, amps.size)
        // 流行：低频 +4dB、超高频 +3dB，中频接近 0
        assertEquals(4f, amps[0], 0.1f)
        assertEquals(3f, amps.last(), 0.1f)
        assertTrue(abs(amps[4]) <= 1f)
        assertTrue(amps.any { abs(it) > 1f })
    }

    @Test
    fun `平直预设全部归零`() {
        val engine = engine()
        engine.warmUp()
        engine.equalizer.setEnabled(true)
        engine.equalizer.selectPreset("rock")
        engine.equalizer.resetFlat()

        assertEquals("flat", engine.equalizer.state.value.presetId)
        assertTrue((engine.equalizer as VlcEqualizer).currentAmpsFromNative().all { abs(it) < 0.01f })
    }

    @Test
    fun `拖某一段写回自定义曲线`() {
        val engine = engine()
        engine.warmUp()
        engine.equalizer.setEnabled(true)
        engine.equalizer.setBandLevel(0, 6f)

        val state = engine.equalizer.state.value
        assertEquals("custom", state.presetId)
        assertEquals(6f, state.bands.first().levelDb, 0.01f)
        assertEquals(6f, (engine.equalizer as VlcEqualizer).currentAmpsFromNative().first(), 0.1f)
    }

    @Test
    fun `关掉开关回到直通`() {
        val engine = engine()
        engine.warmUp()
        engine.equalizer.setEnabled(true)
        engine.equalizer.selectPreset("vocal")
        assertTrue((engine.equalizer as VlcEqualizer).currentAmpsFromNative().any { abs(it) > 0.5f })

        engine.equalizer.setEnabled(false)
        assertFalse(engine.equalizer.state.value.effective)
        assertTrue((engine.equalizer as VlcEqualizer).currentAmpsFromNative().all { abs(it) < 0.01f })
        // 关掉不等于丢掉设置：再打开还是人声
        engine.equalizer.setEnabled(true)
        assertEquals("vocal", engine.equalizer.state.value.presetId)
        assertTrue((engine.equalizer as VlcEqualizer).currentAmpsFromNative().any { abs(it) > 0.5f })
    }

    @Test
    fun `没有播放器时是 NoSession`() {
        val engine = engine()
        val state = engine.equalizer.state.value
        assertEquals(EqualizerAvailability.NoSession, state.availability)
        assertFalse(state.effective)
        // 预览仍然给 10 段，设置页不用等播放
        assertEquals(10, state.bands.size)
        assertEquals(PlaybackState.Idle, engine.snapshot.value.state)
    }
}
