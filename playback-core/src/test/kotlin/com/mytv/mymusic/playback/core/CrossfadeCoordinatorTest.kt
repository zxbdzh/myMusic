package com.mytv.mymusic.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交叉淡化状态机：什么时候预载、什么时候开始淡、淡到一半被暂停 / 睡眠定时 / 预载失败怎么退。
 * 用假播放器驱动，不起 native 库，所以能在毫秒级跑完整条时间线。
 */
class CrossfadeCoordinatorTest {
    private class FakeHost : CrossfadeHost {
        var now = 0L
        var duration = 200_000L
        var position = 190_000L
        var primaryIdx = 0
        var playing = true
        var playWhenReady = true
        var sleepActive = false
        var allowPrepare = true
        var incomingReadyNow = false
        var startedIncoming = false
        var preparedIndex = -1
        var preparedStart = 0L
        var primaryVol = 1f
        var incomingVol = 1f
        var rate = 1f
        var promoted = 0
        var discarded = 0
        val logs = mutableListOf<String>()

        override fun nowMs() = now

        override fun primaryTitle() = "《上一首》"

        override fun primaryPlaying() = playing

        override fun primaryPlayWhenReady() = playWhenReady

        override fun primaryPositionMs() = position

        override fun primaryDurationMs() = duration

        override fun primaryIndex() = primaryIdx

        override fun hasNext() = true

        override fun nextIndex() = primaryIdx + 1

        override fun trackKey(index: Int) = "key-$index"

        override fun trackTitle(index: Int) = "《第 $index 首》"

        override fun prepareIncoming(index: Int, startMs: Long): Boolean {
            if (!allowPrepare) return false
            preparedIndex = index
            preparedStart = startMs
            return true
        }

        override fun incomingReady() = incomingReadyNow

        override fun seekIncoming(index: Int, startMs: Long) {
            preparedStart = startMs
        }

        override fun startIncoming() {
            startedIncoming = true
        }

        override fun setPrimaryVolume(volume: Float) {
            primaryVol = volume
        }

        override fun setIncomingVolume(volume: Float) {
            incomingVol = volume
        }

        override fun setPrimarySpeed(speed: Float) {
            rate = speed
        }

        override fun promoteIncoming() {
            promoted++
            primaryIdx++
            preparedIndex = -1
            incomingVol = 1f
            primaryVol = 1f
        }

        override fun discardIncoming() {
            discarded++
            preparedIndex = -1
        }

        override fun sleepTimerActive() = sleepActive

        override fun analysisFor(trackKey: String): AutomixAnalysis? = null

        override fun log(message: String) {
            logs += message
        }
    }

    private fun coordinator(
        host: FakeHost,
        settings: CrossfadeSettings = CrossfadeSettings(enabled = true, seconds = 6, mode = CrossfadeMode.Fade),
    ) = CrossfadeCoordinator(host).apply { configure(settings) }

    @Test
    fun `提前预载并在最后几秒开始淡化`() {
        val host = FakeHost()
        val crossfade = coordinator(host)

        // 还差 10 秒：进预载窗口，先预载下一首
        crossfade.tick()
        assertEquals(1, host.preparedIndex)
        assertFalse(host.startedIncoming)
        assertEquals(200L, crossfade.tickIntervalMs())

        // 预载好了但还没到开始淡的时候
        host.incomingReadyNow = true
        crossfade.tick()
        assertFalse(host.startedIncoming)

        // 还差 5 秒：开始淡，长度取 5 秒 - 250ms 尾巴
        host.position = 195_000L
        crossfade.tick()
        assertTrue(host.startedIncoming)
        assertEquals(50L, crossfade.tickIntervalMs())
        assertTrue(host.logs.any { it.startsWith("开始等功率淡化") })
    }

    @Test
    fun `淡到一半两路各 0_707`() {
        val host = FakeHost()
        val crossfade = coordinator(host)
        crossfade.tick()
        host.incomingReadyNow = true
        host.position = 195_000L
        crossfade.tick()

        val startedAt = host.now
        host.now = startedAt + 2_375L // 4.75 秒的一半
        crossfade.tick()

        assertEquals(0.707f, host.primaryVol, 1e-2f)
        assertEquals(0.707f, host.incomingVol, 1e-2f)
    }

