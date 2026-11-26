package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * VLC 发现与启动参数：纯逻辑，不加载 native 库，CI 里没有 VLC 也能跑。
 *
 * 这几条钉住的是"装了两份 VLC / 只装了绿色版"时的行为：
 * 显式目录优先、`jna.library.path` 必须指到 libvlc.dll 所在目录、
 * 并且不再传 VLC 3.0.21 已经删掉的 `--plugin-path`（否则每次启动打一行警告）。
 */
class VlcOptionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fakeVlc(home: File): File {
        home.mkdirs()
        File(home, "libvlc.dll").writeText("fake")
        File(home, "plugins").mkdirs()
        return home
    }

    @Test
    fun `显式目录优先`() {
        val home = fakeVlc(temp.newFolder("vlc-3.0.21"))
        val options = VlcOptions.discover(home.absolutePath)
        assertEquals(home.absolutePath, options.home?.absolutePath)
        assertEquals(File(home, "plugins").absolutePath, options.pluginsDir?.absolutePath)
        assertTrue(options.describe("3.0.21").contains("3.0.21"))
        assertTrue(options.describe("3.0.21").contains(home.absolutePath))
    }

    @Test
    fun `空目录不算 VLC`() {
        val empty = temp.newFolder("empty")
        // 目录里没有 libvlc.dll：显式传进来也不认，落回自动发现（本机没装就是 null）
        val options = VlcOptions.discover(empty.absolutePath)
        assertFalse(options.home?.absolutePath == empty.absolutePath)
    }

    @Test
    fun `启动参数不带已删除的 plugin-path`() {
        val home = fakeVlc(temp.newFolder("vlc"))
        val args = VlcOptions.discover(home.absolutePath).factoryArgs()
        assertTrue(args.contains("--no-video"))
        assertTrue(args.contains("--intf=dummy"))
        assertTrue(args.contains("--quiet"))
        assertTrue(args.none { it.startsWith("--plugin-path") })
    }

    @Test
    fun `配置 jna_library_path`() {
        val home = fakeVlc(temp.newFolder("vlc"))
        val previous = System.getProperty("jna.library.path")
        try {
            System.clearProperty("jna.library.path")
            VlcOptions.discover(home.absolutePath).configureNativePath()
            assertTrue(
                System.getProperty("jna.library.path").orEmpty()
                    .split(File.pathSeparator)
                    .contains(home.absolutePath),
            )
        } finally {
            if (previous == null) System.clearProperty("jna.library.path") else System.setProperty("jna.library.path", previous)
        }
    }

    @Test
    fun `本地 MRL 归一化成三斜杠`() {
        // Java 的 File.toURI() 只给单斜杠 file:/F:/...，libvlc 会拼到工作目录后面打不开
        assertEquals("file:///F:/tmp/a.flac", normalizeMrl("file:/F:/tmp/a.flac"))
        assertEquals("file:///F:/tmp/a.flac", normalizeMrl("file:///F:/tmp/a.flac"))
        assertEquals("https://cdn.example.com/a.flac", normalizeMrl("https://cdn.example.com/a.flac"))
        assertEquals("rtsp://live.example.com/a", normalizeMrl("rtsp://live.example.com/a"))
        assertTrue(normalizeMrl("F:\\tmp\\a.flac").startsWith("file:///"))
    }

    @Test
    fun `缓存与续播目录分开`() {        val options = VlcOptions.discover(fakeVlc(temp.newFolder("vlc")).absolutePath)
        val cache = options.cacheDir()
        val state = options.stateDir()
        assertTrue(cache.absolutePath.endsWith("cache${File.separator}playback"))
        assertTrue(state.absolutePath.endsWith("${File.separator}playback"))
        assertFalse(cache.absolutePath == state.absolutePath)
        // 缓存配置默认 512MB，三档缓存时长按音源类型分
        val config = options.cacheConfig()
        assertEquals(512L * 1024 * 1024, config.maxBytes)
        assertEquals(2_000, config.cachingMs(PlaybackSource(url = "https://cdn.example.com/a.flac")))
        assertEquals(1_000, config.cachingMs(PlaybackSource(url = "file:///F:/a.flac")))
        assertEquals(300, config.cachingMs(PlaybackSource(url = "https://live.example.com/a.m3u8", live = true)))
    }
}
