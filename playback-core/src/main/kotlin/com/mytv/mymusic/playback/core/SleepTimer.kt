package com.mytv.mymusic.playback.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepTimerState(
    val active: Boolean = false,
    /** 结束时间（墙钟毫秒；界面只显示剩余分秒，和进程运行时长无关）。 */
    val endAt: Long = 0L,
    val totalMs: Long = 0L,
    val endOfTrack: Boolean = false,
    val fade: Boolean = true,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (endAt - now).coerceAtLeast(0L)
}

/**
 * 定时关闭要控制的那一头。Android 侧接 `ExoPlayer`（pause / volume / pauseAtEndOfMediaItems），
 * 桌面侧接 [PlaybackEngine]（libVLC 没有"放完暂停"，由引擎在 finished 事件里自己停）。
 *
 * 抽成接口而不是直接吃播放器：定时逻辑要能在纯 JVM 单测里用假目标跑完 30 秒的淡出，
 * 不然只能靠真放歌等一分钟。
 */
interface SleepTarget {
    fun onSleepPause()

    fun onSleepVolume(volume: Float)

    /** 播完当前歌曲再停：只有引擎知道"当前歌曲放完了"。 */
    fun onSleepAfterTrack(enabled: Boolean)
}

/**
 * 睡眠定时。由播放服务绑定真实的播放器，所以应用退到后台、锁屏后照样计时。
 * 结束前 30 秒线性降低音量，到点暂停并恢复音量。
 *
 * 时间源和协程作用域都可注入：单测用虚拟时间跑完整个淡出，生产用墙钟 + 播放服务的 scope。
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fadeMs: Long = FADE_MS,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var target: SleepTarget? = null
    private var job: Job? = null

    fun attach(target: SleepTarget) {
        this.target = target
    }

    fun detach() {
        cancel()
        target = null
    }

    fun start(minutes: Int, fade: Boolean = true) {
        cancel()
        val total = minutes * 60_000L
        val endAt = clock() + total
        _state.value = SleepTimerState(active = true, endAt = endAt, totalMs = total, fade = fade)
        job = scope.launch {
            while (true) {
                val left = endAt - clock()
                if (left <= 0) break
                // 离结束还有 30 秒起，每 200ms 推一次音量；曲线听不出台阶
                if (fade && left <= fadeMs) {
                    target?.onSleepVolume((left.toFloat() / fadeMs).coerceIn(0f, 1f))
                }
                delay(if (left <= fadeMs) FADE_STEP_MS else TICK_MS)
            }
            target?.onSleepPause()
            target?.onSleepVolume(1f)
            _state.value = SleepTimerState()
        }
    }

    /** 播完当前歌曲再停：交给引擎的"每首放完"事件。 */
    fun stopAfterCurrent() {
        cancel()
        target?.onSleepAfterTrack(true)
        _state.value = SleepTimerState(active = true, endOfTrack = true)
    }

    /** 引擎报告一首放完；只有 [SleepTimerState.endOfTrack] 才真的停。 */
    fun onTrackEnded() {
        if (!_state.value.endOfTrack) return
        target?.onSleepAfterTrack(false)
        _state.value = SleepTimerState()
    }

    fun cancel() {
        job?.cancel()
        job = null
        target?.onSleepVolume(1f)
        target?.onSleepAfterTrack(false)
        _state.value = SleepTimerState()
    }

    private companion object {
        const val FADE_MS = 30_000L
        const val TICK_MS = 1_000L
        const val FADE_STEP_MS = 200L
    }
}
