package com.mytv.mymusic.desktop

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mytv.mymusic.desktop.state.CoreMusicCatalog
import com.mytv.mymusic.desktop.state.DefaultMusicStore
import com.mytv.mymusic.desktop.state.DesktopPaths
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.SearchKind
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.AppShell
import com.mytv.mymusic.desktop.ui.components.RemoteImages
import com.mytv.mymusic.desktop.ui.components.TooltipHost
import com.mytv.mymusic.desktop.ui.components.TooltipState
import com.mytv.mymusic.desktop.ui.components.ContextMenuHost
import com.mytv.mymusic.desktop.ui.components.ContextMenuState
import com.mytv.mymusic.desktop.ui.components.LocalContextMenuState
import com.mytv.mymusic.desktop.ui.components.LocalTooltipState
import com.mytv.mymusic.desktop.ui.components.appScrollbarStyle
import com.mytv.mymusic.desktop.win.AppMenuBar
import com.mytv.mymusic.desktop.win.DesktopAction
import com.mytv.mymusic.desktop.win.FloatingLyricsWindow
import com.mytv.mymusic.desktop.win.LyricLine
import com.mytv.mymusic.desktop.win.MediaCommand
import com.mytv.mymusic.desktop.win.MediaControlTarget
import com.mytv.mymusic.desktop.win.NowPlaying
import com.mytv.mymusic.desktop.win.Shortcuts
import com.mytv.mymusic.desktop.win.WindowGeometry
import com.mytv.mymusic.desktop.win.WindowGeometryStore
import com.mytv.mymusic.desktop.win.Win32
import com.mytv.mymusic.desktop.win.WindowCapture
import com.mytv.mymusic.desktop.win.WindowsShell
import com.mytv.mymusic.desktop.win.showAbout
import com.mytv.mymusic.desktop.win.showShortcutHelp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Dimension
import java.awt.Frame
import java.io.File

/**
 * 桌面版入口。
 *
 * 三条路径（顺序即优先级）：
 *  1. `--screenshot[=]<目录>`：离屏渲染每个页面为 PNG，**不开任何窗口**。验收与文档用，不抢焦点；
 *  2. `--win-selftest`：Windows 集成自检需要真实窗口与消息泵，宿主 main 在 win 包
 *     （`./gradlew :desktop:runWinSelfTest`）；这里只提示一句怎么跑，不再重复实现；
 *  3. 无参数：单实例互斥 → 装配系统集成 → 打开主窗口（内容 = [AppShell]）。
 *
 * 归属划分（见团队仲裁）：`Main.kt`、`desktop/ui`、`desktop/state`、`desktop/theme` 属于 UI；
 * `desktop/win` 属于 Windows 集成。接缝只有三处：窗口壳 [WindowsShell]、悬浮歌词窗、以及
 * [NowPlaying] 这份媒体快照——UI 不感知 JNA，`win` 也不需要认识 `MusicStore` 之外的 UI 类型。
 *
 * 快捷键挂在窗口级（[Window.onKeyEvent]）：输入框会先消费空格与方向键，
 * 所以"搜歌时打空格"不会触发暂停；Esc 只在覆盖层打开时响应。
 */
fun main(args: Array<String>) {
    // 随包 libVLC：jpackage 把 resources/vlc 放到 app 目录旁，JNA 只读一次 jna.library.path，
    // 所以必须在任何 vlcj 调用之前设置（见 VlcInstallation）
    val bundledVlc = com.mytv.mymusic.desktop.state.audio.VlcInstallation.applyBundledRuntime()
    if (bundledVlc != null) System.err.println("[vlc] 随包运行库：${bundledVlc.absolutePath}")
    screenshotTarget(args)?.let { dir ->
        ScreenshotRunner.run(File(dir))
        return
    }
    windowShotTarget(args)?.let { dir ->
        runWindowShot(File(dir))
        return
    }
    if (args.any { it == WIN_SELF_TEST_FLAG || it.startsWith("$WIN_SELF_TEST_FLAG=") }) {
        System.err.println("Windows 集成自检需要真实窗口，请用： ./gradlew :desktop:runWinSelfTest")
        return
    }
    launchDesktop()
}

private const val SCREENSHOT_FLAG = "--screenshot"
private const val WINDOW_SHOT_FLAG = "--window-shot"
private const val WIN_SELF_TEST_FLAG = "--win-selftest"
/** 左右方向键的 seek 步长（t16 播放侧与 t17 快捷键体系对齐为 5 秒）。 */
private const val SEEK_STEP_MS = 5_000L
/** ↑/↓ 的音量步长。 */
private const val VOLUME_STEP = 0.05f

