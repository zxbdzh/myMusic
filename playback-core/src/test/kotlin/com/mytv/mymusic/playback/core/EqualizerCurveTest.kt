package com.mytv.mymusic.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 均衡器：预设曲线 → 10 段 dB，0.5 dB 步进与 ±20 dB 夹紧，自定义曲线写回。 */
class EqualizerCurveTest {
    @Test
    fun `平直预设全零`() {
        val amplitudes = EqualizerCurve.amplitudes(EqualizerSettings(presetId = "flat"))
        assertEquals(10, amplitudes.size)
        assertTrue(amplitudes.all { it == 0f })
    }

    @Test
    fun `流行预设落到 10 段`() {
        val amplitudes = EqualizerCurve.amplitudes(EqualizerSettings(presetId = "pop"))
        assertEquals(EqualizerCurve.BAND_COUNT, amplitudes.size)
        // 60Hz 就是控制点本身；16kHz 超出最后一个控制点（14kHz），取端点 3dB
        assertEquals(4f, amplitudes[0], 1e-6f)
        assertEquals(3f, amplitudes.last(), 1e-6f)
        // 中频基本不动
        assertTrue(kotlin.math.abs(amplitudes[4]) <= 1f)
    }

    @Test
    fun `未知预设按平直处理`() {
        val amplitudes = EqualizerCurve.amplitudes(EqualizerSettings(presetId = "nope"))
        assertTrue(amplitudes.all { it == 0f })
    }

    @Test
    fun `自定义曲线优先`() {
        val settings = EqualizerSettings(
            presetId = "custom",
            customBands = listOf(EqBandSetting(60, -6f), EqBandSetting(16_000, 6f)),
        )
        val amplitudes = EqualizerCurve.amplitudes(settings)
        assertEquals(-6f, amplitudes.first(), 1e-6f)
        assertEquals(6f, amplitudes.last(), 1e-6f)
        // 中间按对数频率插值，不会突变
        assertTrue(amplitudes[5] > -6f && amplitudes[5] < 6f)
    }

    @Test
    fun `按 0_5dB 步进量化并夹紧`() {
        assertEquals(3.5f, EqualizerCurve.clamp(3.26f), 1e-6f)
        assertEquals(0f, EqualizerCurve.clamp(0.24f), 1e-6f)
        assertEquals(-20f, EqualizerCurve.clamp(-20.7f), 1e-6f)
        assertEquals(20f, EqualizerCurve.clamp(99f), 1e-6f)
        assertEquals(0f, EqualizerCurve.clamp(Float.NaN), 1e-6f)
    }

    @Test
    fun `拖一段时先把预设固化成自定义曲线`() {
        val settings = EqualizerSettings(presetId = "rock")
        val updated = EqualizerCurve.withBand(settings, frequencyHz = 1_000, levelDb = 5f)

        assertEquals("custom", updated.presetId)
        // 5 个预设控制点 + 新拖的点
        assertEquals(6, updated.customBands.size)
        assertEquals(5f, updated.customBands.first { it.freqHz == 1_000 }.gainDb, 1e-6f)
        // 预设原有的低频控制点还在
        assertEquals(5f, updated.customBands.first { it.freqHz == 60 }.gainDb, 1e-6f)
    }

    @Test
    fun `同一段再拖是覆盖不是追加`() {
        val first = EqualizerCurve.withBand(EqualizerSettings(presetId = "flat"), 1_000, 3f)
        val second = EqualizerCurve.withBand(first, 1_000, -4f)
        assertEquals(1, second.customBands.count { it.freqHz == 1_000 })
        assertEquals(-4f, second.customBands.first { it.freqHz == 1_000 }.gainDb, 1e-6f)
    }

    @Test
    fun `预览段数与频点固定`() {
        val bands = EqualizerCurve.previewBands(EqualizerSettings())
        assertEquals(EqualizerCurve.VLC_FREQUENCIES_HZ, bands.map { it.frequencyHz })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), bands.map { it.band })
        assertTrue(bands.all { it.minDb == -20f && it.maxDb == 20f })
    }
}
