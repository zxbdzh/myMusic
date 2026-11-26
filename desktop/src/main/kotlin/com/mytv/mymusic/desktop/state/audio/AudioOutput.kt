package com.mytv.mymusic.desktop.state.audio

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.desktop.state.AudioStream
import com.mytv.mymusic.desktop.state.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放后端把状态回给 UI 的那一份快照。
 *
 * 与 `:playback-core` 的 `PlaybackSnapshot` 字段一一对应，额外带 [label] / [detail]：
 * 桌面骨架必须能说清"现在到底是谁在出声"——libVLC 没装、t3 还没接上时，
 * 界面不能假装在放歌（Android 端没有这个问题，它的播放器是进程内必然存在的）。
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
 * UI 侧唯一的播放后端契约。
 *
 * 为什么要再包一层而不是直接用 `PlaybackEngine`：
 *  1. t3 正在重写 `:playback-core` / `:playback-vlc`，接口签名可能变；
 *     把编译期耦合收在 [VlcAudioOutput] 一个文件里，改动面最小；
 *  2. 桌面还可能没有 libVLC（本机就没有），需要一条模拟输出的退路，
 *     `PlaybackEngine` 的契约里没有"我其实没在播"这个概念。
 */
interface AudioOutput {
    /** 显示用的后端名，如 "libVLC" / "模拟输出"。 */
    val label: String

    /** 一句话说明（版本、缺什么、为什么降级）。 */
    val detail: String

    val snapshot: StateFlow<AudioSnapshot>

    fun load(track: Track, stream: AudioStream)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun release()
}

/** 后端选择：优先 libVLC，拿不到就退回模拟输出（进度照走，只是没有声音）。 */
object AudioOutputs {
    fun create(scope: CoroutineScope): AudioOutput {
        val vlc = VlcAudioOutput.create(scope)
        return vlc ?: SimulatedAudioOutput(scope)
    }
}

/**
 * 模拟输出。
 *
 * 没有音频设备 / 没有 libVLC 时用：按真实时间推进进度、到点停下，
 * 让"播放/暂停/seek/下一首/歌词滚动/进度条"这些交互全部可验证。
 * 它绝不假装有声音——[detail] 在界面上一直显示为"模拟"。
 */
class SimulatedAudioOutput(
    private val scope: CoroutineScope,
    private val tickMs: Long = 200L,
) : AudioOutput {

    override val label: String = "模拟输出"
    override val detail: String = "未检测到 libVLC：进度由定时器推进，t3 接上后自动切到真实播放"

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
                    // 播到结尾：停在末尾并暂停，交给队列层决定是否续播（骨架期不自动切歌）
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