/** `--window-shot=<dir>`：实拍真实窗口（含菜单栏展开态），用于文档与验收。 */
private fun windowShotTarget(args: Array<String>): String? {
    args.firstOrNull { it.startsWith("$WINDOW_SHOT_FLAG=") }?.let { return it.substringAfter('=').trim('"', '\'') }
    val index = args.indexOf(WINDOW_SHOT_FLAG)
    return if (index >= 0 && index + 1 < args.size) args[index + 1].trim('"', '\'') else null
}

/** `--screenshot=<dir>` 与 `--screenshot <dir>` 两种写法都认（文档里两种都出现过）。 */
private fun screenshotTarget(args: Array<String>): String? {
    args.firstOrNull { it.startsWith("$SCREENSHOT_FLAG=") }?.let { return it.substringAfter('=').trim('"', '\'') }
    val index = args.indexOf(SCREENSHOT_FLAG)
    return if (index >= 0 && index + 1 < args.size) args[index + 1].trim('"', '\'') else null
}

/** 窗口标题：`正在播放 - {歌名} - myMusic`；没有在播的歌时用 [WindowsShell.DEFAULT_MAIN_TITLE]。 */
internal fun windowTitle(trackTitle: String?): String {
    val name = trackTitle?.trim().orEmpty()
    return if (name.isEmpty()) WindowsShell.DEFAULT_MAIN_TITLE else "正在播放 - $name - ${WindowsShell.DEFAULT_MAIN_TITLE}"
}

