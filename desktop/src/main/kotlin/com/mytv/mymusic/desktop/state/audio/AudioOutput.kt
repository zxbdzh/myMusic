package com.mytv.mymusic.desktop.state.audio

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.desktop.state.AudioStream
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放后端回传给桌面 UI 的状态快照。
 *
 * 与 `:playback-core` 的 `PlaybackSnapshot` 对齐，额外由实现携带 [AudioOutput.ready]、
 * [AudioOutput.label] 与 [AudioOutput.detail]，明确区分「真实引擎就绪」与「原生库缺失」。
 */
@Immutable
data class AudioSnapshot(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 0.8f,
    val error: String = "",
)

/**
 * 桌面 UI 侧唯一的播放后端契约。
 *
 * 生产环境直接对接 `:playback-vlc` 的 [VlcAudioOutput] 并暴露均衡器 / 交叉淡化 / 定时关闭；
 * 缺库时返回 [UnavailableAudioOutput] 明确报错，绝不在生产界面里静默假播。
 */
interface AudioOutput {
    /** 显示用的后端名，如 "libVLC" / "libVLC 未就绪" / "模拟输出（测试）"。 */
    val label: String

    /** 一句话说明（库版本、加载目录或缺失原因）。 */
    val detail: String

    /** 是否已接入真实可出声的原生音频引擎。 */
    val ready: Boolean

    val snapshot: StateFlow<AudioSnapshot>

    /** 10 段均衡器控制；后端未就绪或测试模拟态为 null。 */
    val equalizer: EqualizerControl? get() = null

    /** 自动接歌（交叉淡化 / 智能过渡）控制；后端未就绪或测试模拟态为 null。 */
    val crossfade: CrossfadeControl? get() = null

    /** 定时关闭控制；后端未就绪或测试模拟态为 null。 */
    val sleepTimer: SleepTimer? get() = null

    fun load(track: Track, stream: AudioStream)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun release()
}

/**
 * 后端工厂：
 * - [create] 为运行态入口：直连真实 [VlcAudioOutput]，缺库时返回 [UnavailableAudioOutput] 明示错误，绝不假播；
 * - [simulated] 仅供无音频设备的单元测试与离屏无声截图显式调用。
 */
object AudioOutputs {
    fun create(scope: CoroutineScope): AudioOutput {
        val result = VlcAudioOutput.create(scope)
        return result.getOrElse { error ->
            UnavailableAudioOutput(
                reason = error.message ?: "未找到 libVLC 运行库，请确认随包 vlc 目录完整或配置 VLC_HOME",
            )
        }
    }

    /** 仅限单元测试与离屏 `--screenshot` 使用，避免后台静默发声干扰桌面。 */
    fun simulated(scope: CoroutineScope, tickMs: Long = 200L): AudioOutput =
        SimulatedAudioOutput(scope, tickMs)
}

/**
 * 原生音频运行库缺失时的显式不可用占位。
 *
 * 约束（ADR-0001）：禁止在生产界面里靠定时器假走进度条；任何装载或起播动作都必须
 * 把原因写入 [AudioSnapshot.error]，并保持 `playing = false`、`positionMs = 0`。
 */
class UnavailableAudioOutput(
    private val reason: String,
) : AudioOutput {

    override val label: String = "libVLC 未就绪"
    override val detail: String = reason
    override val ready: Boolean = false

    private val _snapshot = MutableStateFlow(AudioSnapshot(error = ""))
    override val snapshot: StateFlow<AudioSnapshot> = _snapshot.asStateFlow()

    override fun load(track: Track, stream: AudioStream) {
        _snapshot.value = _snapshot.value.copy(
            playing = false,
            buffering = false,
            positionMs = 0L,
            durationMs = track.durationMs,
            error = "无法播放《${track.title}》：$reason",
        )
    }

    override fun play() {
        _snapshot.value = _snapshot.value.copy(
            playing = false,
            buffering = false,
            error = "音频后端未就绪：$reason",
        )
    }

    override fun pause() {
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun seekTo(positionMs: Long) {
        // 后端不可用时拒绝推进进度，避免界面出现假进度
    }

    override fun setVolume(volume: Float) {
        _snapshot.value = _snapshot.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    override fun release() = Unit
}

/**
 * 模拟输出（仅限单元测试与离屏无声截图）。
 *
 * 按定时器推进进度，让无声卡的自动化测试和离屏截图可验证播放态 UI；
 * 不再被 [AudioOutputs.create] 用作生产环境回退。
 */
class SimulatedAudioOutput(
    private val scope: CoroutineScope,
    private val tickMs: Long = 200L,
) : AudioOutput {

    override val label: String = "模拟输出（测试）"
    override val detail: String = "仅用于单测与离屏截图：定时器推进进度，无真实音频输出"
    override val ready: Boolean = false

    private val _snapshot = MutableStateFlow(AudioSnapshot())
    override val snapshot: StateFlow<AudioSnapshot> = _snapshot.asStateFlow()

    private var ticker: Job? = null
    private var durationMs: Long = 0L

    override fun load(track: Track, stream: AudioStream) {
        stopTicker()
        durationMs = track.durationMs
        _snapshot.value = AudioSnapshot(
            playing = false,
            positionMs = 0L,
            durationMs = durationMs,
            volume = _snapshot.value.volume,
        )
    }

    override fun play() {
        if (durationMs <= 0L) return
        _snapshot.value = _snapshot.value.copy(playing = true)
        startTicker()
    }

    override fun pause() {
        stopTicker()
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        _snapshot.value = _snapshot.value.copy(positionMs = clamped)
    }

    override fun setVolume(volume: Float) {
        _snapshot.value = _snapshot.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    override fun release() {
        stopTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(tickMs)
                val current = _snapshot.value
                if (!current.playing) return@launch
                val next = current.positionMs + tickMs
                if (next >= durationMs) {
                    _snapshot.value = current.copy(positionMs = durationMs, playing = false)
                    return@launch
                }
                _snapshot.value = current.copy(positionMs = next)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
