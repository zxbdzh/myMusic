package com.mytv.mymusic.desktop.win

import com.mytv.mymusic.playback.core.PlaybackEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放控制命令。
 *
 * 四个系统入口（托盘菜单、全局热键、任务栏缩略图按钮、SMTC 媒体浮层按钮）最终都归到这里，
 * 因此"哪个入口能做什么"只有一份定义——新增一个入口不需要动播放器，只要发命令。
 */
enum class MediaCommand {
    /** 播放 / 暂停切换（媒体键与 SMTC 的主按钮语义）。 */
    TOGGLE_PLAY_PAUSE,
    PLAY,
    PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
}

/** 命令的接收方；测试里换成记录器即可断言"某入口发来了哪条命令"。 */
fun interface MediaControlTarget {
    fun onCommand(command: MediaCommand)
}

/**
 * 歌词行。
 *
 * 为什么桌面侧自己定义而不直接吃 Android 的歌词模型：Android 侧歌词来自
 * `ui/LyricsParser.kt` 的 LRC 解析，桌面阶段还没搬过来（见 ADR 批次 B1/B3）。
 * 这里只保留"时间 + 文本"这两个浮层真正需要的字段，等 `:core` 的解析器落地后
 * 用一层 `map` 转过来即可，浮层不需要改。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 系统集成面读的播放状态快照。
 *
 * 与 [com.mytv.mymusic.playback.core.PlaybackSnapshot] 的分工：那个是"引擎此刻在干什么"
 * （位置、缓冲、错误），这个是"系统外壳要显示什么"（标题、歌手、专辑、能否切歌）。
 * 两者由 [MediaCommandSink.publish] 合并，避免每个入口各自去拼字符串。
 */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val lyrics: List<LyricLine> = emptyList(),
) {
    /** 托盘提示与媒体浮层共用的单行标题；空标题时退回应用名，避免托盘出现空 tooltip。 */
    val displayLine: String
        get() = when {
            title.isBlank() -> "myMusic"
            artist.isBlank() -> title
            else -> "$title — $artist"
        }
}

/**
 * 命令分发中心 + 状态广播。
 *
 * 所有平台入口都在 AWT 事件线程、热键消息线程、SMTC 读线程上调用，因此这里的状态
 * 必须是线程安全的：[MutableStateFlow] 与 [AtomicInteger] 都满足。
 */
class MediaCommandSink(
    private val target: MediaControlTarget,
    initialState: NowPlaying = NowPlaying(),
) {

    private val stateFlow = MutableStateFlow(initialState)

    /** 供托盘 tooltip、SMTC 写入侧、悬浮歌词窗收集。 */
    val state: StateFlow<NowPlaying> = stateFlow.asStateFlow()

    private val dispatched = AtomicInteger(0)

    /** 已分发的命令数；`--win-selftest` 用它证明"入口 → 命令"真的打通了。 */
    val dispatchedCount: Int
        get() = dispatched.get()

    fun publish(next: NowPlaying) {
        stateFlow.value = next
    }

    fun dispatch(command: MediaCommand) {
        dispatched.incrementAndGet()
        target.onCommand(command)
    }
}

/**
 * 把 [MediaCommand] 翻译成播放引擎调用。
 *
 * `NEXT` / `PREVIOUS` 走外部注入的队列操作：队列属于 `:playback-core` 的 `QueueStore`，
 * 引擎契约里没有播放列表（见 `PlaybackEngine.kt` 的注释），所以这里只留一个回调，
 * 等队列层搬过来时把真实实现塞进来即可。
 */
class PlaybackEngineTarget(
    private val engine: PlaybackEngine,
    private val next: () -> Unit = {},
    private val previous: () -> Unit = {},
) : MediaControlTarget {

    override fun onCommand(command: MediaCommand) {
        when (command) {
            MediaCommand.TOGGLE_PLAY_PAUSE -> if (engine.snapshot.value.isPlaying) engine.pause() else engine.play()
            MediaCommand.PLAY -> engine.play()
            MediaCommand.PAUSE -> engine.pause()
            // 引擎契约没有 stop：暂停 + 回零是等价语义，避免为了一个按钮扩接口
            MediaCommand.STOP -> {
                engine.pause()
                engine.seekTo(0)
            }
            MediaCommand.NEXT -> next()
            MediaCommand.PREVIOUS -> previous()
        }
    }
}
