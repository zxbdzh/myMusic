package com.mytv.mymusic.desktop.state.usecase

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.desktop.state.LyricsDoc
import com.mytv.mymusic.desktop.state.MusicCatalog
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.audio.AudioOutput
import com.mytv.mymusic.desktop.state.lyric.LyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 播放域状态：队列、进度、当前歌词。和 Android `canonicalQueue` + `position` + `lyrics` 对应。 */
@Immutable
data class PlaybackUi(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val volume: Float = 0.8f,
    val lyrics: LyricsDoc? = null,
    val lyricsLoading: Boolean = false,
    val error: String = "",
)

/**
 * 播放用例：队列 + 与 [AudioOutput] 的交接 + 歌词拉取。
 *
 * 和 Android 的分工对照：
 *  - `QueueStore`（队列持久化）→ 这里只有内存队列，落盘属于 t3 / P2；
 *  - `PlaybackService`（进程内 ExoPlayer）→ [AudioOutput]（桌面是 libVLC 或模拟）；
 *  - `MusicViewModel.play*` 的换源重试 → 骨架期只做一次 `catalog.stream`，
 *    「取流失败换下一个音源」是 t2/t3 的活，接口已经留好（stream 返回 null 就报错）。
 *
 * 所有状态更新都走 `_state.update`，UI 侧只 collect [state]。
 */
class PlaybackUseCase(
    private val catalog: MusicCatalog,
    private val audio: AudioOutput,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlaybackUi(volume = audio.snapshot.value.volume))
    val state: StateFlow<PlaybackUi> = _state.asStateFlow()

    init {
        // 后端是唯一进度来源：引擎（或模拟器）怎么报，界面就怎么显示，
        // 不让 UI 自己算时间——否则 seek / 缓冲 / 换曲时会漂
        scope.launch {
            audio.snapshot.collect { snapshot ->
                _state.update { current ->
                    current.copy(
                        playing = snapshot.playing,
                        buffering = snapshot.buffering,
                        positionMs = snapshot.positionMs,
                        durationMs = if (snapshot.durationMs > 0) snapshot.durationMs else current.durationMs,
                        volume = snapshot.volume,
                        error = snapshot.error.ifBlank { current.error },
                    )
                }
            }
        }
    }

    /** 从一首歌起播；[queue] 为空时只播这一首（搜索结果点播就是这个语义）。 */
    fun play(track: Track, queue: List<Track> = emptyList()) {
        val list = queue.ifEmpty { listOf(track) }
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playQueue(list, index)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(tracks.indices)
        _state.update {
            it.copy(
                queue = tracks,
                queueIndex = index,
                current = tracks[index],
                positionMs = 0L,
                durationMs = tracks[index].durationMs,
                lyrics = null,
                lyricsLoading = true,
                error = "",
            )
        }
        startCurrent(autoPlay = true)
    }

    fun toggle() {
        val current = _state.value
        if (current.current == null) return
        if (current.playing) audio.pause() else audio.play()
    }

    /**
     * 右键「下一首播放」：插到当前曲目之后，**不打断**正在播的那首。
     *
     * 只改队列与索引，不碰音频后端；已经在队列里的曲目先摘掉再插入，
     * 避免同一首要放两遍。摘除会让后续索引整体前移，所以插入后要按 id 重新定位当前曲目。
     */
    fun insertNext(track: Track) {
        val current = _state.value
        if (current.queue.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }
        val queue = current.queue.toMutableList()
        val existing = queue.indexOfFirst { it.id == track.id }
        if (existing >= 0) queue.removeAt(existing)
        val insertAt = (current.queueIndex + 1).coerceIn(0, queue.size)
        queue.add(insertAt, track)
        val newIndex = queue.indexOfFirst { it.id == current.current?.id }.coerceAtLeast(0)
        _state.update { it.copy(queue = queue, queueIndex = newIndex) }
    }

    fun next() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        val index = (current.queueIndex + 1) % current.queue.size
        playQueue(current.queue, index)
    }

    fun previous() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        // 和绝大多数播放器一致：播过 3 秒先回到本曲开头，再按一次才切上一首
        if (current.positionMs > 3_000L) {
            seekTo(0L)
            return
        }
        val index = if (current.queueIndex - 1 < 0) current.queue.lastIndex else current.queueIndex - 1
        playQueue(current.queue, index)
    }

    fun seekTo(positionMs: Long) {
        audio.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun setVolume(volume: Float) {
        val value = volume.coerceIn(0f, 1f)
        audio.setVolume(value)
        _state.update { it.copy(volume = value) }
    }

    fun release() = audio.release()

    private fun startCurrent(autoPlay: Boolean) {
        val track = _state.value.current ?: return
        if (!track.playable) {
            _state.update {
                it.copy(
                    playing = false,
                    lyricsLoading = false,
                    error = "《${track.title}》当前不可播放（未登录或无版权）",
                )
            }
            return
        }
        scope.launch {
            val stream = runCatching { catalog.stream(track) }.getOrNull()
            if (stream == null) {
                _state.update {
                    it.copy(
                        playing = false,
                        lyricsLoading = false,
                        error = "《${track.title}》取流失败：音源返回空地址",
                    )
                }
                return@launch
            }
            audio.load(track, stream)
            // load 之后 duration 以曲目元数据为准；模拟输出和 libVLC 都会带回来
            _state.update { it.copy(durationMs = track.durationMs, error = "") }
            if (autoPlay) audio.play()
            loadLyrics(track)
        }
    }

    private fun loadLyrics(track: Track) {
        scope.launch {
            val raw = runCatching { catalog.lyrics(track) }.getOrNull().orEmpty()
            val doc = if (raw.isBlank()) null else LyricsParser.parse(raw)
            _state.update { it.copy(lyrics = doc, lyricsLoading = false) }
        }
    }
}
