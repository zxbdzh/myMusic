package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.PlaybackEngine
import com.mytv.mymusic.playback.core.PlaybackSnapshot
import com.mytv.mymusic.playback.core.PlaybackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/**
 * libVLC 版播放引擎（骨架，行为待 :desktop 阶段实现）。
 *
 * 之所以先落这个类：验证 vlcj / JNA 在 JDK 17 目标、Windows x64 下能被编译期锁定，
 * 同时把"引擎只在真正要播的时候才碰 native 库"这条约束写死在代码里——
 * `MediaPlayerFactory` 构造会加载 libvlc.dll，放构造期会让"打开设置页"也要求装 VLC。
 *
 * 实现要点（后续任务）：
 *  1. `factory.mediaPlayers().newEmbeddedMediaPlayer()` 拿播放器，`VideoSurface` 不需要，纯音频；
 *  2. 事件用 `MediaPlayerEventAdapter`（Playing / Paused / Finished / EncounteredError /
 *     TimeChanged / LengthChanged）回填 [snapshot]；
 *  3. 音源切换沿用队列层的 crossfade 交接（见 :playback-core 的 Crossfade* 搬运计划）；
 *  4. 均衡器走 `mediaPlayer.audio().equalizer()`，与 Android 的 `EqualizerController` 对齐档位。
 */
class VlcPlaybackEngine : PlaybackEngine {

    private val state = MutableStateFlow(PlaybackSnapshot())

    override val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()

    /** 延迟到首次播放再建，避免"只是想看曲库"就要求本机装了 VLC。 */
    private val factory: MediaPlayerFactory by lazy { MediaPlayerFactory() }

    override fun prepare(source: PlaybackSource) {
        TODO(":desktop 阶段实现：mediaPlayer.media().prepare(source.url, options)")
    }

    override fun play() {
        TODO(":desktop 阶段实现：mediaPlayer.controls().play()")
    }

    override fun pause() {
        TODO(":desktop 阶段实现：mediaPlayer.controls().setPause(true)")
    }

    override fun seekTo(positionMs: Long) {
        TODO(":desktop 阶段实现：mediaPlayer.controls().setTime(positionMs)")
    }

    override fun setVolume(volume: Float) {
        TODO(":desktop 阶段实现：mediaPlayer.audio().setVolume((volume * 100).toInt())")
    }

    override fun release() {
        TODO(":desktop 阶段实现：mediaPlayer.release() + factory.release()")
    }
}
