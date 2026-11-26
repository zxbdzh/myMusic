package com.mytv.mymusic.playback.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 方式：先分析再挑接法、固定长度的等功率淡化，或者干脆不过渡（和 Android 的 `data.CrossfadeMode` 同名）。 */
enum class CrossfadeMode(val label: String) {
    Automix("智能过渡"),
    Fade("交叉淡化"),
    None("不过渡"),
}

data class CrossfadeSettings(
    val enabled: Boolean = false,
    val seconds: Int = 6,
    val mode: CrossfadeMode = CrossfadeMode.Automix,
)

/**
 * 等功率交叉淡化的两条音量曲线：功率恒定，叠在一起时总响度不会在中间塌下去。
 * [t] 0 → 只有上一首，1 → 只有下一首。
 */
fun crossfadeGains(t: Float): Pair<Float, Float> {
    val p = t.coerceIn(0f, 1f)
    return cos(p * (PI / 2)).toFloat() to sin(p * (PI / 2)).toFloat()
}

/**
 * 上一次自动接歌的结果：用了哪种接法、多长，或者为什么退回直接切。
 * 播放服务在切完 / 放弃过渡时写，设置页读——参考文档里「上一次接歌」那一行。
 */
data class CrossfadeReport(
    val fromTitle: String,
    val toTitle: String,
    /** 例如「等功率淡化」「直接切」；后续接了更多接法会换成「切拍」「低频交接」等。 */
    val method: String,
    val seconds: Float,
    /** 退回直接切的原因；没有退回时为 null。 */
    val fallbackReason: String? = null,
) {
    val headline: String
        get() = if (fallbackReason != null) "已退回直接切" else "$method · ${"%.1f".format(seconds)} 秒"

    val detail: String
        get() = buildString {
            if (fromTitle.isNotBlank() || toTitle.isNotBlank()) {
                append('《').append(fromTitle).append("》→《").append(toTitle).append('》')
            }
            fallbackReason?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
        }
}

/**
 * 正在淡化的这一次接歌：播放页据此画过渡动画。进度按 [startedAtMs] 和真实时间算，
 * 不另推帧——动画只是把已经定好的混音画出来。
 */
