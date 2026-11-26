package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 响度归一化：各平台字段 → dB，跨平台夹紧与峰值保护。 */
class VolumeNormalizationTest {
    private fun song(source: String, extra: Map<String, String>) = Song(source = source, extra = extra)

    @Test
    fun `网易直接给增益就照用`() {
        val value = VolumeNormalization.gainDb(song("netease", mapOf("netease_gain" to "-6.5", "netease_peak" to "0.9")))
        assertEquals(-6.5f, value, 1e-6f)
    }

    @Test
    fun `网易有封闭增益时优先它`() {
        val value = VolumeNormalization.gainDb(
            song(
                "netease",
                mapOf(
                    "netease_gain" to "-1.0",
                    "netease_closed_gain" to "-8.0",
                    "netease_closed_peak" to "0.5",
                ),
            ),
        )
        assertEquals(-8.0f, value, 1e-6f)
    }

    @Test
    fun `峰值到顶就不给正增益`() {
        // peak 0.98 已经是上限：还能提 0 dB，增益被压回 0
        val value = VolumeNormalization.gainDb(song("netease", mapOf("netease_gain" to "6", "netease_peak" to "0.98")))
        assertEquals(0f, value, 1e-6f)
    }

    @Test
    fun `峰值留有余量时按峰值压增益`() {
        // 20 * log10(0.98 / 0.5) ≈ 5.844 dB，小于 6，取它
        val value = VolumeNormalization.gainDb(song("netease", mapOf("netease_gain" to "6", "netease_peak" to "0.5")))
        assertEquals(5.844f, value, 2e-3f)
    }

    @Test
    fun `酷狗由响度与增益合成`() {
        val value = VolumeNormalization.gainDb(
            song("kugou", mapOf("kugou_volume" to "-10", "kugou_volume_gain" to "2")),
        )
        assertEquals(-2f, value, 1e-6f)
    }

    @Test
    fun `QQ 直接给增益`() {
        assertEquals(3.5f, VolumeNormalization.gainDb(song("qq", mapOf("qq_gain" to "3.5"))), 1e-6f)
    }

    @Test
    fun `千千由平均响度算峰值`() {
        // gain = -14 - (-20) = 6 dB；peak = 10^(-0.5/20) ≈ 0.944
        val value = VolumeNormalization.gainDb(
            song("qianqian", mapOf("qianqian_mean_volume" to "-20", "qianqian_max_volume" to "-0.5")),
        )
        assertEquals(0.325f, value, 5e-3f)
    }

    @Test
    fun `Subsonic 用 replaygain`() {
        val value = VolumeNormalization.gainDb(song("subsonic:abc", mapOf("replay_gain" to "-3")))
        assertEquals(-3f, value, 1e-6f)
    }

    @Test
    fun `未知音源与空值都是 0`() {
        assertEquals(0f, VolumeNormalization.gainDb(song("unknown", mapOf("qq_gain" to "3"))), 1e-6f)
        assertEquals(0f, VolumeNormalization.gainDb(null), 1e-6f)
        assertEquals(0f, VolumeNormalization.gainDb(song("qq", mapOf("qq_gain" to "not-a-number"))), 1e-6f)
    }

    @Test
    fun `增益夹在 -20 到 +6 之间`() {
        assertEquals(6f, VolumeNormalization.gainDb(song("qq", mapOf("qq_gain" to "99"))), 1e-6f)
        assertEquals(-20f, VolumeNormalization.gainDb(song("qq", mapOf("qq_gain" to "-99"))), 1e-6f)
    }

    @Test
    fun `有没有响度元数据`() {
        assertTrue(VolumeNormalization.hasMetadata(song("netease", mapOf("netease_gain" to "1"))))
        assertTrue(VolumeNormalization.hasMetadata(song("qq", mapOf("qq_peak" to "0.5"))))
        assertTrue(VolumeNormalization.hasMetadata(song("subsonic:x", mapOf("replay_gain" to "-2"))))
        // 全 0 的酷狗字段当没有数据
        assertFalse(
            VolumeNormalization.hasMetadata(
                song("kugou", mapOf("kugou_volume" to "0", "kugou_volume_gain" to "0")),
            ),
        )
        assertFalse(VolumeNormalization.hasMetadata(song("unknown", mapOf("qq_gain" to "1"))))
        assertFalse(VolumeNormalization.hasMetadata(null))
    }

    @Test
    fun `合成到输出音量`() {
        // 0 dB 不动音量；+6 dB ≈ 2 倍但要夹到 1；-6.02 dB ≈ 0.5 倍
        assertEquals(1f, VolumeState(volume = 1f).effective, 1e-6f)
        assertEquals(1f, VolumeState(volume = 1f, normalizationDb = 6f).effective, 1e-6f)
        assertEquals(0.5f, VolumeState(volume = 1f, normalizationDb = -6.0206f).effective, 1e-4f)
        assertEquals(0f, VolumeState(volume = 0.8f, muted = true).effective, 1e-6f)
        assertEquals(0.4f, VolumeState(volume = 0.4f).effective, 1e-6f)
    }
}
