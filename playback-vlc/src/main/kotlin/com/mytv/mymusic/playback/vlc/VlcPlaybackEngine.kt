package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.AutomixAnalysis
import com.mytv.mymusic.playback.core.AutomixAnalysisStore
import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.CrossfadeHost
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.PlaybackCacheConfig
import com.mytv.mymusic.playback.core.PlaybackEngine
import com.mytv.mymusic.playback.core.PlaybackQueue
import com.mytv.mymusic.playback.core.PlaybackSnapshot
import com.mytv.mymusic.playback.core.PlaybackSource
import com.mytv.mymusic.playback.core.PlaybackState
import com.mytv.mymusic.playback.core.QueueController
import com.mytv.mymusic.playback.core.QueueStore
import com.mytv.mymusic.playback.core.SleepTarget
import com.mytv.mymusic.playback.core.SleepTimer
import com.mytv.mymusic.playback.core.VolumeState
import com.mytv.mymusic.playback.core.toPlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import kotlin.math.roundToInt

/**
 * libVLC 版播放引擎：`PlaybackEngine` 的唯一桌面实现。
 *
 * 分工（见 docs/pc/ARCHITECTURE.md「播放后端」）：
 *  - **状态机**：队列 / 交叉淡化 / 定时 / 响度归一化全在 `:playback-core` 里，与播放器无关；
 *  - **native 这一层**：本类只做"把状态机的要求翻译成 vlcj 调用"，外加事件回填 [snapshot]；
 *  - **均衡器**：[VlcEqualizer]（libVLC 10 段）；**交叉淡化**：[VlcCrossfade]（双实例）；
 *  - **缓存**：`:file-caching` / `:network-caching` / `:live-caching` 由 [PlaybackCacheConfig] 生成。
 *
 * 线程约定：vlcj 的对象都不是线程安全的，所有调用都从同一批线程进来（UI/CLI），
 * 状态更新走 `MutableStateFlow`，事件回调在 libVLC 自己的线程上只写 StateFlow。
 *
 * native 库按 [VlcOptions] 的发现顺序加载（显式参数 → 环境变量 → 安装目录 → `jna.library.path`），
 * 并且**延迟到第一次 prepare**：只想看曲库的窗口不该要求本机装了 VLC。
 */
