package com.mytv.mymusic.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 等功率曲线：两条增益的平方和恒为 1，听感上叠在一起不会在中间塌下去。
 * 这是交叉淡化"听得出、不难受"的数学前提，先把这条钉死。
 */
class CrossfadeCurveTest {
    @Test
    fun `两端只有一路在响`() {
        val (outStart, inStart) = crossfadeGains(0f)
        assertEquals(1f, outStart, 1e-6f)
        assertEquals(0f, inStart, 1e-6f)

        val (outEnd, inEnd) = crossfadeGains(1f)
        assertEquals(0f, outEnd, 1e-6f)
        assertEquals(1f, inEnd, 1e-6f)
    }

    @Test
    fun `中点两路都是 0_707`() {
        val (out, incoming) = crossfadeGains(0.5f)
        assertEquals(0.7071f, out, 1e-3f)
        assertEquals(0.7071f, incoming, 1e-3f)
    }

    @Test
    fun `功率恒定`() {
        for (step in 0..100) {
            val t = step / 100f
            val (out, incoming) = crossfadeGains(t)
            assertEquals(1f, out * out + incoming * incoming, 1e-5f)
        }
        // 与公式对一遍，防止实现改成线性淡入（线性叠加中点会掉 3dB）
        assertEquals(cos(0.25 * PI / 2).toFloat(), crossfadeGains(0.25f).first, 1e-6f)
        assertEquals(sin(0.75 * PI / 2).toFloat(), crossfadeGains(0.75f).second, 1e-6f)
    }

    @Test
    fun `越界夹紧`() {
        assertEquals(crossfadeGains(0f).first, crossfadeGains(-3f).first, 1e-6f)
        assertEquals(crossfadeGains(1f).second, crossfadeGains(9f).second, 1e-6f)
    }

    @Test
    fun `低频交接两端频率收敛`() {
        // 开始时上一首低通还在 20kHz（等于不压）、下一首高通从交接频率起步；
        // 结束时低通压到交接频率、高通打开到 20Hz —— 低频在这几秒里完成交接
        assertEquals(CrossfadeCurve.FILTER_MAX_HZ, CrossfadeCurve.lowPassHz(300f, 0f), 1e-3f)
        assertEquals(300f, CrossfadeCurve.lowPassHz(300f, 1f), 0.5f)
        assertEquals(300f, CrossfadeCurve.highPassHz(300f, 0f), 0.5f)
        assertEquals(CrossfadeCurve.FILTER_MIN_HZ, CrossfadeCurve.highPassHz(300f, 1f), 0.5f)
        // 单调：低通只往下压，高通只往下打开（频率越低 = 通过的低频越多）
        assertTrue(CrossfadeCurve.lowPassHz(300f, 0.3f) > CrossfadeCurve.lowPassHz(300f, 0.6f))
        assertTrue(CrossfadeCurve.highPassHz(300f, 0.3f) > CrossfadeCurve.highPassHz(300f, 0.6f))
    }

    @Test
    fun `过渡进度按起点算`() {
        val transition = CrossfadeTransition(
            fromTitle = "A",
            toTitle = "B",
            method = "等功率淡化",
            durationMs = 4_000L,
            startedAtMs = 10_000L,
        )
        assertEquals(0f, transition.progressAt(10_000L), 1e-6f)
        assertEquals(0.5f, transition.progressAt(12_000L), 1e-6f)
        assertEquals(1f, transition.progressAt(99_000L), 1e-6f)
    }
}
