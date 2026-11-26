package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.CrossfadeCoordinator
import com.mytv.mymusic.playback.core.CrossfadeHost
import com.mytv.mymusic.playback.core.CrossfadeReport
import com.mytv.mymusic.playback.core.CrossfadeSettings
import com.mytv.mymusic.playback.core.CrossfadeTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把 [CrossfadeCoordinator]（纯状态机）接到一个协程循环上。
 *
 * Android 用 `Handler.postDelayed` 每 200ms 看一眼（淡化中 50ms 推一次音量）；
 * 桌面侧用同样的节奏跑在播放引擎的 scope 上，区别只是"时钟"换成协程调度器。
 */
class VlcCrossfade(host: CrossfadeHost, private val scope: CoroutineScope) : CrossfadeControl {
    private val coordinator = CrossfadeCoordinator(host)
    private var ticker: Job? = null

    override val settings: StateFlow<CrossfadeSettings> get() = coordinator.settings
    override val report: StateFlow<CrossfadeReport?> get() = coordinator.report
    override val transition: StateFlow<CrossfadeTransition?> get() = coordinator.transition

    /** 是否正在淡化：引擎据此判断"这一首的结尾已经交给过渡了，别再自动切歌"。 */
    val isFading: Boolean get() = coordinator.transition.value != null

    override fun configure(settings: CrossfadeSettings) {
        coordinator.configure(settings)
        PlaybackLog.info(
            "Crossfade",
            "设置 开关=${settings.enabled} 时长=${settings.seconds}s 方式=${settings.mode.label}",
        )
    }

    override fun tick() = coordinator.tick()

    override fun tickIntervalMs(): Long = coordinator.tickIntervalMs()

    override fun cancel(reason: String) = coordinator.cancel(reason)

    override fun release() {
        stop()
        coordinator.release()
    }

    /** 开始播放时调一次；重复调用无副作用。 */
    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                runCatching { coordinator.tick() }
                    .onFailure { PlaybackLog.error("Crossfade", "检查失败：${it.stackTraceToString()}") }
                delay(coordinator.tickIntervalMs())
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }
}
