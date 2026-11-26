package com.mytv.mymusic.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 缓存：VLC 选项、缓存文件名、按最久未用淘汰。 */
class PlaybackCacheConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun config(maxBytes: Long = 1_000L) = PlaybackCacheConfig(dir = temp.newFolder("cache"), maxBytes = maxBytes)

    @Test
    fun `网络流用网络缓存并带上防盗链头`() {
        val options = config().vlcOptions(
            PlaybackSource(
                url = "https://cdn.example.com/a.flac",
                headers = mapOf("User-Agent" to "myMusic/1.1", "Referer" to "https://music.example.com/"),
            ),
        )
        assertTrue(options.contains(":network-caching=2000"))
        assertTrue(options.contains(":file-caching=1000"))
        assertTrue(options.contains(":http-user-agent=myMusic/1.1"))
        assertTrue(options.contains(":http-referrer=https://music.example.com/"))
        assertTrue(options.contains(":no-video"))
        assertFalse(options.any { it.startsWith(":live-caching") })
    }

    @Test
    fun `直播单独压短缓存`() {
        val options = config().vlcOptions(PlaybackSource(url = "https://live.example.com/a.m3u8", live = true))
        assertTrue(options.contains(":network-caching=300"))
        assertTrue(options.contains(":live-caching=300"))
    }

    @Test
    fun `本地文件走文件缓存`() {
        val options = config().vlcOptions(PlaybackSource(url = "file:///F:/music/a.flac"))
        assertTrue(options.contains(":file-caching=1000"))
        assertFalse(options.contains(":network-caching=2000"))
    }

    @Test
    fun `缓存文件名稳定且带扩展名`() {
        val cache = config()
        val source = PlaybackSource(url = "https://cdn.example.com/a.flac", ext = "flac")
        val first = cache.entryFor(source)
        val second = cache.entryFor(source)
        assertEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.name.endsWith(".flac"))
        assertNotEquals(first.name, cache.entryFor(source.copy(url = "https://cdn.example.com/b.flac")).name)
    }

    @Test
    fun `按最久未用淘汰到上限以内`() {
        val cache = config(maxBytes = 1_000L)
        val dir = cache.dir
        val oldest = dir.resolve("old.bin").apply { writeBytes(ByteArray(600)); setLastModified(1_000L) }
        val middle = dir.resolve("mid.bin").apply { writeBytes(ByteArray(600)); setLastModified(2_000L) }
        val newest = dir.resolve("new.bin").apply { writeBytes(ByteArray(600)); setLastModified(3_000L) }

        assertEquals(1_800L, cache.sizeBytes())
        val removed = cache.trim()

        // 要落到 1000 以内得删两份：最旧的 mid 也一起被淘汰
        assertEquals(1_200L, removed)
        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertEquals(600L, cache.sizeBytes())
    }

    @Test
    fun `正在播放的缓存不删`() {
        val cache = config(maxBytes = 1_000L)
        val dir = cache.dir
        val playing = dir.resolve("playing.bin").apply { writeBytes(ByteArray(800)); setLastModified(1_000L) }
        dir.resolve("other.bin").apply { writeBytes(ByteArray(800)); setLastModified(2_000L) }

        cache.trim(keep = listOf(playing))
        assertTrue(playing.exists())
        assertTrue(cache.sizeBytes() <= 1_000L || !dir.resolve("other.bin").exists())
    }

    @Test
    fun `清空缓存目录`() {
        val cache = config()
        cache.dir.resolve("a.bin").writeBytes(ByteArray(10))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
    }
}
