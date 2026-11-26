package com.mytv.mymusic.playback.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时关闭：用虚拟时间跑完整个 1 分钟（最后 30 秒淡出）。
 * 这条逻辑以前只能靠真放歌等一分钟，现在能在单测里精确断言"第 45 秒音量是 0.5"。
 */
class SleepTimerTest {
    private class FakeTarget : SleepTarget {
        var paused = false
        var afterTrack: Boolean? = null
        val volumes = mutableListOf<Float>()

        override fun onSleepPause() {
            paused = true
        }

        override fun onSleepVolume(volume: Float) {
            volumes += volume
        }

        override fun onSleepAfterTrack(enabled: Boolean) {
            afterTrack = enabled
        }
    }

    @Test
    fun `最后一分钟里 30 秒淡出到暂停`() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, clock = { testScheduler.currentTime })
        timer.attach(target)
        timer.start(minutes = 1)

        assertTrue(timer.state.value.active)
        assertEquals(60_000L, timer.state.value.totalMs)

        // 前 30 秒只是看一眼还差多久，不动音量
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(target.paused)
        assertTrue(target.volumes.isEmpty() || target.volumes.all { it == 1f })

        // 第 45 秒：剩余 15 秒 / 30 秒 = 0.5
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(0.5f, target.volumes.last(), 0.01f)

        // 第 60 秒：暂停、恢复音量、状态归零
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(target.paused)
        assertEquals(1f, target.volumes.last(), 1e-6f)
        assertFalse(timer.state.value.active)
    }

    @Test
    fun `关掉淡出就直接到点暂停`() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, clock = { testScheduler.currentTime })
        timer.attach(target)
        timer.start(minutes = 1, fade = false)

        advanceTimeBy(59_000)
        runCurrent()
        assertFalse(target.paused)
        assertTrue(target.volumes.all { it == 1f })

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(target.paused)
    }

    @Test
    fun `播完当前歌曲再停`() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, clock = { testScheduler.currentTime })
        timer.attach(target)

        timer.stopAfterCurrent()
        assertTrue(timer.state.value.active)
        assertTrue(timer.state.value.endOfTrack)
        assertEquals(true, target.afterTrack)

        timer.onTrackEnded()
        assertFalse(timer.state.value.active)
        assertEquals(false, target.afterTrack)
    }

    @Test
    fun `没开播完停时收到播放结束不处理`() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, clock = { testScheduler.currentTime })
        timer.attach(target)

        timer.onTrackEnded()
        assertFalse(timer.state.value.active)
    }

    @Test
    fun `取消后恢复音量`() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, clock = { testScheduler.currentTime })
        timer.attach(target)
        timer.start(minutes = 2)

        advanceTimeBy(100_000)
        runCurrent()
        assertTrue(target.volumes.isNotEmpty())

        timer.cancel()
        assertEquals(1f, target.volumes.last(), 1e-6f)
        assertFalse(timer.state.value.active)
        assertEquals(false, target.afterTrack)
        // 取消后不再有任何推音量
        val count = target.volumes.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(count, target.volumes.size)
    }

    @Test
    fun `剩余时间不会小于零`() {
        val state = SleepTimerState(active = true, endAt = 1_000L, totalMs = 1_000L)
        assertEquals(0L, state.remainingMs(now = 5_000L))
        assertEquals(500L, state.remainingMs(now = 500L))
    }
}