/** 正常启动：系统集成（托盘/热键/任务栏/SMTC）+ 菜单栏 + 主窗口 + 悬浮歌词窗。 */
private fun launchDesktop() = application {
    val paths = remember { DesktopPaths() }
    val store = remember { DefaultMusicStore(catalog = CoreMusicCatalog(paths)) }
    // 后台验收用：MYMUSIC_BACKGROUND=1 时窗口不抢焦点（默认行为仍是正常激活）
    val background = remember { System.getenv("MYMUSIC_BACKGROUND") == "1" }

    // 托盘 / 全局热键 / 任务栏按钮 / SMTC 由 WindowsShell 装配；命令流回 store 的播放方法
    val shell = remember { WindowsShell.create(StoreMediaTarget(store)).install { exitApplication() } }
    // 状态流：store → 托盘 tooltip / SMTC / 悬浮歌词窗（单向，只有这一处发布）
    LaunchedEffect(store, shell) {
        store.state.collect { state -> shell.sink.publish(state.toNowPlaying()) }
    }
    val nowPlaying by shell.sink.state.collectAsState()
    val musicState by store.state.collectAsState()

    // 窗口几何：上次关闭时的位置/大小（独立小文件，理由见 WindowGeometryStore 注释）
    val geometryStore = remember { WindowGeometryStore(File(paths.dataDir, "window.json")) }
    val initialGeometry = remember { geometryStore.load()?.let(geometryStore::sanitize) }
    val windowState = rememberWindowState(
        position = if (initialGeometry?.x != null && initialGeometry.y != null) {
            WindowPosition(initialGeometry.x.dp, initialGeometry.y.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
        size = DpSize(
            (initialGeometry?.width ?: WindowGeometry.DEFAULT_WIDTH).dp,
            (initialGeometry?.height ?: WindowGeometry.DEFAULT_HEIGHT).dp,
        ),
    )

    // tooltip 宿主：pcTooltip 的唯一渲染点（在窗口内容最外层提供）
    val tooltipState = remember { TooltipState() }
    val contextMenuState = remember { ContextMenuState() }
    val currentGeometry: () -> WindowGeometry = {
        // WindowState.position 是 sealed（Aligned / Absolute），只有 Absolute 才有坐标；
        // 居中打开（Aligned）时坐标留空，下次启动继续居中。
        val absolute = windowState.position as? WindowPosition.Absolute
        WindowGeometry(
            x = absolute?.x?.value?.toInt(),
            y = absolute?.y?.value?.toInt(),
            width = windowState.size.width.value.toInt(),
            height = windowState.size.height.value.toInt(),
            maximized = windowState.placement == WindowPlacement.Maximized ||
                windowState.placement == WindowPlacement.Fullscreen,
        )
    }

    Window(
        // 关窗只收进托盘；托盘"退出"才真的退出（由 install 的 onQuit 负责）
        onCloseRequest = {
            geometryStore.save(currentGeometry())
            shell.hideToTray()
        },
        // 标题随播放状态变化（Spotify 习惯）；任务栏按钮用模式匹配找窗口，见 Win32.findMainWindow
        title = windowTitle(musicState.current?.title),
        state = windowState,
        onKeyEvent = { event -> handleShortcut(event, store) },
    ) {
        // 菜单栏：文件/播放/视图/帮助；快捷键提示由 Compose 按 KeyShortcut 渲染
        AppMenuBar(
            playing = musicState.playing,
            volume = musicState.volume,
            onAction = { action -> handleMenuAction(action, store, window) },
        )

        CompositionLocalProvider(
            LocalTooltipState provides tooltipState,
            LocalContextMenuState provides contextMenuState,
            // 应用级细滚动条：8dp、圆角、半透明（hover 变宽由 PcVerticalScrollbar 负责）
            LocalScrollbarStyle provides appScrollbarStyle(),
        ) {
            // 在内容组合期同步设置：此时窗口还没 show，autoRequestFocus 才会生效
            if (background) runCatching { window.isAutoRequestFocus = false }
            DisposableEffect(Unit) {
                onDispose {
                    geometryStore.save(currentGeometry())
                    shell.close()
                    store.shutdown()
                }
            }
            LaunchedEffect(Unit) {
                // 最小可读尺寸：960×640（低于这个值，导航栏 + 内容区开始互相挤压）
                window.minimumSize = Dimension(WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT)
                if (initialGeometry?.maximized == true) {
                    window.extendedState = window.extendedState or Frame.MAXIMIZED_BOTH
                }
                // 任务栏缩略图按钮需要 HWND：窗口句柄异步就绪，用重试而不是 sleep（会卡住事件线程）
                repeat(20) {
                    if (shell.attachMainWindow(window)) return@LaunchedEffect
                    delay(60)
                }
            }
            AppShell(store)
            TooltipHost(tooltipState)
            ContextMenuHost(contextMenuState)
        }
    }

    // 悬浮歌词是独立顶层窗口，由宿主（这里）Compose 出来，才能拿到自己的 HWND
    FloatingLyricsWindow(
        visible = shell.floatingLyricsVisible,
        nowPlaying = nowPlaying,
        onClose = { shell.closeFloatingLyrics() },
    )
}

/**
 * 托盘 / 媒体键 → 状态层。
 *
 * 只做命令翻译，不碰播放器：`STOP` 在桌面语义里是"暂停并停在原地"，
 * 因为队列层还没有"清空并停止"这个行为（P2 再补）。
 */
private class StoreMediaTarget(private val store: MusicStore) : MediaControlTarget {
    override fun onCommand(command: MediaCommand) {
        when (command) {
            MediaCommand.TOGGLE_PLAY_PAUSE -> store.togglePlay()
            MediaCommand.PLAY -> if (!store.state.value.playing) store.togglePlay()
            MediaCommand.PAUSE -> if (store.state.value.playing) store.togglePlay()
            MediaCommand.STOP -> if (store.state.value.playing) store.togglePlay()
            MediaCommand.NEXT -> store.next()
            MediaCommand.PREVIOUS -> store.previous()
        }
    }
}

/** 界面状态 → 系统媒体信息（托盘菜单、SMTC、悬浮歌词窗共用这一份）。 */
private fun MusicUiState.toNowPlaying(): NowPlaying = NowPlaying(
    title = current?.title.orEmpty(),
    artist = current?.artist.orEmpty(),
    album = current?.album.orEmpty(),
    isPlaying = playing,
    positionMs = positionMs,
    durationMs = durationMs.takeIf { it > 0 } ?: current?.durationMs ?: 0L,
    canNext = queue.size > 1,
    canPrevious = queue.size > 1,
    lyrics = lyrics?.lines?.map { LyricLine(it.startMs, it.text) }.orEmpty(),
)

/**
 * 应用内快捷键：KeyEvent → [Shortcuts.resolve] → [dispatchAction]。
 *
 * 与 `GlobalHotkeys`（系统级媒体键）的分工：这里的事件只在窗口聚焦时到达，
 * 输入框会先消费空格与方向键（所以"搜歌时打空格"不会暂停）。
 * 返回 true 表示事件已被应用消费、不再往下传。
 */
private fun handleShortcut(event: KeyEvent, store: MusicStore): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = Shortcuts.resolve(
        key = event.key,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    ) ?: return false
    return dispatchAction(action, store)
}

/** 菜单动作 → 同一个分发入口（菜单与键盘走同一份逻辑，不会出现「菜单能点、快捷键不灵」）。 */
private fun handleMenuAction(action: DesktopAction, store: MusicStore, window: java.awt.Window) {
    when (action) {
        DesktopAction.QUIT -> store.shutdown()
        DesktopAction.REFRESH -> refreshCurrentRoute(store)
        DesktopAction.FULLSCREEN -> {
            val maximized = window is java.awt.Frame && window.extendedState and Frame.MAXIMIZED_BOTH != 0
            if (window is java.awt.Frame) {
                window.extendedState = if (maximized) {
                    window.extendedState and Frame.MAXIMIZED_BOTH.inv()
                } else {
                    window.extendedState or Frame.MAXIMIZED_BOTH
                }
            }
        }
        DesktopAction.SHORTCUT_HELP -> showShortcutHelp()
        DesktopAction.ABOUT -> showAbout()
        DesktopAction.SHOW_PLAYER_PANE -> dispatchAction(DesktopAction.SHOW_PLAYER_PANE, store)
        DesktopAction.SHOW_LYRICS -> dispatchAction(DesktopAction.SHOW_LYRICS, store)
        else -> dispatchAction(action, store)
    }
}

/** 动作 → store 调用；所有可执行动作只有这一处映射。 */
private fun dispatchAction(action: DesktopAction, store: MusicStore): Boolean {
    val state = store.state.value
    return when (action) {
        DesktopAction.PLAY_PAUSE -> { store.togglePlay(); true }
        DesktopAction.PREVIOUS -> { store.previous(); true }
        DesktopAction.NEXT -> { store.next(); true }
        DesktopAction.VOLUME_UP -> { store.setVolume((state.volume + VOLUME_STEP).coerceIn(0f, 1f)); true }
        DesktopAction.VOLUME_DOWN -> { store.setVolume((state.volume - VOLUME_STEP).coerceIn(0f, 1f)); true }
        DesktopAction.SEEK_BACKWARD -> {
            store.seekTo((state.positionMs - SEEK_STEP_MS).coerceAtLeast(0L)); true
        }
        DesktopAction.SEEK_FORWARD -> { store.seekTo(state.positionMs + SEEK_STEP_MS); true }
        DesktopAction.SHOW_LYRICS -> { store.openLyrics(); true }
        DesktopAction.SHOW_PLAYER_PANE -> { store.openPlayer(); true }
        DesktopAction.TOGGLE_POSTER_WALL -> { store.openPosterWall(); true }
        DesktopAction.FOCUS_SEARCH -> {
            store.navigate(Route.Search)
            // 输入框聚焦由 SearchPage 的 FocusRequester 完成（t15 页面内实现），这里只负责跳转
            true
        }
        DesktopAction.OPEN_SETTINGS -> { store.navigate(Route.Settings); true }
        DesktopAction.CLOSE_OVERLAY -> when {
            state.lyricsOpen -> { store.closeLyrics(); true }
            state.playerOpen -> { store.closePlayer(); true }
            state.posterOpen -> { store.closePosterWall(); true }
            state.comments.open -> { store.closeComments(); true }
            state.detailKey != null -> { store.closeDetail(); true }
            else -> false
        }
        // 以下动作在菜单里由 handleMenuAction 先行处理（需要窗口/对话框），或不支持
        DesktopAction.REFRESH, DesktopAction.FULLSCREEN, DesktopAction.SHORTCUT_HELP,
        DesktopAction.ABOUT, DesktopAction.QUIT,
        DesktopAction.IMPORT_PLAYLIST, DesktopAction.EXPORT_PLAYLIST,
        -> false
    }
}

/** 视图 → 刷新：刷新当前页签对应的数据（没有刷新概念的页什么都不做）。 */
private fun refreshCurrentRoute(store: MusicStore) {
    when (store.state.value.route) {
        Route.Charts -> store.refreshCharts()
        Route.Playlists -> store.refreshPlaylists()
        Route.Search -> store.retrySearch()
        Route.Library, Route.Settings -> Unit
    }
}

/**
 * `--window-shot=<dir>`：实拍真实窗口（含菜单栏展开态）。
 *
 * 与 `--screenshot` 的分工：那个用 [ImageComposeScene] 离屏渲染页面，**没有窗口装饰**
 * （菜单栏是 `FrameWindowScope` 里的东西，离屏场景里不存在）；这个开真实窗口，用
 * `java.awt.Robot` 点开「文件」菜单后截窗口区域，作为「像不像 PC 程序」的验收证据。
 *
 * 会短暂把窗口提到前台（Robot 点击需要窗口接收事件）；跑完自动退出。
 */
private fun runWindowShot(dir: File) = application {
    val paths = remember { DesktopPaths() }
    val store = remember { DefaultMusicStore(catalog = CoreMusicCatalog(paths)) }
    val shell = remember { WindowsShell.create(StoreMediaTarget(store)).install { } }
    LaunchedEffect(store, shell) {
        store.state.collect { state -> shell.sink.publish(state.toNowPlaying()) }
    }
    val musicState by store.state.collectAsState()
    val state = rememberWindowState(width = 1440.dp, height = 900.dp)

    Window(
        onCloseRequest = { exitApplication() },
        title = windowTitle(musicState.current?.title),
        state = state,
    ) {
        AppMenuBar(playing = musicState.playing, volume = musicState.volume, onAction = { })
        AppShell(store)
        LaunchedEffect(Unit) {
            dir.mkdirs()
            delay(1600) // 等首屏与封面落位
            val hwnd = Win32.findMainWindow()
            if (hwnd == null) {
                println("[window-shot] 找不到主窗口句柄，跳过截图（窗口可能尚未创建完成）")
                exitApplication()
                return@LaunchedEffect
            }
            // 用 PrintWindow（GDI）而不是 Robot：不抢焦点、不需要窗口在前台，
            // 也不会把用户桌面上的其它窗口拍进来（见 win/WindowCapture.kt 注释）。
            fun capture(name: String) {
                val file = File(dir, name)
                val ok = WindowCapture.captureToFile(hwnd, file)
                println("[window-shot] ${file.absolutePath} ok=$ok")
            }
            capture("01-window.png")
            // 程序化点开「文件」菜单：向窗口 PostMessage 左键，不需要真实前台焦点
            postMenuClick(hwnd, x = 40, y = 14)
            delay(650)
            capture("02-menu-file.png")
            postMenuClick(hwnd, x = 40, y = 14) // 再点一次收起
            delay(150)
            exitApplication()
        }
    }
}

/**
 * 向窗口投递一次左键（客户区坐标）：用于程序化点开菜单栏，不需要真实前台焦点。
 *
 * 先发 WM_MOUSEMOVE 让 Compose 更新 hover 状态，再 down/up——菜单的展开走的是
 * 同一套 pointer 事件，和真人点击等价。
 */
private fun postMenuClick(hwnd: com.sun.jna.Pointer, x: Int, y: Int) {
    val lParam = (((y and 0xFFFF) shl 16) or (x and 0xFFFF)).toLong()
    Win32.user32.PostMessageW(hwnd, WM_MOUSEMOVE, 0, lParam)
    Win32.user32.PostMessageW(hwnd, WM_LBUTTONDOWN, 1, lParam) // MK_LBUTTON
    Win32.user32.PostMessageW(hwnd, WM_LBUTTONUP, 0, lParam)
}

/** 窗口消息常量（只在本文件的截图流程里用）。 */
private const val WM_MOUSEMOVE = 0x0200
private const val WM_LBUTTONDOWN = 0x0201
private const val WM_LBUTTONUP = 0x0202

/**
 * 截图模式：逐场景渲染 PNG。
 *
 * 每个场景一个独立 store（互不串状态）；先 `bootstrap()` 灌满数据，再执行场景动作，
 * 最后等异步取流 / 歌词落地。场景里的 `seekTo` 是必需的：
 * 定格在 0 秒的截图看不出进度条、歌词高亮是不是活的。
 */
private object ScreenshotRunner {

    private const val WIDTH = 1440
    private const val HEIGHT = 900

    private class Shot(val name: String, val prepare: suspend (MusicStore) -> Unit)

    fun run(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("截图目录无法创建：${dir.absolutePath}")
            return
        }
        val shots = listOf(
            Shot("01-charts") { store ->
                store.navigate(Route.Charts)
                // 用真实热歌起播：demo 曲目不在 core 的 songs 注册表里，取流必失败
                val queue = store.state.value.charts.hotSongs
                queue.firstOrNull()?.let { store.playTrack(it, queue) }
                // 取流会把进度重置到 0，先等它落地再 seek
                delay(600)
                store.seekTo(48_000L)
            },
            Shot("02-playlists") { store -> store.navigate(Route.Playlists) },
            Shot("03-search") { store ->
                // netease 的搜索接口在本机常返回 502；换 qq（t7 冒烟里 3 条命中）保证截图有真实结果
                store.selectProvider("qq")
                delay(900)
                store.navigate(Route.Search)
                store.updateQuery("晴天")
                store.submitSearch()
                delay(1500)
            },
            Shot("04-library") { store ->
                // 先播放并收藏几首，让「我的」页有真实数据（收藏落在 CoreMusicCatalog 的内存表里）
                store.selectProvider("qq")
                delay(900)
                val hot = store.state.value.charts.hotSongs
                hot.firstOrNull()?.let { store.playTrack(it, hot) }
                delay(400)
                hot.take(8).forEach { store.toggleFavorite(it) }
                // 截图只渲染一帧，NoticeHost 里「3.6 秒后自动消失」的 LaunchedEffect 不会跑，
                // 所以这里手动清掉"已收藏…"提示，否则它会压住列表第一行
                store.clearNotice()
                store.navigate(Route.Library)
                delay(400)
            },
            Shot("05-player") { store ->
                store.selectProvider("qq")
                delay(900)
                val hot = store.state.value.charts.hotSongs
                val first = hot.firstOrNull()
                if (first != null) {
                    store.playTrack(first, hot)
                    delay(600)
                    store.seekTo(32_000L)
                }
                store.openPlayer()
            },
            Shot("06-lyrics") { store ->
                store.selectProvider("qq")
                delay(900)
                val first = store.state.value.charts.hotSongs.firstOrNull()
                if (first != null) {
                    store.playTrack(first, listOf(first))
                    delay(600)
                    store.seekTo(72_000L)
                }
                store.openLyrics()
            },
            Shot("07-poster-wall") { store ->
                val hot = store.state.value.charts.hotSongs
                hot.firstOrNull()?.let { store.playTrack(it, hot) }
                store.openPosterWall()
            },
            Shot("08-settings") { store -> store.navigate(Route.Settings) },
        )

        shots.forEach { shot -> render(dir, shot) }
        println("[screenshot] 全部完成：${dir.absolutePath}")
    }

    private fun render(dir: File, shot: Shot) {
        // 截图 runner 与真实窗口共用同一份装配：接 DemoFixtures 会出假图，验收就没有意义
        val store = DefaultMusicStore(catalog = CoreMusicCatalog(DesktopPaths()))
        try {
            runBlocking {
                store.bootstrap()
                shot.prepare(store)
                delay(700)
                // 离屏渲染只出一帧，图片是异步拉的：不先把封面预加载好，
                // 截出来全是首字占位，看起来像"没有封面"。真实运行时会自己刷出来。
                com.mytv.mymusic.desktop.ui.components.RemoteImages.preloadWithin(
                    collectCoverUrls(store.state.value),
                    timeoutMs = 15_000L,
                )
                // 图进了缓存，但布局里的 LaunchedEffect 还要一帧才会把 bitmap 显示出来
                delay(400)
            }
            val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                AppShell(store)
            }
            try {
                val encoded = scene.render().encodeToData(EncodedImageFormat.PNG)
                if (encoded == null) {
                    System.err.println("[screenshot] ${shot.name} PNG 编码失败")
                    return
                }
                val file = File(dir, "${shot.name}.png")
                file.writeBytes(encoded.bytes)
                println("[screenshot] ${file.absolutePath} (${file.length()} bytes)")
            } finally {
                scene.close()
            }
        } finally {
            store.shutdown()
        }
    }

    /** 当前状态里所有可见的封面地址：榜单、歌单、搜索、队列、正在播放。 */
    private fun collectCoverUrls(state: com.mytv.mymusic.desktop.state.MusicUiState): List<String> = buildList {
        state.charts.charts.forEach { add(it.coverUrl) }
        state.charts.hotSongs.forEach { add(it.coverUrl) }
        state.playlists.recommended.forEach { add(it.coverUrl) }
        state.playlists.categoryPlaylists.forEach { add(it.coverUrl) }
        state.search.results.forEach { add(it.coverUrl) }
        state.queue.forEach { add(it.coverUrl) }
        state.current?.let { add(it.coverUrl) }
    }.filter { it.isNotBlank() }.distinct()
}
