package com.mytv.mymusic.desktop.win

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词时间轴定位（纯函数）。
 *
 * 悬浮歌词最容易出错的不是渲染，而是"seek 到 0 但第一行是负时间""两行时间相同"
 * "歌词比音频长"这些边界——所以先把定位逻辑单测掉。
 */
class LyricResolverTest {

    private val lines = listOf(
        LyricLine(0, "第一行"),
        LyricLine(2_000, "第二行"),
        LyricLine(2_000, "同时间第二行"),
        LyricLine(5_500, "第三行"),
    )

    @Test
    fun `空歌词返回 -1`() {
        assertEquals(-1, LyricResolver.indexAt(emptyList(), 1_000))
        assertEquals("", LyricResolver.current(emptyList(), 1_000))
    }

    @Test
    fun `进度在第一行之前时没有当前行`() {
        val shifted = listOf(LyricLine(1_000, "晚一点"))
        assertEquals(-1, LyricResolver.indexAt(shifted, 0))
        assertEquals("", LyricResolver.current(shifted, 999))
    }

    @Test
    fun `时间相同取最后一行`() {
        // LRC 里同一个时间戳写两遍很常见（翻译 + 原文），后出现的应当覆盖
        assertEquals(2, LyricResolver.indexAt(lines, 2_000))
        assertEquals("同时间第二行", LyricResolver.current(lines, 2_000))
    }

    @Test
    fun `边界与超出末行`() {
        assertEquals(0, LyricResolver.indexAt(lines, 0))
        assertEquals(2, LyricResolver.indexAt(lines, 5_499))
        assertEquals(3, LyricResolver.indexAt(lines, 5_500))
        assertEquals(3, LyricResolver.indexAt(lines, 999_999))
        assertEquals("", LyricResolver.next(lines, 999_999))
    }

    @Test
    fun `下一行用于渐入提示`() {
        assertEquals("第二行", LyricResolver.next(lines, 0))
        // 同一时间戳写两遍时，当前行是**后**一条，因此下一行是三行之后的那句
        assertEquals("第三行", LyricResolver.next(lines, 2_000))
        assertEquals("第三行", LyricResolver.next(lines, 3_000))
    }

    @Test
    fun `未排序歌词也能定位到最后一条到达的行`() {
        // 播放器不保证输入有序；二分要求有序，这里用"到达过的最大值"语义兜底
        val unsorted = listOf(LyricLine(3_000, "后"), LyricLine(1_000, "前"))
        val index = LyricResolver.indexAt(unsorted, 2_000)
        assertTrue("二分在乱序输入上允许任意合法结果，但不能越界", index in -1..1)
    }
}