    @Test
    fun `淡完换到下一首并留下报告`() {
        val host = FakeHost()
        val crossfade = coordinator(host)
        crossfade.tick()
        host.incomingReadyNow = true
        host.position = 195_000L
        crossfade.tick()

        host.now += 5_000L
        crossfade.tick()

        assertEquals(1, host.promoted)
        assertEquals(1, host.primaryIdx)
        assertEquals(1f, host.primaryVol, 1e-6f)
        assertNotNull(crossfade.report.value)
        val report = crossfade.report.value!!
        assertEquals("等功率淡化", report.method)
        assertEquals(4.75f, report.seconds, 1e-3f)
        // 淡化结束不再画过渡动画
        assertNull(crossfade.transition.value)
    }

    @Test
    fun `睡眠定时优先于交叉淡化`() {
        val host = FakeHost()
        val crossfade = coordinator(host)

        crossfade.tick()
        host.sleepActive = true
        crossfade.tick()

        assertEquals(1, host.discarded)
        assertEquals(1f, host.primaryVol, 1e-6f)
        // 只预载了、还没开始淡：不算一次接歌，所以不留报告，只留日志
        assertNull(crossfade.report.value)
        assertTrue(host.logs.any { it.contains("睡眠定时优先") })
    }

    @Test
    fun `下一首不能过渡就直接硬切并不再重试`() {
        val host = FakeHost()
        host.allowPrepare = false
        val crossfade = coordinator(host)

        crossfade.tick()
        assertEquals("下一首不能过渡", crossfade.report.value?.fallbackReason)

        // 第二次 tick 不再重试预载（每 200ms 重试一次既费 CPU 又刷日志）
        val logs = host.logs.size
        crossfade.tick()
        assertEquals(logs, host.logs.size)
    }

    @Test
    fun `主播放器暂停就收手`() {
        val host = FakeHost()
        val crossfade = coordinator(host)
        crossfade.tick()
        host.incomingReadyNow = true
        host.position = 195_000L
        crossfade.tick()

        // 用户按了暂停：恢复原音量、丢掉预载
        host.playing = false
        host.playWhenReady = false
        crossfade.tick()

        assertEquals(1f, host.primaryVol, 1e-6f)
        assertEquals(1, host.discarded)
        assertEquals("主播放器暂停了", crossfade.report.value?.fallbackReason)
    }

    @Test
    fun `关掉总开关立即收手`() {
        val host = FakeHost()
        val crossfade = coordinator(host)
        crossfade.tick()

        crossfade.configure(CrossfadeSettings(enabled = false, seconds = 6, mode = CrossfadeMode.Fade))
        assertEquals(1, host.discarded)
        assertNull(crossfade.report.value)
        assertTrue(host.logs.any { it.contains("总开关关掉了") })
    }

    @Test
    fun `不过渡模式什么都不做`() {
        val host = FakeHost()
        val crossfade = coordinator(host, CrossfadeSettings(enabled = true, seconds = 6, mode = CrossfadeMode.None))

        crossfade.tick()
        assertEquals(-1, host.preparedIndex)
        assertNull(crossfade.report.value)
    }

    @Test
    fun `智能过渡会画过渡动画`() {
        val host = FakeHost()
        val crossfade = coordinator(host, CrossfadeSettings(enabled = true, seconds = 6, mode = CrossfadeMode.Automix))
        crossfade.tick()
        host.incomingReadyNow = true
        host.position = 195_000L
        crossfade.tick()

        assertNotNull(crossfade.transition.value)
        val transition = crossfade.transition.value!!
        assertEquals("等功率淡化", transition.method)
        assertTrue(transition.progressAt(host.now) < 0.01f)
        assertEquals(1f, transition.progressAt(host.now + 10_000L), 1e-6f)
    }

    @Test
    fun `切歌后不会把上一首的尾巴再放一遍`() {
        val host = FakeHost()
        val crossfade = coordinator(host)
        crossfade.tick()
        host.incomingReadyNow = true
        host.position = 195_000L
        crossfade.tick()

        // 主播放器自己先切换了（媒体项下标变了）：这次过渡作废
        host.primaryIdx = 1
        crossfade.tick()
        assertEquals("已经切到下一首", crossfade.report.value?.fallbackReason)
    }
}
