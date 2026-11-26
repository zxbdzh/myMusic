package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * 播放队列的对外快照。
 *
 * [order] 只在随机播放时有意义：它是"下一次按什么顺序走"，和 [songs] 本身分开存，
 * 这样增删歌曲不会打乱已经排好的随机顺序（见 [QueueShuffleOrder] 的说明）。
 */
data class QueueState(
    val songs: List<Song> = emptyList(),
    /** 当前曲目下标；-1 表示还没开始。 */
    val index: Int = -1,
    val repeat: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    val order: List<Int> = emptyList(),
) {
    val current: Song? get() = songs.getOrNull(index)

    val hasNext: Boolean
        get() = when (repeat) {
            RepeatMode.One -> songs.isNotEmpty()
            else -> songs.isNotEmpty()
        }
}

/**
 * 播放队列契约：Android 侧是 media3 的 `MediaSession` 队列 + `QueueStore` 落盘，
 * 桌面侧由 [QueueController] 自己实现（libVLC 没有队列概念，切歌由引擎驱动）。
 *
 * 所有方法都同步返回，调用方负责把"该切到哪一首"翻译成引擎动作；
 * 为了让播放页能直接画进度，[state] 是唯一数据源。
 */
interface PlaybackQueue {
    val state: StateFlow<QueueState>

    /** 整份替换队列，并落到 [startIndex]（夹到合法范围）。 */
    fun setQueue(songs: List<Song>, startIndex: Int = 0)

    /** 追加到队尾。 */
    fun append(songs: List<Song>)

    /** 插到 [index] 之前；index 夹到 0..size。 */
    fun insert(index: Int, songs: List<Song>)

    /** 把一首挪到另一个位置（拖拽排序）。 */
    fun move(fromIndex: Int, toIndex: Int)

    fun remove(index: Int)

    fun clear()

    /** 直接选中某一首（用户点歌单里的某一行）。 */
    fun select(index: Int)

    fun setRepeatMode(mode: RepeatMode)

    /** 打开 / 关闭随机；打开时按当前曲目重新洗牌（当前曲目排第一）。 */
    fun setShuffle(enabled: Boolean)

    /** 下一首的下标；-1 表示队列到头（循环 / 随机的规则都在这里）。 */
    fun nextIndex(): Int

    /** 上一首的下标；-1 表示没有（队列为空）。 */
    fun previousIndex(): Int
}

/**
 * 纯内存队列（Android 的 `MediaSession` 队列语义 + `QueueShuffleOrder` 的随机顺序）。
 *
 * 之所以不直接用 media3 的 `Timeline`：桌面侧没有 media3，而且现有单测覆盖的是
 * "增删改之后下一首是谁"这类行为，和播放器无关。
 */
class QueueController(private val random: Random = Random.Default) : PlaybackQueue {
    private val _state = MutableStateFlow(QueueState())
    override val state: StateFlow<QueueState> = _state.asStateFlow()

    private var shuffleOrder: QueueShuffleOrder = QueueShuffleOrder(random)

    override fun setQueue(songs: List<Song>, startIndex: Int) {
        val index = if (songs.isEmpty()) -1 else startIndex.coerceIn(0, songs.lastIndex)
        shuffleOrder = if (_state.value.shuffle) {
            shuffleOrder.cloneAndSet(songs.size, index)
        } else {
            QueueShuffleOrder.of(IntArray(songs.size) { it })
        }
        _state.value = _state.value.copy(songs = songs.toList(), index = index, order = shuffleOrder.toList())
    }

    override fun append(songs: List<Song>) {
        if (songs.isEmpty()) return
        val old = _state.value
        val merged = old.songs + songs
        // 空队列里追加：直接把第一首当当前曲目，播放页才有东西可画
        val index = if (old.index < 0) 0 else old.index
        shuffleOrder = shuffleOrder.cloneAndInsert(old.songs.size, songs.size)
        _state.value = old.copy(songs = merged, index = index, order = shuffleOrder.toList())
    }

