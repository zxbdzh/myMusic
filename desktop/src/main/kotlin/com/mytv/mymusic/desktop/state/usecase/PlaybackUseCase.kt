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
import kotlin.random.Random

/** 播放模式：顺序循环 / 单曲循环 / 随机播放。 */
enum class PlayMode(val label: String) {
    Sequence("列表循环"),
    Single("单曲循环"),
    Shuffle("随机播放"),
    ;

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/** 播放域状态：队列、进度、播放模式、当前歌词。 */
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
    val playMode: PlayMode = PlayMode.Sequence,
    val lyrics: LyricsDoc? = null,
    val lyricsLoading: Boolean = false,
    val error: String = "",
    val streamNote: String = "",
)

/**
 * 播放用例：队列 + 与 [AudioOutput] 的交接 + 自动切歌 + 历史落库 + 歌词拉取。
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

    @Volatile
    private var userPaused: Boolean = false

    @Volatile
    private var lastWasPlaying: Boolean = false

    init {
        // 后端是唯一进度来源：引擎怎么报，界面就怎么显示，避免 seek / 缓冲 / 换曲时漂移
        scope.launch {
            audio.snapshot.collect { snapshot ->
                val finishedNaturally = lastWasPlaying &&
                    !snapshot.playing &&
                    !userPaused &&
                    snapshot.durationMs > 0L &&
                    snapshot.positionMs >= snapshot.durationMs - 500L
                lastWasPlaying = snapshot.playing

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

                if (finishedNaturally) {
                    advanceAfterTrackEnded()
                }
            }
        }
    }

    /** 从一首歌起播；[queue] 为空时只播这一首。 */
    fun play(track: Track, queue: List<Track> = emptyList()) {
        val list = queue.ifEmpty { listOf(track) }
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playQueue(list, index)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(tracks.indices)
        userPaused = false
        lastWasPlaying = false
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
                streamNote = "",
            )
        }
        startCurrent(autoPlay = true)
    }

    fun toggle() {
        val current = _state.value
        if (current.current == null) return
        if (current.playing) {
            userPaused = true
            audio.pause()
        } else {
            userPaused = false
            audio.play()
        }
    }

    fun setPlayMode(mode: PlayMode) {
        _state.update { it.copy(playMode = mode) }
    }

    fun cyclePlayMode(): PlayMode {
        val nextMode = _state.value.playMode.next()
        setPlayMode(nextMode)
        return nextMode
    }

    /**
     * 右键「下一首播放」：插到当前曲目之后，不打断正在播的那首。
     * 已在队列里的同曲先摘除再插入，避免重复播放。
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
        val index = pickNextIndex(current.queue.size, current.queueIndex, current.playMode, manual = true)
        playQueue(current.queue, index)
    }

    fun previous() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        // 播过 3 秒先回到本曲开头，再按一次才切上一首
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

    private fun advanceAfterTrackEnded() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        val nextIndex = pickNextIndex(current.queue.size, current.queueIndex, current.playMode, manual = false)
        playQueue(current.queue, nextIndex)
    }

    private fun pickNextIndex(size: Int, currentIndex: Int, mode: PlayMode, manual: Boolean): Int {
        if (size <= 1) return 0
        return when (mode) {
            PlayMode.Single -> if (manual) (currentIndex + 1) % size else currentIndex.coerceIn(0, size - 1)
            PlayMode.Sequence -> (currentIndex + 1) % size
            PlayMode.Shuffle -> {
                val candidate = Random.nextInt(size - 1)
                if (candidate >= currentIndex) candidate + 1 else candidate
            }
        }
    }

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
                        error = "《${track.title}》取流失败：原平台与自动换源均未返回可播地址",
                    )
                }
                return@launch
            }
            audio.load(track, stream)
            _state.update {
                it.copy(
                    durationMs = track.durationMs,
                    error = "",
                    streamNote = stream.note,
                )
            }
            if (autoPlay) audio.play()
            // 历史写入共享 Room 库，不阻塞起播
            launch { runCatching { catalog.recordHistory(track) } }
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
