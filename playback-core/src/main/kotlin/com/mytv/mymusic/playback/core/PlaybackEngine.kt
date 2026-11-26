package com.mytv.mymusic.playback.core

import kotlinx.coroutines.flow.StateFlow

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
 *  - 播放失败通过 [PlaybackSnapshot.errorText] 暴露，不抛异常给 UI。
 */
interface PlaybackEngine {
    /** 当前状态快照；初值是 idle。 */
    val snapshot: StateFlow<PlaybackSnapshot>

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
)

/** 引擎对外暴露的全部播放状态。字段顺序即 UI 读取顺序，避免过多细粒度 StateFlow。 */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0f..1f；Android 侧由 AudioManager 音量键改的是系统音量，这里只反映引擎音量。 */
    val volume: Float = 1f,
    val trackId: String = "",
    val errorText: String = "",
)