    override fun insert(index: Int, songs: List<Song>) {
        if (songs.isEmpty()) return
        val old = _state.value
        val at = index.coerceIn(0, old.songs.size)
        val merged = old.songs.toMutableList().apply { addAll(at, songs) }
        // 插在当前曲目之前，当前曲目跟着后移，别因为插入而切歌
        val next = when {
            old.index < 0 -> 0
            at <= old.index -> old.index + songs.size
            else -> old.index
        }
        shuffleOrder = shuffleOrder.cloneAndInsert(at, songs.size)
        _state.value = old.copy(songs = merged, index = next, order = shuffleOrder.toList())
    }

    override fun move(fromIndex: Int, toIndex: Int) {
        val old = _state.value
        if (fromIndex !in old.songs.indices || toIndex !in old.songs.indices || fromIndex == toIndex) return
        val merged = old.songs.toMutableList()
        val song = merged.removeAt(fromIndex)
        merged.add(toIndex, song)
        val next = when {
            old.index == fromIndex -> toIndex
            fromIndex < old.index && toIndex >= old.index -> old.index - 1
            fromIndex > old.index && toIndex <= old.index -> old.index + 1
            else -> old.index
        }
        val lo = minOf(fromIndex, toIndex)
        val hi = maxOf(fromIndex, toIndex)
        shuffleOrder = shuffleOrder.cloneAndMove(lo, hi + 1, lo)
        _state.value = old.copy(songs = merged, index = next, order = shuffleOrder.toList())
    }

    override fun remove(index: Int) {
        val old = _state.value
        if (index !in old.songs.indices) return
        val merged = old.songs.toMutableList().apply { removeAt(index) }
        val next = when {
            merged.isEmpty() -> -1
            // 删的就是当前这首：停在原位置（也就是原来的下一首），删到队尾就退一首
            index < old.index -> old.index - 1
            index == old.index -> old.index.coerceAtMost(merged.lastIndex)
            else -> old.index
        }
        shuffleOrder = shuffleOrder.cloneAndRemove(index, index + 1)
        _state.value = old.copy(songs = merged, index = next, order = shuffleOrder.toList())
    }

    override fun clear() {
        val old = _state.value
        shuffleOrder = shuffleOrder.cloneAndClear()
        _state.value = old.copy(songs = emptyList(), index = -1, order = emptyList())
    }

    override fun select(index: Int) {
        val old = _state.value
        if (old.songs.isEmpty()) return
        _state.value = old.copy(index = index.coerceIn(0, old.songs.lastIndex))
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeat = mode) }
    }

    override fun setShuffle(enabled: Boolean) {
        val old = _state.value
        if (old.shuffle == enabled) return
        shuffleOrder = if (enabled) {
            shuffleOrder.cloneAndSet(old.songs.size, old.index)
        } else {
            QueueShuffleOrder.of(IntArray(old.songs.size) { it })
        }
        _state.value = old.copy(shuffle = enabled, order = shuffleOrder.toList())
    }

    override fun nextIndex(): Int {
        val old = _state.value
        if (old.songs.isEmpty()) return -1
        if (old.repeat == RepeatMode.One) return old.index
        if (old.shuffle) {
            val next = shuffleOrder.nextIndex(old.index)
            return when {
                next != QueueShuffleOrder.INDEX_UNSET -> next
                old.repeat == RepeatMode.All -> shuffleOrder.firstIndex()
                else -> -1
            }
        }
        return when {
            old.index + 1 <= old.songs.lastIndex -> old.index + 1
            old.repeat == RepeatMode.All -> 0
            else -> -1
        }
    }

    override fun previousIndex(): Int {
        val old = _state.value
        if (old.songs.isEmpty()) return -1
        if (old.repeat == RepeatMode.One) return old.index
        if (old.shuffle) {
            val previous = shuffleOrder.previousIndex(old.index)
            return when {
                previous != QueueShuffleOrder.INDEX_UNSET -> previous
                old.repeat == RepeatMode.All -> shuffleOrder.lastIndex()
                else -> old.index.coerceAtLeast(0)
            }
        }
        return when {
            old.index - 1 >= 0 -> old.index - 1
            old.repeat == RepeatMode.All -> old.songs.lastIndex
            else -> old.index.coerceAtLeast(0)
        }
    }
}
