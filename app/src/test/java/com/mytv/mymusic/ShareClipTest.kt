package com.mytv.mymusic

import com.mytv.mymusic.data.share.shareClipRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 片段范围的规则（来自 docs/online-sharing/DESIGN.md Q8/Q17）：
 * 取最早与最晚选句，前后各留 1 秒，夹在歌曲长度内；没有时间戳或范围不成立时不可用。
 *
 * 这些是规格里的已知值，不是按实现反推的。
 */
class ShareClipTest {

    @Test
    fun singleLineLeavesOneSecondOnEachSide() {
        // 60s 那句，下一句 90s 开始 → 结束取下一句起点
        val range = shareClipRange(
            starts = listOf(60_000L, 90_000L),
            selected = setOf(0),
            durationMs = 180_000L,
            reliableTiming = true,
        )
        assertEquals(59_000L, range?.startMs)
        assertEquals(91_000L, range?.endMs)
    }

    @Test
    fun firstLineClampsStartAtZero() {
        // 500ms 那句往前留 1 秒会变成负数
        val range = shareClipRange(
            starts = listOf(500L, 30_000L),
            selected = setOf(0),
            durationMs = 180_000L,
            reliableTiming = true,
        )
        assertEquals(0L, range?.startMs)
        assertEquals(31_000L, range?.endMs)
    }

    @Test
    fun lastLineEndsAtSongEnd() {
        // 最后一句没有下一句：结束取歌曲长度，加 1 秒不能超过
        val range = shareClipRange(
            starts = listOf(20_000L, 170_000L),
            selected = setOf(1),
            durationMs = 180_000L,
            reliableTiming = true,
        )
        assertEquals(169_000L, range?.startMs)
        assertEquals(180_000L, range?.endMs)
    }

    @Test
    fun nonAdjacentLinesTakeTheWholeSpan() {
        // 选第 1、3 句：中间那句的声音保留（连续范围）
        val range = shareClipRange(
            starts = listOf(10_000L, 20_000L, 30_000L, 40_000L),
            selected = setOf(0, 2),
            durationMs = 200_000L,
            reliableTiming = true,
        )
        assertEquals(9_000L, range?.startMs)
        assertEquals(41_000L, range?.endMs)
    }

    @Test
    fun selectionOrderDoesNotMatter() {
        val forward = shareClipRange(listOf(10_000L, 30_000L), setOf(0, 1), 200_000L, true)
        val backward = shareClipRange(listOf(10_000L, 30_000L), setOf(1, 0), 200_000L, true)
        assertEquals(forward, backward)
    }

    @Test
    fun unavailableWithoutReliableTiming() {
        // 未同步歌词：只能整曲分享，不能猜范围
        assertNull(shareClipRange(listOf(60_000L), setOf(0), 180_000L, reliableTiming = false))
    }

    @Test
    fun unavailableWithoutSelection() {
        assertNull(shareClipRange(listOf(60_000L), emptySet(), 180_000L, reliableTiming = true))
    }

    @Test
    fun unavailableWithoutDuration() {
        assertNull(shareClipRange(listOf(60_000L), setOf(0), durationMs = 0L, reliableTiming = true))
    }

    @Test
    fun unavailableForOutOfRangeLine() {
        // 行号越界不能静默按 0 处理
        assertNull(shareClipRange(listOf(60_000L), setOf(5), 180_000L, reliableTiming = true))
        assertNull(shareClipRange(listOf(60_000L), setOf(-1), 180_000L, reliableTiming = true))
    }

    @Test
    fun durationIsDerivedFromRange() {
        val range = shareClipRange(listOf(60_000L, 90_000L), setOf(0), 180_000L, reliableTiming = true)
        assertEquals(32_000L, range?.durationMs)
    }
}