class VlcPlaybackEngine(
    val options: VlcOptions = VlcOptions.discover(),
    override val cache: PlaybackCacheConfig = options.cacheConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PlaybackEngine, SleepTarget, CrossfadeHost {
    private val snapshots = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = snapshots.asStateFlow()

    private val volumes = MutableStateFlow(VolumeState())
    override val volumeState: StateFlow<VolumeState> = volumes.asStateFlow()

    override val queue: PlaybackQueue = QueueController()

    /** 续播落盘（和 Android 的 `filesDir/playback` 同格式）。 */
    val queueStore: QueueStore by lazy { QueueStore(options.stateDir()) }

    private val automix = AutomixAnalysisStore()

    private val factoryHolder = lazy { buildFactory() }
    private val factory: MediaPlayerFactory get() = factoryHolder.value

    private val equalizerBridge = lazy { VlcEqualizer(factory) }
    override val equalizer: EqualizerControl get() = equalizerBridge.value

    private val crossfadeBridge = lazy { VlcCrossfade(this, scope) }
    override val crossfade: CrossfadeControl get() = crossfadeBridge.value

    override val sleepTimer: SleepTimer = SleepTimer(scope, clock)

    private var player: MediaPlayer? = null
    private var secondary: MediaPlayer? = null

    /**
     * 上一轮淡出后的那一路播放器：停掉、音量归零后留着给下一次预载复用。
     *
     * 为什么要复用而不是当场 `release()`：libVLC 在音频输出线程还在跑的时候释放 MediaPlayer
     * 会踩到 native 内存（实测 "Invalid memory access" / `libvlc_event_detach` 挂死），
     * 而交叉淡化一定发生在"另一路正在响"的时刻。两路播放器来回换主、只在 [release] 里销毁，
     * 是 vlcj 社区对这条约束的常规解法。
     */
    private var spare: MediaPlayer? = null

    /** 预载的那一路对应队列里的第几首；淡化结束（[promoteIncoming]）时切过去。 */
    private var pendingIndex = -1

    /** 用户按过暂停（区别于"缓冲时暂时不响"），交叉淡化据此判断要不要收手。 */
    private var userPaused = false

    private var pauseAtEndOfTrack = false
    private var released = false
    private var lastHandoffPercent = -1

    /** 状态轮询协程（见 [pollState] 的说明）。 */
    private var ticker: Job? = null

    /** 上一轮看到的状态：用来把"第一次结束"和"一直结束"区分开。 */
    private var lastState: State? = null

    init {
        sleepTimer.attach(this)
    }

    /** libVLC 实际用的 native 库路径；排查"装了两份 VLC"时看这一行。 */
    val nativeLibraryPath: String
        get() = runCatching { factory.nativeLibraryPath() }.getOrDefault("")

    fun describe(): String = options.describe(
        version = runCatching { factory.application().version() }.getOrDefault(""),
    )

    /**
     * 预创建输出设备与均衡器：打开均衡器设置页时调一次，没在播放也能读到 libVLC 报的 10 段频点
     * （否则设置页只能画预览曲线，用户拖了推子才发现"没生效"）。
     */
    fun warmUp() {
        checkNotReleased()
        ensurePlayer()
    }

    // ---------------------------------------------------------------- 传输控制

    override fun prepare(source: PlaybackSource) {
        checkNotReleased()
        val target = ensurePlayer()
        lastState = null
        snapshots.update {
            it.copy(
                state = PlaybackState.Buffering,
                isBuffering = true,
                positionMs = 0,
                durationMs = 0,
                trackId = source.trackId,
                trackTitle = source.title,
                errorText = "",
            )
        }
        PlaybackLog.info(
            "VLC",
            "装载 ${normalizeMrl(source.url)}${if (source.live) "（直播）" else ""} 缓存=${cache.cachingMs(source)}ms",
        )
        val prepared = runCatching {
            target.media().prepare(normalizeMrl(source.url), *cache.vlcOptions(source).toTypedArray())
        }.getOrElse { error ->
            snapshots.update {
                it.copy(state = PlaybackState.Error, isBuffering = false, errorText = error.message ?: "libVLC 准备失败")
            }
            PlaybackLog.error("VLC", "准备失败：${error.javaClass.simpleName}: ${error.message}")
            return
        }
        if (!prepared) {
            snapshots.update {
                it.copy(state = PlaybackState.Error, isBuffering = false, errorText = "libVLC 无法准备：${source.url}")
            }
            PlaybackLog.error("VLC", "libVLC 拒绝这一路音源：${source.url}")
        }
    }

    override fun play() {
        checkNotReleased()
        userPaused = false
        ensurePlayer().controls().play()
        startTicker()
        crossfadeBridge.value.start()
        PlaybackLog.info("VLC", "播放")
    }

    override fun pause() {
        userPaused = true
        player?.let { runCatching { it.controls().setPause(true) } }
        PlaybackLog.info("VLC", "暂停")
    }

    override fun seekTo(positionMs: Long) {
        player?.let { runCatching { it.controls().setTime(positionMs) } }
        snapshots.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
        PlaybackLog.info("VLC", "跳转 ${positionMs}ms")
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        volumes.update { it.copy(volume = clamped) }
        // snapshot 是 UI 的唯一数据源：音量也同步过去，别让进度条和推子各自为政
        snapshots.update { it.copy(volume = clamped) }
        applyVolume()
    }

    /** 静音只影响输出音量，不动用户档位（与 [VolumeState] 的约定一致）。 */
    fun setMuted(muted: Boolean) {
        volumes.update { it.copy(muted = muted) }
        applyVolume()
    }

    /** 响度归一化：平台元数据算出的 dB 增益，乘进输出音量。 */
    fun setNormalizationDb(db: Float) {
        volumes.update { it.copy(normalizationDb = db) }
        applyVolume()
        PlaybackLog.info("VLC", "响度归一化 ${"%.2f".format(db)}dB → 音量 ${"%.3f".format(volumes.value.effective)}")
    }

    /** 选中队列里的某一首并立即播放（点歌单 / 自动切歌都走这里）。 */
    fun playAt(index: Int) {
        val song = queue.state.value.songs.getOrNull(index) ?: return
        queue.select(index)
        snapshots.update { it.copy(queueIndex = index) }
        prepare(song.toPlaybackSource())
        play()
    }

    fun playNext() {
        val next = queue.nextIndex()
        if (next >= 0) playAt(next)
    }

    fun playPrevious() {
        val previous = queue.previousIndex()
        if (previous >= 0) playAt(previous)
    }

    /** 主路音量百分比（0..100）；交叉淡化验收时把两条曲线都记进日志。 */
    fun primaryVolumePercent(): Int = runCatching { player?.audio()?.volume() ?: 0 }.getOrDefault(0)

    /** 预载那一路的音量百分比（0..100）。 */
    fun incomingVolumePercent(): Int = runCatching { secondary?.audio()?.volume() ?: 0 }.getOrDefault(0)

    override fun release() {
        if (released) return
        released = true
        ticker?.cancel()
        ticker = null
        if (crossfadeBridge.isInitialized()) runCatching { crossfadeBridge.value.release() }
        sleepTimer.detach()
        listOfNotNull(secondary, spare, player).forEach { target ->
            // 先停再放：停下来的播放器释放时才不会和音频输出线程打架
            runCatching { target.controls().stop() }
            runCatching { target.release() }
        }
        secondary = null
        spare = null
        player = null
        if (factoryHolder.isInitialized()) runCatching { factory.release() }
        scope.cancel()
        PlaybackLog.info("VLC", "释放 native 资源")
    }

    private fun checkNotReleased() = check(!released) { "VlcPlaybackEngine 已释放，不能再播放" }

    private fun applyVolume() {
        val percent = (volumes.value.effective * 100f).roundToInt().coerceIn(0, 100)
        player?.let { runCatching { it.audio().setVolume(percent) } }
    }

    private fun ensurePlayer(): MediaPlayer = player ?: newPlayer().also { created ->
        player = created
        equalizerBridge.value.attach(created)
        val percent = (volumes.value.effective * 100f).roundToInt().coerceIn(0, 100)
        created.audio().setVolume(percent)
    }

    // ---------------------------------------------------------------- 状态轮询

    /**
     * 为什么不用 `mediaPlayer.events().addMediaPlayerEventListener`：
     * libVLC 3.0.x 在 `libvlc_event_detach` 里会对事件管理器加锁，若此时事件线程正在派发回调，
     * `MediaPlayer.release()` 会**永久挂住**（实测 jstack 停在
     * `NativeEventManager.removeNativeEventListener → libvlc_event_detach`）。
     * 音乐播放器对状态延迟不敏感，这里改成 100ms 轮询 `status()`：
     * 一次性取回位置 / 时长 / 播放中 / 缓冲 / 结束，既没有 native 回调线程，也就没有 detach 死锁。
     */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                runCatching { pollState() }
                    .onFailure { PlaybackLog.warn("VLC", "轮询状态失败：${it.message}") }
                delay(POLL_MS)
            }
        }
    }

    private fun pollState() {
        if (released) return
        val target = player ?: return
        val status = target.status()
        val state = status.state()
        val position = status.time().coerceAtLeast(0)
        val length = status.length()
        val duration = if (length > 0L) length else snapshots.value.durationMs
        snapshots.update {
            it.copy(
                state = state.toPlaybackState(),
                isPlaying = status.isPlaying,
                isBuffering = state == State.BUFFERING,
                positionMs = position,
                durationMs = duration,
            )
        }
        val previous = lastState
        lastState = state
        if (state != previous) PlaybackLog.info("VLC", "状态 $state")
        // libVLC 用 media().prepare() + play() 放完一首时给的是 STOPPED（不是 ENDED），
        // 用"位置已经到结尾"把"真放完"和"被 stop 掉"区分开
        val finished = when (state) {
            State.ENDED -> previous != State.ENDED
            State.STOPPED -> previous != State.STOPPED && duration > 0L && position >= duration - END_SLACK_MS
            else -> false
        }
        if (finished) onFinished()
        if (state == State.ERROR && previous != State.ERROR) {
            snapshots.update { it.copy(errorText = "libVLC 播放失败") }
            PlaybackLog.error("VLC", "播放失败（state=Error）")
        }
    }

    private fun State.toPlaybackState(): PlaybackState = when (this) {
        State.PLAYING -> PlaybackState.Playing
        State.PAUSED -> PlaybackState.Paused
        State.BUFFERING, State.OPENING -> PlaybackState.Buffering
        State.ENDED -> PlaybackState.Ended
        State.ERROR -> PlaybackState.Error
        State.STOPPED, State.NOTHING_SPECIAL -> PlaybackState.Idle
    }

    private fun newPlayer(): MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    /** 一首放完：优先交给定时关闭，其次交叉淡化，最后才自动切下一首。 */
    private fun onFinished() {
        val sleep = sleepTimer.state.value
        if (sleep.endOfTrack) {
            sleepTimer.onTrackEnded()
            userPaused = true
            pauseAtEndOfTrack = false
            player?.controls()?.setPause(true)
            PlaybackLog.info("VLC", "定时关闭：播完当前歌曲，暂停")
            return
        }
        val fading = crossfadeBridge.isInitialized() && crossfadeBridge.value.isFading
        if (fading) {
            PlaybackLog.info("VLC", "结尾已交给交叉淡化，不再自动切歌")
            return
        }
        val next = queue.nextIndex()
        if (next >= 0 && next != queue.state.value.index) {
            playAt(next)
            return
        }
        snapshots.update { it.copy(state = PlaybackState.Ended, isPlaying = false) }
        PlaybackLog.info("VLC", "播放结束")
    }

    // ---------------------------------------------------------------- SleepTarget

    override fun onSleepPause() = pause()

    override fun onSleepVolume(volume: Float) = setVolume(volume)

    override fun onSleepAfterTrack(enabled: Boolean) {
        pauseAtEndOfTrack = enabled
        PlaybackLog.info("VLC", "定时关闭：${if (enabled) "播完当前歌曲再停" else "取消播完停"}")
    }

    // ---------------------------------------------------------------- CrossfadeHost

    override fun nowMs(): Long = clock()

    override fun primaryTitle(): String = snapshots.value.trackTitle

    override fun primaryPlaying(): Boolean = player?.status()?.isPlaying == true

    override fun primaryPlayWhenReady(): Boolean = !userPaused

    override fun primaryPositionMs(): Long = (player?.status()?.time() ?: snapshots.value.positionMs).coerceAtLeast(0)

    override fun primaryDurationMs(): Long {
        val length = player?.status()?.length() ?: -1L
        return if (length > 0L) length else snapshots.value.durationMs
    }

    override fun primaryIndex(): Int = snapshots.value.queueIndex

    override fun hasNext(): Boolean = queue.nextIndex() != -1

    override fun nextIndex(): Int = queue.nextIndex()

    override fun trackKey(index: Int): String = queue.state.value.songs.getOrNull(index)?.key.orEmpty()

    override fun trackTitle(index: Int): String = queue.state.value.songs.getOrNull(index)?.title.orEmpty()

    override fun prepareIncoming(index: Int, startMs: Long): Boolean {
        val song = queue.state.value.songs.getOrNull(index) ?: return false
        val source = song.toPlaybackSource()
        // 地址还没解析出来（等 Provider）或直播流：这一首不能过渡，直接硬切
        if (source.url.isBlank()) return false
        discardIncoming()
        val incoming = spare.also { spare = null } ?: newPlayer()
        // startPaused：打开音频输出、解到第 0 帧就停住。只 prepare 不 play 的话 libVLC 不会建输出，
        // isPlayable 一直是 false，状态机会以为"下一首还没准备好"而放弃过渡。
        val prepared = runCatching {
            incoming.media().startPaused(normalizeMrl(source.url), *cache.vlcOptions(source).toTypedArray())
        }.getOrDefault(false)
        if (!prepared) {
            runCatching { incoming.release() }
            PlaybackLog.warn("Crossfade", "下一首预载失败 index=$index")
            return false
        }
        incoming.audio().setVolume(0)
        equalizerBridge.value.applyTo(incoming)
        secondary = incoming
        pendingIndex = index
        if (startMs > 0) runCatching { incoming.controls().setTime(startMs) }
        PlaybackLog.info("Crossfade", "预载下一首 index=$index（${song.title}）起始 ${startMs}ms")
        return true
    }

    override fun incomingReady(): Boolean {
        val status = secondary?.status() ?: return false
        return status.isPlayable || status.state() == State.PAUSED || status.state() == State.PLAYING
    }

    override fun seekIncoming(index: Int, startMs: Long) {
        secondary?.let { runCatching { it.controls().setTime(startMs) } }
    }

    override fun startIncoming() {
        secondary?.let { runCatching { it.controls().play() } }
    }

    override fun setPrimaryVolume(volume: Float) {
        player?.let { runCatching { it.audio().setVolume((volume.coerceIn(0f, 1f) * 100f).roundToInt()) } }
    }

    override fun setIncomingVolume(volume: Float) {
        secondary?.let { runCatching { it.audio().setVolume((volume.coerceIn(0f, 1f) * 100f).roundToInt()) } }
    }

    override fun setPrimarySpeed(speed: Float) {
        player?.let { runCatching { it.controls().setRate(speed) } }
    }

    override fun promoteIncoming() {
        val next = secondary ?: return
        secondary = null
        val previous = player
        player = next
        // 旧的一路已经淡到 0：停掉、静音，留作下一次预载的 spare（见 [spare] 的说明）
        previous?.let { stopped ->
            runCatching { stopped.controls().stop() }
            runCatching { stopped.audio().setVolume(0) }
            spare = stopped
        }
        next.audio().setVolume(100)
        // 换了播放器实例：上一轮的结束状态不能带过来，否则下一首放完不会再触发 onFinished
        lastState = null
        val index = pendingIndex
        pendingIndex = -1
        if (index >= 0) {
            queue.select(index)
            val current = queue.state.value.current
            snapshots.update {
                it.copy(
                    queueIndex = index,
                    trackId = current?.key.orEmpty(),
                    trackTitle = current?.title.orEmpty(),
                    positionMs = 0,
                    durationMs = 0,
                )
            }
        }
        PlaybackLog.info("Crossfade", "交叉淡化完成，主播放器切到 index=$index")
    }

    override fun discardIncoming() {
        val incoming = secondary ?: return
        secondary = null
        pendingIndex = -1
        // 不 release 正在响的一路（会踩 native 内存），停掉静音后留作 spare
        runCatching { incoming.controls().stop() }
        runCatching { incoming.audio().setVolume(0) }
        spare = incoming
    }

    override fun sleepTimerActive(): Boolean = sleepTimer.state.value.active

    override fun analysisFor(trackKey: String): AutomixAnalysis? = automix.cached(trackKey)

    override fun log(message: String) = PlaybackLog.info("Crossfade", message)

    /**
     * 低频交接：libVLC 的均衡器只有 10 段固定频点，做不了 Android 那套动态高通 / 低通，
     * 这里只把交接进度记进日志（听感上退化成等功率淡化 + 速度对齐）。
     */
    override fun applyBassHandoff(cutoffHz: Float, progress: Float) {
        val percent = (progress * 100f).toInt() / 20 * 20
        if (percent == lastHandoffPercent) return
        lastHandoffPercent = percent
        PlaybackLog.info("Crossfade", "低频交接 ${cutoffHz.toInt()}Hz @ $percent%（libVLC 无动态滤波，按等功率处理）")
    }

    // ---------------------------------------------------------------- native

    private fun buildFactory(): MediaPlayerFactory {
        options.configureNativePath()
        PlaybackLog.info("VLC", "发现 libVLC 目录：${options.home?.absolutePath ?: "（vlcj 默认发现）"}")
        return try {
            MediaPlayerFactory(options.factoryArgs())
        } catch (error: Throwable) {
            throw VlcUnavailableException(
                "找不到可用的 libvlc：装 VLC 3.0.x（win64），或用 -Dmymusic.vlc.home / MYMUSIC_VLC_HOME " +
                    "指向 VLC 目录。桌面版会随包分发 libvlc.dll。原因：${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
    }

    private companion object {
        /** 状态轮询间隔：100ms 对进度条足够，且不会像 libVLC 事件线程那样在释放时死锁。 */
        const val POLL_MS = 100L

        /** 位置离结尾不到这么多就算放完（libVLC 的 time() 常常差几十毫秒不精确到 last sample）。 */
        const val END_SLACK_MS = 250L
    }
}
