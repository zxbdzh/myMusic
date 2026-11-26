package com.mytv.mymusic.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动接歌的四种接法：切拍 / 尾奏承接 / 低频交接 / 等功率淡化，以及速度对齐的边界。 */
class AutomixPlannerTest {
    private fun analysis(
        key: String,
        durationMs: Long,
        periodS: Double,
        confidence: Float,
        downbeatClear: Boolean,
        outroMs: Long = durationMs,
        introMs: Long = 0L,
        t0S: Double = 0.0,
    ) = AutomixAnalysis(
        songKey = key,
        durationMs = durationMs,
        beatGrid = BeatGrid(
            periodS = periodS,
            t0S = t0S,
            k0 = 0L,
            confidence = confidence,
            downbeat = 0,
            downbeatClear = downbeatClear,
        ),
        loudnessDb = -12f,
        keyIndex = 0,
        keyMinor = false,
        keyConfidence = 0.5f,
        introMs = introMs,
        outroMs = outroMs,
    )

    @Test
    fun `两首都锁定拍子时在小节首拍切`() {
        val outgoing = analysis("a", 200_000L, 0.5, 0.9f, downbeatClear = true)
        val incoming = analysis("b", 200_000L, 0.5, 0.9f, downbeatClear = true)

        val plan = AutomixPlanner.plan(outgoing, incoming, configuredSeconds = 6, durationMs = 200_000L)

        assertEquals(AutomixMethod.BeatCut, plan.method)
        // 120 BPM、4/4：最后一个小节首拍在 198.0s，距结尾 2s
        assertEquals(2_000L, plan.startAtRemainingMs)
        assertEquals(1_200L, plan.fadeMs)
        assertEquals(0L, plan.incomingStartMs)
        assertEquals(1f, plan.outgoingSpeed, 1e-6f)
    }

    @Test
    fun `拍子差太多就不切拍`() {
        val outgoing = analysis("a", 200_000L, 0.5, 0.9f, downbeatClear = true) // 120 BPM
        val incoming = analysis("b", 200_000L, 1.0, 0.9f, downbeatClear = true) // 60 BPM

        val plan = AutomixPlanner.plan(outgoing, incoming, configuredSeconds = 6, durationMs = 200_000L)

        assertTrue(plan.method != AutomixMethod.BeatCut)
    }

    @Test
    fun `尾奏收得干净就自然承接`() {
        val outgoing = analysis("a", 200_000L, 0.0, 0.1f, downbeatClear = false, outroMs = 190_000L)
        val incoming = analysis("b", 200_000L, 0.0, 0.1f, downbeatClear = false, introMs = 2_000L)

        val plan = AutomixPlanner.plan(outgoing, incoming, configuredSeconds = 6, durationMs = 200_000L)

        assertEquals(AutomixMethod.OutroCarry, plan.method)
        assertEquals(8_000L, plan.startAtRemainingMs)
        assertEquals(8_000L, plan.fadeMs)
        assertEquals(0.25f, plan.echoMix, 1e-6f)
    }

    @Test
    fun `有置信度就做低频交接`() {
        val outgoing = analysis("a", 200_000L, 0.5, 0.6f, downbeatClear = false, outroMs = 199_900L)
        val incoming = analysis("b", 200_000L, 0.5, 0.4f, downbeatClear = false, introMs = 0L)

        val plan = AutomixPlanner.plan(outgoing, incoming, configuredSeconds = 6, durationMs = 200_000L)

        assertEquals(AutomixMethod.BassHandoff, plan.method)
        assertEquals(300f, plan.bassCutoffHz, 1e-6f)
    }

    @Test
    fun `没有分析结果就等功率淡化`() {
        val plan = AutomixPlanner.plan(null, null, configuredSeconds = 6, durationMs = 200_000L)

        assertEquals(AutomixMethod.EqualPowerFade, plan.method)
        assertEquals(6_000L, plan.fadeMs)
        assertEquals(1f, plan.outgoingSpeed, 1e-6f)
    }

    @Test
    fun `秒数夹在 4 到 10 秒`() {
        assertEquals(4_000L, AutomixPlanner.plan(null, null, 1, 200_000L).fadeMs)
        assertEquals(10_000L, AutomixPlanner.plan(null, null, 30, 200_000L).fadeMs)
    }

    @Test
    fun `速度差 6 趴以内对齐`() {
        val outgoing = analysis("a", 200_000L, 0.5, 0.1f, downbeatClear = false) // 120 BPM
        val incoming = analysis("b", 200_000L, 0.4854, 0.1f, downbeatClear = false) // ≈123.6 BPM

        val plan = AutomixPlanner.plan(outgoing, incoming, configuredSeconds = 6, durationMs = 200_000L)

        assertEquals(1.03f, plan.outgoingSpeed, 5e-3f)
    }

    @Test
    fun `速度差超过 6 趴就不对齐`() {
        val outgoing = analysis("a", 200_000L, 0.5, 0.1f, downbeatClear = false) // 120 BPM
        val incoming = analysis("b", 200_000L, 0.6, 0.1f, downbeatClear = false) // 100 BPM

        assertEquals(1f, AutomixPlanner.plan(outgoing, incoming, 6, 200_000L).outgoingSpeed, 1e-6f)
    }
}
