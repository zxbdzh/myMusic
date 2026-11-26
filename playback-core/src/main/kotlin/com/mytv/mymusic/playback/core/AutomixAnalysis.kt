package com.mytv.mymusic.playback.core

import kotlin.math.abs

/**
 * 拍子网格，不可变：第 [k0] 拍在 [t0S] 秒（媒体时间），每拍 [periodS] 秒。
 * [periodS] 为 0 表示还没有网格；[confidence] 不到 0.5 时界面不按网格起拍。
 * 拍号对 4 取余等于 [downbeat] 的是小节首拍；[downbeatClear] 为 false 时四拍一样重
 * （四拍底鼓），首拍只是每四拍算一次。
 *
 * 从 Android 的 `playback/BeatTracker.kt` 搬来（只留数据部分，跟踪算法还在 :app，
 * 桌面侧后续按 ADR 的 B4 批次跟进）。
 */
class BeatGrid(
    val periodS: Double,
    val t0S: Double,
    val k0: Long,
    val confidence: Float,
    val downbeat: Int,
    val downbeatClear: Boolean,
    private val strengthIndex: LongArray = LongArray(0),
    private val strengthValue: FloatArray = FloatArray(0),
) {
    val bpm: Double get() = if (periodS > 0.0) 60.0 / periodS else 0.0

    /** 第 [index] 拍附近低频起拍的相对强度 0..1；还没量过时返回 NaN。 */
    fun strength(index: Long): Float {
        for (i in strengthIndex.indices.reversed()) if (strengthIndex[i] == index) return strengthValue[i]
        return Float.NaN
    }

    /** 拍号对 4 的位置是否是小节首拍。 */
    fun isDownbeat(index: Long): Boolean = Math.floorMod(index - downbeat, 4L) == 0L

    companion object {
        val NONE = BeatGrid(0.0, 0.0, 0L, 0f, 0, false)
    }
}

/** 段落：从 [startMs] 开始的一段，能量 0..1（相对整首最响的一段）。 */
data class AutomixSection(val startMs: Long, val energy: Float)

/**
 * 切歌过渡需要的离线分析结果。拍网格复用界面上那套 [BeatGrid]，判断接法时和实时跟踪同一套坐标。
 * 一首歌只分析一次，结果按曲目 key 存在 Room 里（Android）；桌面侧先只看内存缓存。
 */
data class AutomixAnalysis(
    val songKey: String,
    val durationMs: Long,
    val beatGrid: BeatGrid,
    /** 整首的均方根响度（dBFS，满刻度 0）。 */
    val loudnessDb: Float,
    /** 0–11，-1 表示没算出来。 */
    val keyIndex: Int,
    val keyMinor: Boolean,
    val keyConfidence: Float,
    /** 第一个 / 最后一个有效声音的位置。 */
    val introMs: Long,
    val outroMs: Long,
    val sections: List<AutomixSection> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val bpm: Double get() = beatGrid.bpm

    companion object {
        private const val MATCH_TOLERANCE_MS = 2_000L

        /** 曲目 key + 时长对上才算这份结果还能用：换源到另一个版本（时长不同）要重新分析。 */
        fun matches(cached: AutomixAnalysis?, songKey: String, durationMs: Long): Boolean =
            cached != null &&
                cached.songKey == songKey &&
                (durationMs <= 0L || cached.durationMs <= 0L || abs(cached.durationMs - durationMs) <= MATCH_TOLERANCE_MS)
    }
}

/** 分析结果的内存缓存：播放侧在主线程上同步取，不做 IO（Room 那份留在 :app）。 */
class AutomixAnalysisStore {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, AutomixAnalysis>()

    fun cached(songKey: String): AutomixAnalysis? = memory[songKey]

    fun get(songKey: String, durationMs: Long = 0L): AutomixAnalysis? =
        memory[songKey]?.takeIf { AutomixAnalysis.matches(it, songKey, durationMs) }

    fun put(analysis: AutomixAnalysis) {
        memory[analysis.songKey] = analysis
    }

    fun clear() = memory.clear()
}
