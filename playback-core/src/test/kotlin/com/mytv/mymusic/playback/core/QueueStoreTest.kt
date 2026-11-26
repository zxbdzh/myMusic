package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 续播落盘：队列 / 位置分两个文件写，中途被杀要能自己对齐；
 * 在线地址不能落盘（会过期），本地文件原样保留。
 */
class QueueStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun song(id: String, url: String = "https://cdn.example.com/$id.flac") = Song(
        id = id,
        name = "曲目 $id",
        artist = "歌手",
        album = "专辑",
        duration = 200,
        source = "local",
        url = url,
        ext = "flac",
    )

    @Test
    fun `没有存档时是空的`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        assertFalse(store.hasSaved())
        assertNull(store.load())
    }

    @Test
    fun `存取一轮`() = runTest {
        val dir = temp.newFolder("playback")
        val store = QueueStore(dir)
        val songs = listOf(song("a"), song("b"), song("c"))
        store.saveQueue(songs, PlaybackOrigin(kind = "collection", key = "top/1", title = "排行榜"))
        store.saveCursor(SavedCursor(key = "local:b", index = 1, positionMs = 42_000L, durationMs = 200_000L))

        assertTrue(store.hasSaved())
        val loaded = store.load()!!
        assertEquals(songs.map { it.key }, loaded.songs.map { it.key })
        assertEquals(1, loaded.index)
        assertEquals("local:b", loaded.current?.key)
        assertEquals(42_000L, loaded.cursor.positionMs)
        assertEquals("排行榜", loaded.origin?.title)
        // 在线地址不落盘：存盘时清空，续播时由 Provider 重新解析
        assertTrue(loaded.songs.all { it.url.isBlank() })
    }

    @Test
    fun `本地文件地址原样保留`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        val local = song("d", url = "file:///F:/music/d.flac")
        store.saveQueue(listOf(local))
        store.saveCursor(SavedCursor(key = "local:d", index = 0, positionMs = 1_000L, durationMs = 200_000L))

        val loaded = store.load()!!
        assertEquals("file:///F:/music/d.flac", loaded.songs.single().url)
    }

    @Test
    fun `位置和队列对不上时按歌找`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        store.saveQueue(listOf(song("a"), song("b"), song("c")))
        // 上一次写的是第 3 首，但队列后来缩短了 —— 按 key 找回 b
        store.saveCursor(SavedCursor(key = "local:b", index = 9, positionMs = 5_000L, durationMs = 200_000L))

        val loaded = store.load()!!
        assertEquals(1, loaded.index)
        assertEquals("local:b", loaded.current?.key)
    }

    @Test
    fun `找不到那首就从头放`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        store.saveQueue(listOf(song("a"), song("b")))
        store.saveCursor(SavedCursor(key = "local:gone", index = 1, positionMs = 5_000L, durationMs = 200_000L))

        val loaded = store.load()!!
        assertEquals(0, loaded.cursor.index)
        assertEquals("local:a", loaded.cursor.key)
    }

    @Test
    fun `上次停在末尾时从头接上`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        store.saveQueue(listOf(song("a"), song("b")))
        store.saveCursor(SavedCursor(key = "local:b", index = 1, positionMs = 199_500L, durationMs = 200_000L))

        val loaded = store.load()!!
        assertEquals(0L, loaded.cursor.positionMs)
    }

    @Test
    fun `循环与随机的字符串能还原`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        store.saveQueue(listOf(song("a")))
        store.saveCursor(SavedCursor(key = "local:a", index = 0, shuffle = true, repeat = RepeatMode.All.name))

        val loaded = store.load()!!
        assertTrue(loaded.cursor.shuffle)
        assertEquals(RepeatMode.All, loaded.cursor.repeatMode)
    }

    @Test
    fun `清空之后没有存档`() = runTest {
        val dir = temp.newFolder("playback")
        val store = QueueStore(dir)
        store.saveQueue(listOf(song("a")))
        store.saveCursor(SavedCursor(key = "local:a"))
        store.clear()

        assertFalse(store.hasSaved())
        assertNull(store.load())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `空队列等于清空`() = runTest {
        val store = QueueStore(temp.newFolder("playback"))
        store.saveQueue(listOf(song("a")))
        store.saveQueue(emptyList())
        assertFalse(store.hasSaved())
    }

    @Test
    fun `坏文件当没有存档`() = runTest {
        val dir = temp.newFolder("playback")
        dir.resolve("queue.json").writeText("{ 这不是 json")
        val store = QueueStore(dir)
        assertNull(store.load())
    }
}
