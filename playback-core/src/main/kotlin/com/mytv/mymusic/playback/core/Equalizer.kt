package com.mytv.mymusic.playback.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.roundToInt

/** 一段的设置值（和 Android 的 `data.AppStore.EqBandSetting` 同名字段，续播 / 设置文件可以互读）。 */
data class EqBandSetting(val freqHz: Int = 0, val gainDb: Float = 0f)

/** 均衡器设置：开关 + 预设 id + 自定义曲线（presetId 为 "custom" 时后者生效）。 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val customBands: List<EqBandSetting> = emptyList(),
)

/** 一段的实时状态：频率、当前增益、可调范围。 */
data class EqualizerBand(
    val band: Int,
    val frequencyHz: Int,
    val levelDb: Float,
    val minDb: Float = EqualizerCurve.MIN_DB,
    val maxDb: Float = EqualizerCurve.MAX_DB,
)

enum class EqualizerAvailability { NoSession, Available, Unsupported }

data class EqualizerState(
    val availability: EqualizerAvailability = EqualizerAvailability.NoSession,
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val bands: List<EqualizerBand> = EqualizerCurve.previewBands(EqualizerSettings()),
    /** 开关打开且真的挂到了播放器上；界面据此显示"已生效"。 */
    val effective: Boolean = false,
    val outputLabel: String = "扬声器",
    val error: String = "",
)

/** 预设：曲线是 5 个控制点（和 Android 的 `EqPresets` 一样），落到 10 段上靠对数频率插值。 */
data class EqPreset(
    val id: String,
    val label: String,
    val curve: List<Pair<Int, Float>> = emptyList(),
)

object EqPresets {
    val flat = EqPreset("flat", "平直")
    val pop = EqPreset(
        "pop", "流行",
        listOf(60 to 4f, 230 to 2f, 910 to 0f, 3600 to 2f, 14000 to 3f),
    )
    val rock = EqPreset(
        "rock", "摇滚",
        listOf(60 to 5f, 230 to 2f, 910 to -1f, 3600 to 3f, 14000 to 4f),
    )
    val jazz = EqPreset(
        "jazz", "爵士",
        listOf(60 to 3f, 230 to 1f, 910 to 0f, 3600 to 2f, 14000 to 3f),
    )
    val vocal = EqPreset(
        "vocal", "人声",
        listOf(60 to -2f, 230 to -1f, 910 to 3f, 3600 to 2f, 14000 to 0f),
    )
    val all = listOf(flat, pop, rock, jazz, vocal)

    fun byId(id: String): EqPreset? = all.firstOrNull { it.id == id }
}

/**
 * 10 段均衡器的纯计算部分：预设 / 自定义曲线 → 每一段的 dB。
 *
 * 频点用 libVLC 的 10 段（[VLC_FREQUENCIES_HZ]），Android 的 5 点预设按对数频率插值落到这 10 段上，
 * 这样"流行 / 摇滚"在两端听起来一致，而不是低音区少 5 段。
 * 增益按 libVLC 的 `libvlc_audio_equalizer_set_amp_at_index` 约定：直接是 dB，夹在 ±20。
 */
object EqualizerCurve {
    const val MIN_DB = -20f
    const val MAX_DB = 20f
    const val BAND_COUNT = 10

    /**
     * libVLC 3.0 的 10 段中心频率（`libvlc_audio_equalizer_get_band_frequency` 实测值）：
     * 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz，即 ISO 倍频程。
     * 运行期还会用 [EqualizerControl.refreshBands] 覆盖成 native 报的频点，这里的值只用于预览曲线。
     */
    val VLC_FREQUENCIES_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    /** 用户拖动的步进：0.5 dB，和 Android 侧一致。 */
    private const val STEP_DB = 0.5f

    /** 对数频率插值：控制点之间按 log10(f) 线性过渡，超出两端取端点值。 */
    fun interpolate(points: List<Pair<Int, Float>>, freqHz: Int): Float {
        if (points.isEmpty()) return 0f
        val sorted = points.sortedBy { it.first }
        if (freqHz <= sorted.first().first) return sorted.first().second
        if (freqHz >= sorted.last().first) return sorted.last().second
        for (i in 0 until sorted.lastIndex) {
            val (f1, v1) = sorted[i]
            val (f2, v2) = sorted[i + 1]
            if (freqHz in f1..f2) {
                val ratio = if (f2 == f1) {
                    0f
                } else {
                    ((log10(freqHz.toDouble()) - log10(f1.toDouble())) /
                        (log10(f2.toDouble()) - log10(f1.toDouble()))).toFloat()
                }
                return v1 + (v2 - v1) * ratio
            }
        }
        return 0f
    }

