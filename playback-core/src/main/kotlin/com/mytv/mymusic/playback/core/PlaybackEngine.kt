package com.mytv.mymusic.playback.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * 播放器无关的播放引擎契约。
 *
 * 现状对照（Android，见 `app/src/main/java/com/mytv/mymusic/playback/PlaybackService.kt`）：
 * 引擎是 `ExoPlayer` + `MediaSessionService`，播放列表状态在 `QueueStore`，交叉淡入在
 * `CrossfadeController`。桌面侧把同一套状态机接到 libVLC，UI 只用这层接口，
 * 因此 ui 目录下 164 个文件重写时不必关心底下是 ExoPlayer 还是 libVLC。
 *
 * 约定：
 *  - 所有方法都在主线程之外可调用，实现自己切线程；
 *  - [snapshot] 是 UI 的唯一数据源（Compose 直接 collectAsState）；
 *  - 播放失败通过 [PlaybackSnapshot.errorText] 暴露，不抛异常给 UI；
 *  - 六个能力面（音量 / 队列 / 均衡器 / 交叉淡化 / 定时 / 缓存）各自是一个子契约，
 *    桌面实现可以拆开写（`VlcEqualizer`、`VlcCrossfade`…），引擎只负责把它们串起来。
 */
interface PlaybackEngine {
    /** 当前状态快照；初值是 idle。 */
    val snapshot: StateFlow<PlaybackSnapshot>

    /** 音量与响度归一化的合成结果；[PlaybackSnapshot.volume] 只反映用户音量。 */
    val volumeState: StateFlow<VolumeState>

    /** 播放队列（增删改 + 随机 / 循环）。 */
    val queue: PlaybackQueue

    /** 10 段均衡器。 */
    val equalizer: EqualizerControl

    /** 自动接歌（交叉淡化 / 智能过渡）。 */
    val crossfade: CrossfadeControl

    /** 定时关闭。 */
    val sleepTimer: SleepTimer

    /** 缓存目录与 VLC 缓存参数。 */
    val cache: PlaybackCacheConfig

    /** 装载单个音源（不自动播放）。 */
    fun prepare(source: PlaybackSource)

    fun play()

    fun pause()

    /** 跳转到绝对位置（毫秒）。 */
    fun seekTo(positionMs: Long)

    /** 设置音量，0f..1f。 */
    fun setVolume(volume: Float)

    /** 释放 native 资源；之后该实例不可再用。 */
    fun release()
}

/** 播放器的粗粒度状态；UI 只按这几个值切界面，不读 media3 / vlcj 的枚举。 */
enum class PlaybackState {
    Idle,
    Buffering,
    Playing,
    Paused,
    Ended,
    Error,
}

/**
 * 交给引擎的一路音频。
 *
 * [headers] 是给 CDN 防盗链用的——`:core` 里各 Provider 取流时已经算好 UA / Referer，
 * 引擎不该再猜。Android 侧等价物是 ExoPlayer 的 `DefaultHttpDataSource.Factory` 默认请求头。
 */
data class PlaybackSource(
    val url: String,
    val ext: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** 播放会话内的曲目标识，用于切换音源时对齐位置。 */
    val trackId: String = "",
    /** 标题只用于日志与交叉淡化的报告，不参与播放决策。 */
    val title: String = "",
    /** 直播流（云听电台）：没有时长、不参与"播完切下一首"。 */
    val live: Boolean = false,
)

/** 引擎对外暴露的全部播放状态。字段顺序即 UI 读取顺序，避免过多细粒度 StateFlow。 */
data class PlaybackSnapshot(
    val state: PlaybackState = PlaybackState.Idle,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0f..1f；Android 侧由 AudioManager 音量键改的是系统音量，这里只反映引擎音量。 */
    val volume: Float = 1f,
    val trackId: String = "",
    val trackTitle: String = "",
    /** 在 [PlaybackQueue] 里的下标，-1 表示还没有队列。 */
    val queueIndex: Int = -1,
    val errorText: String = "",
)

/**
 * 音量状态：用户音量 × 响度归一化增益。
 *
 * 归一化（见 [VolumeNormalization]）给出的是 dB，直接乘进输出音量会失真，
 * 所以这里按线性增益合成：`effective = volume * 10^(normalizationDb / 20)`，再夹到 0..1。
 * 静音只影响 [effective]，不动 [volume]——取消静音能回到用户原来的档位。
 */
data class VolumeState(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val normalizationDb: Float = 0f,
) {
    val effective: Float
        get() = if (muted) 0f else (volume * linearGain(normalizationDb)).coerceIn(0f, 1f)

    companion object {
        /** dB → 线性增益；0 dB 是 1.0，+6 dB ≈ 2.0，-20 dB ≈ 0.1。 */
        fun linearGain(db: Float): Float = if (db == 0f) 1f else 10.0.pow((db / 20f).toDouble()).toFloat()
    }
}
