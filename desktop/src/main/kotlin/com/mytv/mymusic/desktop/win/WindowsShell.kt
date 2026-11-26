package com.mytv.mymusic.desktop.win

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Frame

/**
 * Windows 集成总装。
 *
 * 一个实例持有全部系统入口，并把它们接到同一个 [MediaCommandSink] 上：
 *
 * ```
 *   托盘菜单 ─┐
 *   全局热键 ─┤
 *   任务栏按钮 ┼──> MediaCommandSink ──> MediaControlTarget（由调用方提供）
 *   SMTC 浮层 ┘                 ▲
 *                          NowPlaying（标题/歌手/进度/歌词，由调用方 publish）
 * ```
 *
 * 与播放层的关系是**单向**的：本类不认识 `PlaybackEngine`、也不认识 `MusicStore`，
 * 只认一个 [MediaControlTarget] 回调。UI 组把 `MusicStore` 的播放方法接进来
 * 只需要一个 lambda，Windows 层也不需要跟着播放层重构。
 *
 * 关闭顺序有意为之：先拆入口（热键→托盘→SMTC→任务栏），最后由调用方释放引擎——
 * 反过来的话，拆的过程中用户按到热键会打到已释放的引擎上。
 */
class WindowsShell private constructor(
    target: MediaControlTarget,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 命令入口 + 状态出口；UI 组从这里 `dispatch` 与 `publish`。 */
    val sink: MediaCommandSink = MediaCommandSink(target)

    val tray: TrayControl = TrayControl(
        sink = sink,
        onShowWindow = { showMainWindow() },
        onToggleLyrics = { toggleFloatingLyrics() },
        onQuit = {
            quitRequested = true
            quitHandler()
        },
    )

    val hotkeys: GlobalHotkeys = GlobalHotkeys(
        MediaHotkeys.defaultBindings(
            sink = sink,
            onToggleLyrics = { toggleFloatingLyrics() },
            onShowWindow = { showMainWindow() },
        ),
    )

    val smtc: SmtcBridgeClient = SmtcBridgeClient(
        exe = SmtcBridgeClient.locate(),
        onButton = { button ->
            // 媒体浮层按钮 → 同一套命令，行为与托盘/热键完全一致
            when (button) {
                MediaButton.PLAY -> sink.dispatch(MediaCommand.PLAY)
                MediaButton.PAUSE -> sink.dispatch(MediaCommand.PAUSE)
                MediaButton.STOP -> sink.dispatch(MediaCommand.STOP)
                MediaButton.NEXT -> sink.dispatch(MediaCommand.NEXT)
                MediaButton.PREVIOUS -> sink.dispatch(MediaCommand.PREVIOUS)
            }
        },
    )

    /** 悬浮歌词窗是否可见；Compose 直接读它决定窗口的 `visible`。 */
    var floatingLyricsVisible: Boolean by mutableStateOf(false)
        private set

    /** 主窗口标题，任务栏按钮靠它把 HWND 对到窗口上。 */
    var mainWindowTitle: String = DEFAULT_MAIN_TITLE
        private set

    /** 任务栏按钮句柄，自检与诊断面板读它。 */
    val taskbarButtons: TaskbarThumbnailButtons?
        get() = taskbar

    @Volatile
    private var mainWindow: ComposeWindow? = null

    @Volatile
    private var taskbar: TaskbarThumbnailButtons? = null

    @Volatile
    private var quitRequested = false

    /** 托盘"退出"与"关闭窗口且无托盘"时调用；由 [install] 注入。 */
    @Volatile
    private var quitHandler: () -> Unit = {}

    /** 安装全部入口（非 Windows 平台整体跳过，只保留空实现）。 */
    fun install(onQuit: () -> Unit = {}): WindowsShell {
        quitHandler = onQuit
        if (!Win32.isWindows) return this

        tray.install()
        hotkeys.start()
        smtc.start()

        // 状态广播：托盘 tooltip 自己订阅，SMTC 需要把状态推给桥
        scope.launch { sink.state.collect { smtc.publish(it) } }
        return this
    }

    /**
     * 主窗口显示后调用：装任务栏缩略图按钮。
     *
     * 必须等窗口**可见**（有 HWND）才能装。窗口刚创建时句柄还查不到，所以这里只试一次
     * 并返回是否成功——调用方（Compose 的 `LaunchedEffect`）用 `delay` 重试，
     * 不能在这里 `Thread.sleep`，否则会堵住刚起来的事件线程。
     *
     * @return 任务栏按钮是否装上（非 Windows 平台恒为 false）
     */
    fun attachMainWindow(window: ComposeWindow, title: String = DEFAULT_MAIN_TITLE): Boolean {
        mainWindow = window
        mainWindowTitle = title
        if (!Win32.isWindows) return false
        if (taskbar?.installed == true) return true

        val hwnd = Win32.findTopLevelWindow(title) ?: return false
        taskbar = TaskbarThumbnailButtons(
            hwnd = hwnd,
            buttons = listOf(
                ThumbButton(THUMB_PREVIOUS, "上一首", ThumbGlyph.PREVIOUS) {
                    sink.dispatch(MediaCommand.PREVIOUS)
                },
                ThumbButton(THUMB_PLAY_PAUSE, "播放/暂停", ThumbGlyph.PLAY) {
                    sink.dispatch(MediaCommand.TOGGLE_PLAY_PAUSE)
                    // 图标随状态切换：Windows 不会因为 iId 相同就自动重画
                    syncThumbButtonGlyphs(sink.state.value.isPlaying)
                },
                ThumbButton(THUMB_NEXT, "下一首", ThumbGlyph.NEXT) {
                    sink.dispatch(MediaCommand.NEXT)
                },
                ThumbButton(THUMB_LYRICS, "悬浮歌词", ThumbGlyph.LYRICS) {
                    toggleFloatingLyrics()
                },
            ),
        ).install()
        if (taskbar?.installed != true) {
            System.err.println("myMusic: 任务栏按钮不可用（${taskbar?.failure}）")
        }
        return taskbar?.installed == true
    }

    /** 播放状态变化后同步缩略图按钮的图标（播放 ↔ 暂停）。 */
    fun syncThumbButtonGlyphs(playing: Boolean) {
        taskbar?.updateGlyph(
            THUMB_PLAY_PAUSE,
            if (playing) ThumbGlyph.PAUSE else ThumbGlyph.PLAY,
            if (playing) "暂停" else "播放",
        )
    }

    fun showMainWindow() {
        val window = mainWindow ?: return
        if (!window.isVisible) {
            window.isVisible = true
        }
        // 最小化过就恢复，否则单纯前置
        if ((window.extendedState and Frame.ICONIFIED) != 0) {
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        }
        runCatching {
            window.toFront()
            window.requestFocus()
        }
        // AWT 的 toFront 在部分场景被前台锁挡住，再走一次 Win32
        runCatching { Win32.findTopLevelWindow(mainWindowTitle)?.let { Win32.user32.SetForegroundWindow(it) } }
    }

    /** 关闭按钮 → 收进托盘（而不是退出）。没有托盘时不能隐藏，否则用户点不回来。 */
    fun hideToTray() {
        if (!tray.isInstalled || quitRequested) {
            quitHandler()
            return
        }
        EventQueue.invokeLater { mainWindow?.isVisible = false }
    }

    fun toggleFloatingLyrics() {
        if (!Win32.isWindows) return
        floatingLyricsVisible = !floatingLyricsVisible
    }

    fun closeFloatingLyrics() {
        floatingLyricsVisible = false
    }

    override fun close() {
        quitRequested = true
        runCatching { hotkeys.close() }
        runCatching { tray.close() }
        runCatching { smtc.close() }
        runCatching { taskbar?.close() }
        taskbar = null
        scope.cancel()
    }

    companion object {
        /**
         * 主窗口标题。
         *
         * 必须与 `Main.kt` 里 `Window(title = ...)` 的字符串**逐字相同**：
         * 任务栏缩略图按钮靠 `EnumWindows` + 标题找 HWND，差一个全角点都找不到。
         */
        const val DEFAULT_MAIN_TITLE = "myMusic · 桌面版"

        /** 缩略图按钮 id：与 `THUMBBUTTON.iId` 一一对应，改一个就要改两处。 */
        const val THUMB_PREVIOUS = 1
        const val THUMB_PLAY_PAUSE = 2
        const val THUMB_NEXT = 3
        const val THUMB_LYRICS = 4

        /**
         * 装配入口：`Main.kt` 里一行
         * `WindowsShell.create(target).install { exitApplication() }`。
         *
         * @param target 命令出口，把 `MusicStore` 的播放方法包一层（见 `Main.kt` 的
         *   `StoreMediaTarget`）；Windows 层不反向依赖状态层。
         */
        fun create(target: MediaControlTarget): WindowsShell = WindowsShell(target)
    }
}
