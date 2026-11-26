package com.mytv.mymusic.playback.core

import kotlin.random.Random

/**
 * 跟着队列改动走的随机顺序（从 Android 的 `QueueShuffleOrder` 原样搬来，去掉 media3 的 ShuffleOrder 接口）。
 *
 * 播放器自带的随机顺序只按位置记：挪动一首它不跟着走，新插的随手一放；换地址（先在后面插新的、
 * 再删掉旧的）时正在放的这首被删，播放器就沿着随机顺序跳到一首不相干的歌上。这里改成：
 * - 换一整个队列：起播的那首排第一，其余打乱；
 * - 插入或挪动单独一首：排到它在队列里前一首的紧后面。"下一首播放"就是挪 / 插到正在放的这首后面，
 *   随机时也照样下一首放它；换地址时新的那首顶上旧的那一格；
 * - 一次插入多首：各自随机放；挪动多首、删除：其余先后不变。
 *
 * 不可变：每个改动方法都返回新的一份，调用方（[QueueController]）自己换引用。
 */
class QueueShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val random: Random,
) {
    /** 反查表：某个队列下标在随机顺序里的位置。 */
    private val indexInShuffled =
        IntArray(shuffled.size).also { at -> shuffled.forEachIndexed { index, value -> at[value] = index } }

    constructor(random: Random = Random.Default) : this(IntArray(0), random)

    val length: Int get() = shuffled.size

    fun nextIndex(index: Int): Int {
        val at = indexInShuffled.getOrNull(index) ?: return INDEX_UNSET
        return shuffled.getOrElse(at + 1) { INDEX_UNSET }
    }

    fun previousIndex(index: Int): Int {
        val at = indexInShuffled.getOrNull(index) ?: return INDEX_UNSET
        return shuffled.getOrElse(at - 1) { INDEX_UNSET }
    }

    fun firstIndex(): Int = shuffled.firstOrNull() ?: INDEX_UNSET

    fun lastIndex(): Int = shuffled.lastOrNull() ?: INDEX_UNSET

    fun cloneAndSet(insertionCount: Int, startIndex: Int): QueueShuffleOrder {
        val order = (0 until insertionCount).filter { it != startIndex }.shuffled(random).toMutableList()
        if (startIndex in 0 until insertionCount) order.add(0, startIndex)
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): QueueShuffleOrder {
        // 空顺序要覆盖到插入位置：长度是「前 <insertionIndex> 首 + 新插入的」，不能只排新插入的几首
        if (shuffled.isEmpty()) return cloneAndSet(insertionIndex + insertionCount, INDEX_UNSET)
        val order = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        if (insertionCount == 1) {
            order.placeAfter(insertionIndex - 1, insertionIndex)
        } else {
            for (i in 0 until insertionCount) order.add(random.nextInt(order.size + 1), insertionIndex + i)
        }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): QueueShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count <= 0 || indexFrom == newIndexFrom) return this
        // 队列挪完后每一首的新下标（同 Player.moveMediaItems：先取出这几首，再从 newIndexFrom 放回去）
        val queue = MutableList(shuffled.size) { it }
        val moved = queue.subList(indexFrom, indexToExclusive).toList()
        queue.subList(indexFrom, indexToExclusive).clear()
        queue.addAll(newIndexFrom, moved)
        val newIndex = IntArray(queue.size).also { at -> queue.forEachIndexed { i, old -> at[old] = i } }
        val order = shuffled.map { newIndex[it] }.toMutableList()
        if (count == 1) {
            order.removeAt(order.indexOf(newIndexFrom))
            order.placeAfter(newIndexFrom - 1, newIndexFrom)
        }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): QueueShuffleOrder {
        val count = indexToExclusive - indexFrom
        val order = shuffled
            .filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    fun cloneAndClear(): QueueShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    /** 当前的随机顺序；换主播放器时原样带过去，过渡后"下一首"不要变成另一个顺序。 */
    fun order(): IntArray = shuffled.copyOf()

    /** 按随机顺序排好的队列下标，测试用。 */
    fun toList(): List<Int> = shuffled.toList()

    companion object {
        /** 队列下标无效（没有下一首 / 不在随机顺序里）。 */
        const val INDEX_UNSET: Int = -1

        /** 用一份现成的顺序建一个新的；长度必须和播放器的曲目数一致。 */
        fun of(order: IntArray): QueueShuffleOrder = QueueShuffleOrder(order, Random.Default)
    }

    /** 把 [index] 插到 [after] 的紧后面；[after] 不在（队列第一首之前）就排第一。 */
    private fun MutableList<Int>.placeAfter(after: Int, index: Int) = add(indexOf(after) + 1, index)
}