    /** 当前设置落到 [frequenciesHz] 上每一段的 dB。 */
    fun amplitudes(
        settings: EqualizerSettings,
        frequenciesHz: List<Int> = VLC_FREQUENCIES_HZ,
    ): List<Float> {
        val curve = curvePoints(settings)
        return frequenciesHz.map { clamp(interpolate(curve, it)) }
    }

    fun bands(
        settings: EqualizerSettings,
        frequenciesHz: List<Int> = VLC_FREQUENCIES_HZ,
    ): List<EqualizerBand> {
        val curve = curvePoints(settings)
        return frequenciesHz.mapIndexed { index, freq ->
            EqualizerBand(band = index, frequencyHz = freq, levelDb = clamp(interpolate(curve, freq)))
        }
    }

    /** 自定义曲线的控制点；预设则取预设曲线，未知 id（含 "custom" 但没有曲线）走平直。 */
    fun curvePoints(settings: EqualizerSettings): List<Pair<Int, Float>> =
        if (settings.presetId == "custom" && settings.customBands.isNotEmpty()) {
            settings.customBands.sortedBy { it.freqHz }.map { it.freqHz to it.gainDb }
        } else {
            EqPresets.byId(settings.presetId)?.curve?.takeIf { it.isNotEmpty() }
                ?: listOf(60 to 0f, 230 to 0f, 910 to 0f, 3_600 to 0f, 14_000 to 0f)
        }

    /** 按 0.5 dB 步进量化并夹紧：界面拖动、写回设置都用同一个函数，避免 0.4999 这种脏值。 */
    fun clamp(levelDb: Float): Float {
        if (!levelDb.isFinite()) return 0f
        val quantized = (levelDb / STEP_DB).roundToInt() * STEP_DB
        return quantized.coerceIn(MIN_DB, MAX_DB)
    }

    /** 没有播放器时的预览：10 段按 VLC 频点排好，给设置页画曲线。 */
    fun previewBands(settings: EqualizerSettings): List<EqualizerBand> = bands(settings)

    /** 把一段的拖动结果写回自定义曲线：同一频点覆盖，其它点保持。 */
    fun withBand(settings: EqualizerSettings, frequencyHz: Int, levelDb: Float): EqualizerSettings {
        val current = if (settings.presetId == "custom" && settings.customBands.isNotEmpty()) {
            settings.customBands
        } else {
            // 从预设切到自定义：先把预设曲线固化成控制点，再改这一段
            curvePoints(settings).map { EqBandSetting(it.first, it.second) }
        }
        val updated = current
            .filterNot { it.freqHz == frequencyHz }
            .plus(EqBandSetting(frequencyHz, clamp(levelDb)))
            .sortedBy { it.freqHz }
        return settings.copy(presetId = "custom", customBands = updated)
    }
}

/**
 * 10 段均衡器契约。Android 侧接 `android.media.audiofx.Equalizer`（`EqualizerController`），
 * 桌面侧接 `uk.co.caprica.vlcj.player.base.Equalizer`（`VlcEqualizer`）。
 *
 * 段数 / 频点由后端报告（[refreshBands]），因为不同平台的可调段不一样：
 * Android 是 5 段、libVLC 是 10 段，界面按 [EqualizerState.bands] 画，不写死段数。
 */
interface EqualizerControl {
    val state: StateFlow<EqualizerState>

    fun setEnabled(enabled: Boolean)

    /** 选预设；"custom" 会先把当前曲线存成自定义。 */
    fun selectPreset(presetId: String)

    /** 拖某一段；[bandIndex] 是 [EqualizerState.bands] 里的下标。 */
    fun setBandLevel(bandIndex: Int, levelDb: Float)

    fun resetFlat()

    fun saveAsCustom()

    /** 后端报告段数 / 频点（VLC 创建均衡器后、Android 拿到会话后各调一次）。 */
    fun refreshBands(frequenciesHz: List<Int>)
}
