package com.mytv.mymusic.desktop.win

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令分发：四个系统入口最终都走 [MediaCommandSink]，这里验证"发什么到哪去"。
 *
 * 不依赖窗口：媒体浮层、托盘、热键、任务栏按钮的行为差异在各自的冒烟测试里，
 * 这里只锁住"命令 → 目标"这条公共路径。
 */
class MediaCommandSinkTest {

    private class Recorder : MediaControlTarget {
        val received = mutableListOf<MediaCommand>()
        override fun onCommand(command: MediaCommand) {
            received += command
        }
    }

    @Test
    fun `每个命令都只投递一次且保序`() {
        val recorder = Recorder()
        val sink = MediaCommandSink(recorder)

        listOf(
            MediaCommand.TOGGLE_PLAY_PAUSE,
            MediaCommand.NEXT,
            MediaCommand.PREVIOUS,
            MediaCommand.STOP,
        ).forEach(sink::dispatch)

        assertEquals(
            listOf(
                MediaCommand.TOGGLE_PLAY_PAUSE,
                MediaCommand.NEXT,
                MediaCommand.PREVIOUS,
                MediaCommand.STOP,
            ),
            recorder.received,
        )
        assertEquals(4, sink.dispatchedCount)
    }

    @Test
    fun `发布状态不触发命令`() {
        val recorder = Recorder()
        val sink = MediaCommandSink(recorder)
        sink.publish(NowPlaying(title = "标题", isPlaying = true))

        assertEquals("标题", sink.state.value.title)
        assertTrue(sink.state.value.isPlaying)
        assertEquals(0, sink.dispatchedCount)
        assertTrue(recorder.received.isEmpty())
    }

    @Test
    fun `空标题回退到应用名`() {
        assertEquals("myMusic", NowPlaying().displayLine)
        assertEquals("只有标题", NowPlaying(title = "只有标题").displayLine)
        assertEquals("标题 — 歌手", NowPlaying(title = "标题", artist = "歌手").displayLine)
    }

    @Test
    fun `并发分发不丢命令`() {
        val recorder = object : MediaControlTarget {
            private val count = java.util.concurrent.atomic.AtomicInteger()
            override fun onCommand(command: MediaCommand) {
                count.incrementAndGet()
            }

            fun total() = count.get()
        }
        val sink = MediaCommandSink(recorder)
        val threads = (0 until 8).map {
            Thread { repeat(100) { sink.dispatch(MediaCommand.NEXT) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(800, sink.dispatchedCount)
        assertFalse(recorder.total() < 800)
    }
}