data class CrossfadeTransition(
    val fromTitle: String,
    val toTitle: String,
    val method: String,
    val durationMs: Long,
    /** 时钟起点（生产是 System.currentTimeMillis，单测是假时钟）。 */
    val startedAtMs: Long,
) {
    /** [t] 为当前时间；0 到 1。 */
    fun progressAt(t: Long): Float =
        if (durationMs <= 0L) 1f else ((t - startedAtMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * 交叉淡化的对外契约。UI 只读 [settings] / [report] / [transition]，
 * 引擎每 [tickIntervalMs] 毫秒调一次 [tick]（Android 用 Handler，桌面用协程循环）。
 */
interface CrossfadeControl {
    val settings: StateFlow<CrossfadeSettings>
    val report: StateFlow<CrossfadeReport?>
    val transition: StateFlow<CrossfadeTransition?>

    fun configure(settings: CrossfadeSettings)

    fun tick()

    /** 两次 tick 的间隔：淡化中 50ms 推一次音量，平时只是看一眼还差多久。 */
    fun tickIntervalMs(): Long

    fun cancel(reason: String)

    fun release()
}

/**
 * 交叉淡化要操作的那一头。Android 侧是两个 `ExoPlayer`（主 + 预载），
 * 桌面侧是两个 `MediaPlayer`（libVLC 双实例），所以状态机只依赖这组原语。
 */
interface CrossfadeHost {
    fun nowMs(): Long

    fun primaryTitle(): String

    fun primaryPlaying(): Boolean

    fun primaryPlayWhenReady(): Boolean

    fun primaryPositionMs(): Long

    fun primaryDurationMs(): Long

    fun primaryIndex(): Int

    fun hasNext(): Boolean

    fun nextIndex(): Int

    fun trackKey(index: Int): String

    fun trackTitle(index: Int): String

    /**
     * 预载下一首（seek 到 [startMs]、音量归零）。返回 false 表示这一首不可过渡
     * （没有地址 / 直播流 / 加密整曲），状态机据此直接硬切。
     */
    fun prepareIncoming(index: Int, startMs: Long): Boolean

    /** 预载的那一路是否已经 STATE_READY。 */
    fun incomingReady(): Boolean

    fun seekIncoming(index: Int, startMs: Long)

    fun startIncoming()

    fun setPrimaryVolume(volume: Float)

    fun setIncomingVolume(volume: Float)

    fun setPrimarySpeed(speed: Float)

    /** 淡完：把会话整体换到预载的那一路（实现里要把新主音量设回 1.0）。 */
    fun promoteIncoming()

    fun discardIncoming()

    fun sleepTimerActive(): Boolean

    fun analysisFor(trackKey: String): AutomixAnalysis?

    fun log(message: String)

    /**
     * 低频交接时把上一首低通往下压、下一首高通往上开。
     * libVLC 的均衡器只有 10 段固定频点，做不了动态高/低通，桌面实现留空（只记日志）。
     */
    fun applyBassHandoff(cutoffHz: Float, progress: Float) {}
}

/**
 * 自动接下一首时的交叉淡化状态机（从 Android 的 `playback/CrossfadeController.kt` 搬来，
 * 去掉 Handler / ExoPlayer / 设置存储，只留"什么时候预载、什么时候开始淡、淡不下去怎么办"）。
 *
 * 在原播放器还在放上一首结尾时，另起一路预载并播放下一首，两路各按等功率曲线淡出 / 淡入；
 * 淡完把会话整体换到新的一路（见 [CrossfadeHost.promoteIncoming]）。
 *
 * 只处理自动衔接。手动切歌、直播、带 DRM 的整曲、队列编辑、暂停和睡眠定时都不接，直接取消或根本不开始。
 */
class CrossfadeCoordinator(
    private val host: CrossfadeHost,
    private val reportSink: MutableStateFlow<CrossfadeReport?> = MutableStateFlow(null),
    private val transitionSink: MutableStateFlow<CrossfadeTransition?> = MutableStateFlow(null),
) : CrossfadeControl {
    private val _settings = MutableStateFlow(CrossfadeSettings())
    override val settings: StateFlow<CrossfadeSettings> = _settings.asStateFlow()

    override val report: StateFlow<CrossfadeReport?> = reportSink.asStateFlow()
    override val transition: StateFlow<CrossfadeTransition?> = transitionSink.asStateFlow()

    private var preparedIndex = INDEX_UNSET
    private var preparedStartMs = 0L

    private var fade: Fade? = null

    /** 预载时定下的接法；分析结果没赶上就是等功率淡化。 */
    private var plan: TransitionPlan? = null

    /** 这一首已经试过、放弃过渡了：不要每 200ms 重新预载一次。 */
    private var skipForIndex = INDEX_UNSET

    /** 上一首因为条件不满足被跳过时的下标，只用来少打点日志。 */
    private var lastIneligible = INDEX_UNSET

    private class Fade(
        val startAtMs: Long,
        val durationMs: Long,
        val index: Int,
        val plan: TransitionPlan,
        val fromTitle: String,
        val toTitle: String,
    )

    override fun configure(settings: CrossfadeSettings) {
        val previous = _settings.value
        _settings.value = settings
        val changed = settings.mode != previous.mode
        when {
            !settings.enabled -> cancel("总开关关掉了")
            // 方式换了：正在淡的这次立刻收手，别一半等功率一半智能过渡
            changed && (fade != null || preparedIndex != INDEX_UNSET) -> cancel("方式换了")
        }
    }

    override fun tickIntervalMs(): Long = if (fade != null) RAMP_MS else TICK_MS

    override fun tick() {
        val config = _settings.value
        // 不过渡：整首放完由播放器自己接下一首，不预载也不叠
        if (!config.enabled || config.mode == CrossfadeMode.None) return
        if (!host.primaryPlaying()) {
            val active = fade
            if (active == null) return
            // 淡着淡着缓冲 / 放完了：只要不是用户按了暂停就接着推完，别把下一首从 0 再放一遍
            if (host.primaryPlayWhenReady()) advance(active) else cancel("主播放器暂停了")
            return
        }
        if (host.sleepTimerActive()) {
            cancel("睡眠定时优先")
            return
        }
        val duration = host.primaryDurationMs()
        if (duration <= 0L) return
        val next = host.nextIndex()
        if (!host.hasNext() || next == INDEX_UNSET || next == host.primaryIndex()) return
        val remaining = duration - host.primaryPositionMs()
        if (remaining <= 0L) return

        val active = fade
        if (active != null) {
            advance(active)
            return
        }

        val candidate = planFor(next)
        val leadMs = candidate?.startAtRemainingMs ?: (config.seconds * 1000L)
        if (remaining > leadMs + PREPARE_AHEAD_MS) {
            releasePrepared()
            plan = null
            return
        }
        if (preparedIndex != next) {
            releasePrepared()
            if (next == skipForIndex) return
            val startMs = candidate?.incomingStartMs ?: 0L
            if (!host.prepareIncoming(next, startMs)) {
                skipForIndex = next
                reportFallback(next, "下一首不能过渡")
                return
            }
            plan = candidate
            preparedIndex = next
            preparedStartMs = startMs
            host.log("预载下一首 index=$next，接法 ${plan?.method?.label ?: "等功率淡化"}")
            return
        }
        if (remaining > leadMs) return
        // 预载和分析是并行的：到这里再取一次接法，分析刚赶上就直接用，不沿用预载时的判断
        val current = planFor(next) ?: plan
        val playerReady = host.incomingReady()
        val wanted = min(current?.fadeMs ?: leadMs, remaining - TAIL_MARGIN_MS)
        val minimum = if (current?.method == AutomixMethod.BeatCut) BEAT_CUT_MIN_MS else MIN_FADE_MS
        if (!playerReady || wanted < minimum) {
            // 预载来不及：这一首直接硬切，不再反复尝试
            skipForIndex = next
            cancel("下一首还没准备好")
            return
        }
        val target = current ?: equalPowerPlan(config.seconds)
        if (target.incomingStartMs != preparedStartMs) {
            host.seekIncoming(next, target.incomingStartMs)
            preparedStartMs = target.incomingStartMs
        }
        plan = target
        startFade(target, wanted)
    }

    override fun cancel(reason: String) {
        val fading = fade
        val hadPrepared = preparedIndex != INDEX_UNSET
        val toTitle = if (fading != null) fading.toTitle else ""
        fade = null
        plan = null
        preparedIndex = INDEX_UNSET
        if (fading != null) {
            host.setPrimaryVolume(1f)
            host.setPrimarySpeed(1f)
            transitionSink.value = null
        }
        if (fading != null || hadPrepared) {
            host.discardIncoming()
            host.log("放弃过渡：$reason")
        }
        if (fading != null) {
            reportSink.value = CrossfadeReport(
                fromTitle = fading.fromTitle,
                toTitle = toTitle,
                method = "直接切",
                seconds = 0f,
                fallbackReason = reason,
            )
        }
    }

    override fun release() {
        cancel("释放")
    }

    /** 当前两首都分析过就按分析挑接法；没有分析结果时等功率淡化。 */
    private fun planFor(nextIndex: Int): TransitionPlan? {
        val config = _settings.value
        if (config.mode == CrossfadeMode.None) return null
        if (config.mode == CrossfadeMode.Fade) return equalPowerPlan(config.seconds)
        val duration = host.primaryDurationMs()
        if (duration <= 0L) return null
        val outgoing = host.analysisFor(host.trackKey(host.primaryIndex()))
        val incoming = host.analysisFor(host.trackKey(nextIndex))
        if (outgoing == null && incoming == null) return equalPowerPlan(config.seconds)
        return AutomixPlanner.plan(outgoing, incoming, config.seconds, duration)
    }

    private fun equalPowerPlan(seconds: Int): TransitionPlan = TransitionPlan(
        method = AutomixMethod.EqualPowerFade,
        startAtRemainingMs = seconds * 1000L,
        fadeMs = seconds * 1000L,
        incomingStartMs = 0L,
        outgoingSpeed = 1f,
        bassCutoffHz = 0f,
        echoMix = 0f,
    )

    private fun startFade(plan: TransitionPlan, durationMs: Long) {
        val config = _settings.value
        val fromTitle = host.primaryTitle()
        val toTitle = host.trackTitle(preparedIndex)
        host.setIncomingVolume(0f)
        if (plan.outgoingSpeed != 1f) host.setPrimarySpeed(plan.outgoingSpeed)
        host.startIncoming()
        val startedAt = host.nowMs()
        fade = Fade(startedAt, durationMs, host.primaryIndex(), plan, fromTitle, toTitle)
        // 固定交叉淡化不画动画，只有智能过渡才让播放页把这次接歌画出来
        if (config.mode == CrossfadeMode.Automix) {
            transitionSink.value = CrossfadeTransition(
                fromTitle = fromTitle,
                toTitle = toTitle,
                method = plan.method.label,
                durationMs = durationMs,
                startedAtMs = startedAt,
            )
        }
        host.log("开始${plan.method.label} ${durationMs}ms，原速 ${"%.3f".format(plan.outgoingSpeed)}")
    }

    private fun advance(fading: Fade) {
        if (host.primaryIndex() != fading.index) {
            cancel("已经切到下一首")
            return
        }
        val t = ((host.nowMs() - fading.startAtMs).toFloat() / fading.durationMs).coerceIn(0f, 1f)
        val gains = crossfadeGains(t)
        host.setPrimaryVolume(gains.first)
        host.setIncomingVolume(gains.second)
        if (fading.plan.bassCutoffHz > 0f) {
            // 上一首低通往下压、下一首高通往上开，低频在中间交接
            host.applyBassHandoff(fading.plan.bassCutoffHz, t)
        }
        if (t >= 1f) {
            reportSink.value = CrossfadeReport(
                fromTitle = fading.fromTitle,
                toTitle = fading.toTitle,
                method = fading.plan.method.label,
                seconds = fading.durationMs / 1000f,
            )
            promote(fading)
        }
    }

    private fun promote(fading: Fade) {
        transitionSink.value = null
        fade = null
        plan = null
        preparedIndex = INDEX_UNSET
        skipForIndex = INDEX_UNSET
        host.setPrimarySpeed(1f)
        host.promoteIncoming()
        host.log("切换到下一首 index=${fading.index + 1}")
    }

    private fun releasePrepared() {
        if (preparedIndex == INDEX_UNSET) return
        preparedIndex = INDEX_UNSET
        host.discardIncoming()
    }

    private fun reportFallback(nextIndex: Int, reason: String) {
        if (lastIneligible == nextIndex) return
        lastIneligible = nextIndex
        host.log("跳过过渡：$reason")
        reportSink.value = CrossfadeReport(
            fromTitle = host.primaryTitle(),
            toTitle = host.trackTitle(nextIndex),
            method = "直接切",
            seconds = 0f,
            fallbackReason = reason,
        )
    }

    private companion object {
        const val INDEX_UNSET = -1
        const val TICK_MS = 200L
        const val RAMP_MS = 50L

        /** 提前这么久预载下一首，缓冲慢的在线歌曲也来得及。 */
        const val PREPARE_AHEAD_MS = 12_000L
        const val MIN_FADE_MS = 1_200L

        /** 切拍只叠很短一下，下限比普通淡化低。 */
        const val BEAT_CUT_MIN_MS = 400L

        /** 不把上一首最后这一点吃掉：淡完立刻换主，尾巴本来也已经听不见了。 */
        const val TAIL_MARGIN_MS = 250L
    }
}

/** 便于桌面实现按 dB 换算动态滤波频率（Android 侧用的是 20Hz..20kHz 的对数插值）。 */
object CrossfadeCurve {
    const val FILTER_MAX_HZ = 20_000f
    const val FILTER_MIN_HZ = 20f

    /** 上一首低通：从 [cutoffHz] 指数推到 [FILTER_MAX_HZ]。 */
    fun lowPassHz(cutoffHz: Float, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return (FILTER_MAX_HZ.toDouble() * (cutoffHz / FILTER_MAX_HZ).toDouble().pow(t.toDouble())).toFloat()
    }

    /** 下一首高通：从 [FILTER_MIN_HZ] 指数推到 [cutoffHz]。 */
    fun highPassHz(cutoffHz: Float, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return (FILTER_MIN_HZ.toDouble() * (cutoffHz / FILTER_MIN_HZ).toDouble().pow((1f - t).toDouble())).toFloat()
    }
}
