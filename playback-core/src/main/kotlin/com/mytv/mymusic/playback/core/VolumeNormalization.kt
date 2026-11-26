package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.subsonic.SUBSONIC_PROVIDER_PREFIX
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 用平台返回的响度元数据算增益，不采样音频。
 * 网易云 / 酷狗 / QQ / 千千各自把原始字段放进 `Song.extra`，这里统一换算成 dB。
 *
 * 从 Android 的 `playback/VolumeNormalization.kt` 原样搬来（只依赖 `Song.extra` 与 `Song.source`，
 * 没有任何 Android 类型）：桌面侧取流时 `:core` 已经把同样的字段带进 Song，因此两边给同一首歌算出的
 * 增益完全一致。合成进输出音量见 [VolumeState.effective]。
 */
object VolumeNormalization {
    const val TARGET_LUFS = -14f
    const val MAX_BOOST_DB = 6f
    const val MAX_CUT_DB = -20f
    const val PEAK_LIMIT = 0.98f

    fun gainDb(song: Song?): Float {
        val extra = song?.extra ?: return 0f
        return when (song.source) {
            "netease" -> {
                val closedGain = extra.float("netease_closed_gain")
                val closed = closedGain != null && abs(closedGain) > 0.05f
                val gain = if (closed) closedGain else extra.float("netease_gain")
                val peak = if (closed) extra.float("netease_closed_peak") else extra.float("netease_peak")
                effective(gain, peak)
            }
            "kugou" -> {
                val volume = extra.float("kugou_volume") ?: 0f
                val boost = extra.float("kugou_volume_gain") ?: 0f
                val gain = if (volume != 0f) TARGET_LUFS - volume + boost else boost
                effective(gain, extra.float("kugou_volume_peak"))
            }
            "qq" -> effective(extra.float("qq_gain"), extra.float("qq_peak"))
            "qianqian" -> {
                val mean = extra.float("qianqian_mean_volume") ?: 0f
                val gain = if (mean != 0f) {
                    TARGET_LUFS - mean
                } else {
                    extra.float("qianqian_replay_gain")
                }
                val maxDb = extra.float("qianqian_max_volume")
                val peak = if (mean != 0f && maxDb != null) 10f.pow(maxDb / 20f) else null
                effective(gain, peak)
            }
            else -> if (song.source.startsWith(SUBSONIC_PROVIDER_PREFIX)) {
                effective(extra.float("replay_gain"), extra.float("replay_peak"))
            } else {
                0f
            }
        }
    }

    /** 是否有平台响度数据；全为 0 的千千/酷狗字段视为没有数据。 */
    fun hasMetadata(song: Song?): Boolean {
        val extra = song?.extra ?: return false
        return when (song.source) {
            "netease" -> extra.containsKey("netease_gain") || extra.containsKey("netease_peak")
            "kugou" -> nonZero(extra, "kugou_volume", "kugou_volume_gain", "kugou_volume_peak")
            "qq" -> extra.containsKey("qq_gain") || extra.containsKey("qq_peak")
            "qianqian" -> nonZero(
                extra,
                "qianqian_mean_volume",
                "qianqian_replay_gain",
                "qianqian_max_volume",
            )
            else -> song.source.startsWith(SUBSONIC_PROVIDER_PREFIX) &&
                (extra.containsKey("replay_gain") || extra.containsKey("replay_peak"))
        }
    }

    private fun nonZero(extra: Map<String, String>, vararg keys: String): Boolean =
        keys.any { (extra[it]?.toFloatOrNull() ?: 0f) != 0f }

    /**
     * 把平台增益夹进 [-20, +6] dB，再按峰值上限压住正增益：
     * 峰值 0.98 满刻度时最多还能提多少，就不让增益超过它，避免整首削波。
     */
    internal fun effective(gain: Float?, peak: Float?): Float {
        if (gain == null || !gain.isFinite()) return 0f
        var value = gain.coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        if (value > 0f && peak != null && peak.isFinite() && peak > 0f) {
            val allowed = 20f * log10(PEAK_LIMIT / peak)
            if (allowed < value) value = allowed.coerceAtLeast(0f)
        }
        return if (abs(value) < 0.05f) 0f else value
    }

    private fun Map<String, String>.float(key: String): Float? = this[key]?.toFloatOrNull()
}
