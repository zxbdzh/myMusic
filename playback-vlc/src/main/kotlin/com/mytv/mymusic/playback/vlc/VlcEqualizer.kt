package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.EqualizerAvailability
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.EqualizerCurve
import com.mytv.mymusic.playback.core.EqualizerSettings
import com.mytv.mymusic.playback.core.EqualizerState
import com.mytv.mymusic.playback.core.EqBandSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * libVLC 的 10 段均衡器（`libvlc_audio_equalizer_*`）。
 *
 * 和 Android 侧 `EqualizerController` 的区别只有"挂在哪"：Android 挂在音频会话 id 上，
 * libVLC 挂在媒体播放器上（`mediaPlayer.audio().setEqualizer`）。曲线换算、预设、
 * 0.5 dB 步进、±20 dB 夹紧全都复用 [EqualizerCurve]，两端听感一致。
 *
 * 关掉开关时不摘均衡器，而是把 10 段全部置 0 dB：0 dB 的均衡器就是直通，
 * 摘掉再挂上反而会让 libVLC 重建音频滤镜、出现一小段静音。
 */
class VlcEqualizer(private val factory: MediaPlayerFactory) : EqualizerControl {
    private val settings = MutableStateFlow(EqualizerSettings())
    private val _state = MutableStateFlow(EqualizerState())
    override val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var equalizer: Equalizer? = null

    /** 段数与频点由 libVLC 报告（默认 10 段），界面按它画推子。 */
    private var frequencies: List<Int> = EqualizerCurve.VLC_FREQUENCIES_HZ

    /** 换播放器（交叉淡化换主之后）时重新挂一次；已经在放的曲目不用重载。 */
    fun attach(target: MediaPlayer) {
        player = target
        equalizer = null
        // 先从 libVLC 问一次真实频点：不同版本/编译选项的段数可能不同
        runCatching {
            factory.equalizer().bands().map { it.toInt() }.takeIf { it.size >= 2 }?.let { frequencies = it }
        }
        publish()
        applyTo(target)
    }

    fun detach() {
        player = null
        equalizer = null
        _state.value = EqualizerState(availability = EqualizerAvailability.NoSession, bands = previewBands())
    }

    override fun refreshBands(frequenciesHz: List<Int>) {
        if (frequenciesHz.size < 2) return
        frequencies = frequenciesHz
        publish()
    }

    override fun setEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(enabled = enabled)
        publish()
        apply()
    }

    override fun selectPreset(presetId: String) {
        if (presetId == "custom" && settings.value.customBands.isEmpty()) {
            saveAsCustom()
            return
        }
        settings.value = settings.value.copy(presetId = presetId)
        publish()
        apply()
    }

    override fun setBandLevel(bandIndex: Int, levelDb: Float) {
        val frequency = frequencies.getOrNull(bandIndex) ?: return
        settings.value = EqualizerCurve.withBand(settings.value, frequency, levelDb)
        publish()
        apply()
    }

    override fun resetFlat() {
        settings.value = settings.value.copy(presetId = "flat", customBands = emptyList())
        publish()
        apply()
    }

    override fun saveAsCustom() {
        val bands = EqualizerCurve.bands(settings.value, frequencies)
        settings.value = settings.value.copy(
            presetId = "custom",
            customBands = bands.map { EqBandSetting(it.frequencyHz, it.levelDb) },
        )
        publish()
        apply()
    }

    private fun previewBands() = EqualizerCurve.bands(settings.value, frequencies)

    /** 直接从 libVLC 的均衡器读回 10 段 dB——验收"预设真的落到 native 上"看这里。 */
    fun currentAmpsFromNative(): List<Float> {
        val eq = equalizer ?: return List(frequencies.size) { 0f }
        return (0 until frequencies.size).map { index -> runCatching { eq.amp(index) }.getOrDefault(0f) }
    }

    private fun publish(error: String = "") {
        val current = settings.value
        _state.value = EqualizerState(
            availability = if (player != null) EqualizerAvailability.Available else EqualizerAvailability.NoSession,
            enabled = current.enabled,
            presetId = current.presetId,
            bands = previewBands(),
            effective = current.enabled && player != null,
            outputLabel = outputLabel(),
            error = error,
        )
    }

    private fun outputLabel(): String =
        runCatching { player?.audio()?.outputDevice() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "默认输出"

    private fun apply() {
        player?.let(::applyTo)
    }

    /**
     * 把当前曲线挂到任意一路播放器上。交叉淡化预载的那一路也要挂同一份曲线，
     * 否则淡入的一半是没均衡的声音，听感上像"下一首突然变亮"。
     */
    fun applyTo(target: MediaPlayer) {
        try {
            val eq = equalizer ?: factory.equalizer().newEqualizer().also {
                it.setPreamp(0f)
                equalizer = it
            }
            // 关掉开关等于全 0 dB（直通），不摘均衡器
            val amps = if (settings.value.enabled) {
                EqualizerCurve.amplitudes(settings.value, frequencies)
            } else {
                List(frequencies.size) { 0f }
            }
            amps.forEachIndexed { index, db -> eq.setAmp(index, db) }
            target.audio().setEqualizer(eq)
            if (_state.value.error.isNotEmpty()) publish()
        } catch (error: Throwable) {
            _state.value = _state.value.copy(
                availability = EqualizerAvailability.Unsupported,
                effective = false,
                error = error.message ?: "均衡器初始化失败",
            )
        }
    }
}
