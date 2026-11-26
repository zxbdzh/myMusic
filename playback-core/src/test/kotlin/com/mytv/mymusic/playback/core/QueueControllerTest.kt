package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 队列：下一首是谁、增删改之后当前曲目跟不跟着走、随机顺序怎么排。 */
class QueueControllerTest {
    private fun song(id: String) = Song(id = id, name = "曲目 $id", source = "local", duration = 180)

    private fun queueOf(vararg ids: String) = QueueController(Random(20261127)).apply {
        setQueue(ids.map(::song))
    }

    @Test
    fun `顺序播放走到头`() {
        val queue = queueOf("a", "b", "c")
        assertEquals(0, queue.state.value.index)
        queue.select(2)
        assertEquals(-1, queue.nextIndex())
        assertEquals(1, queue.previousIndex())
    }

    @Test
    fun `列表循环绕回第一首`() {
        val queue = queueOf("a", "b", "c")
        queue.setRepeatMode(RepeatMode.All)
        queue.select(2)
        assertEquals(0, queue.nextIndex())
        queue.select(0)
        assertEquals(2, queue.previousIndex())
    }

    @Test
    fun `单曲循环原地不动`() {
        val queue = queueOf("a", "b", "c")
        queue.setRepeatMode(RepeatMode.One)
        queue.select(1)
        assertEquals(1, queue.nextIndex())
        assertEquals(1, queue.previousIndex())
    }

    @Test
    fun `队列开头的上一首停在自己`() {
        val queue = queueOf("a", "b")
        assertEquals(0, queue.previousIndex())
    }

    @Test
    fun `在当前曲目前插入不切歌`() {
        val queue = queueOf("a", "b", "c")
        queue.select(1)
        queue.insert(0, listOf(song("x")))
        assertEquals(listOf("x", "a", "b", "c"), queue.state.value.songs.map { it.id })
        assertEquals(2, queue.state.value.index)
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `在当前曲目后插入不动当前曲目`() {
        val queue = queueOf("a", "b", "c")
        queue.select(1)
        queue.insert(2, listOf(song("x")))
        assertEquals(1, queue.state.value.index)
        assertEquals("b", queue.state.value.current?.id)
    }

    @Test
    fun `拖拽排序后当前曲目跟着走`() {
        val queue = queueOf("a", "b", "c", "d")
        queue.select(2) // c
        queue.move(fromIndex = 2, toIndex = 0)
        assertEquals(listOf("c", "a", "b", "d"), queue.state.value.songs.map { it.id })
        assertEquals("c", queue.state.value.current?.id)

        queue.move(fromIndex = 3, toIndex = 1)
        assertEquals(listOf("c", "d", "a", "b"), queue.state.value.songs.map { it.id })
        assertEquals("c", queue.state.value.current?.id)
    }

    @Test
    fun `删掉当前曲目停在原位置`() {
        val queue = queueOf("a", "b", "c")
        queue.select(1)
        queue.remove(1)
        assertEquals(listOf("a", "c"), queue.state.value.songs.map { it.id })
        assertEquals("c", queue.state.value.current?.id)

        queue.remove(1)
        assertEquals("a", queue.state.value.current?.id)
    }

    @Test
    fun `删空之后没有当前曲目`() {
        val queue = queueOf("a")
        queue.remove(0)
        assertEquals(-1, queue.state.value.index)
        assertNull(queue.state.value.current)
        assertEquals(-1, queue.nextIndex())
    }

    @Test
    fun `空队列追加把第一首当当前曲目`() {
        val queue = QueueController(Random(1))
        queue.append(listOf(song("a"), song("b")))
        assertEquals(0, queue.state.value.index)
        assertEquals(2, queue.state.value.songs.size)
    }

    @Test
    fun `随机播放按随机顺序走`() {
        val queue = QueueController(Random(42))
        queue.setQueue(listOf(song("a"), song("b"), song("c"), song("d")))
        queue.select(2)
        queue.setShuffle(true)

        val order = queue.state.value.order
        assertEquals(4, order.size)
        // 起播那首排第一，其余打乱
        assertEquals(2, order.first())

        val visited = mutableListOf(2)
        var next = queue.nextIndex()
        while (next != -1) {
            visited += next
            queue.select(next)
            next = queue.nextIndex()
        }
        assertEquals(order, visited)
    }

    @Test
    fun `关掉随机回到原始顺序`() {
        val queue = QueueController(Random(7))
        queue.setQueue(listOf(song("a"), song("b"), song("c")))
        queue.select(0)
        queue.setShuffle(true)
        queue.setShuffle(false)
        assertEquals(listOf(0, 1, 2), queue.state.value.order)
        assertEquals(1, queue.nextIndex())
    }

    @Test
    fun `清空重置随机顺序`() {
        val queue = queueOf("a", "b")
        queue.clear()
        assertTrue(queue.state.value.order.isEmpty())
        assertEquals(-1, queue.state.value.index)
    }
}
