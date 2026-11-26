package com.mytv.mymusic.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mytv.mymusic.desktop.state.CoreMusicCatalog
import com.mytv.mymusic.desktop.state.DefaultMusicStore
import com.mytv.mymusic.desktop.state.DemoFixtures
import com.mytv.mymusic.desktop.state.DesktopPaths
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.SearchKind
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.AppShell
import com.mytv.mymusic.desktop.ui.components.RemoteImages
import com.mytv.mymusic.desktop.win.FloatingLyricsWindow
import com.mytv.mymusic.desktop.win.LyricLine
import com.mytv.mymusic.desktop.win.MediaCommand
import com.mytv.mymusic.desktop.win.MediaControlTarget
import com.mytv.mymusic.desktop.win.NowPlaying
import com.mytv.mymusic.desktop.win.WindowsShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Dimension
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
    screenshotTarget(args)?.let { dir ->
        ScreenshotRunner.run(File(dir))
        return
    }
    if (args.any { it == WIN_SELF_TEST_FLAG || it.startsWith("$WIN_SELF_TEST_FLAG=") }) {
        System.err.println("Windows 集成自检需要真实窗口，请用： ./gradlew :desktop:runWinSelfTest")
        return
    }
    launchDesktop()
}

private const val SCREENSHOT_FLAG = "--screenshot"
private const val WIN_SELF_TEST_FLAG = "--win-selftest"
private const val SEEK_STEP_MS = 10_000L
private const val WINDOW_WIDTH = 1440
private const val WINDOW_HEIGHT = 900

/** `--screenshot=<dir>` 与 `--screenshot <dir>` 两种写法都认（文档里两种都出现过）。 */
private fun screenshotTarget(args: Array<String>): String? {
    args.firstOrNull { it.startsWith("$SCREENSHOT_FLAG=") }?.let { return it.substringAfter('=').trim('"', '\'') }
    val index = args.indexOf(SCREENSHOT_FLAG)
    return if (index >= 0 && index + 1 < args.size) args[index + 1].trim('"', '\'') else null
}

/** 正常启动：系统集成（托盘/热键/任务栏/SMTC）+ 主窗口 + 悬浮歌词窗。 */
private fun launchDesktop() = application {
    val store = remember { DefaultMusicStore() }
    // 后台验收用：MYMUSIC_BACKGROUND=1 时窗口不抢焦点（默认行为仍是正常激活）
    val background = remember { System.getenv("MYMUSIC_BACKGROUND") == "1" }

    // 托盘 / 全局热键 / 任务栏按钮 / SMTC 由 WindowsShell 装配；命令流回 store 的播放方法
    val shell = remember { WindowsShell.create(StoreMediaTarget(store)).install { exitApplication() } }
    // 状态流：store → 托盘 tooltip / SMTC / 悬浮歌词窗（单向，只有这一处发布）
    LaunchedEffect(store, shell) {
        store.state.collect { state -> shell.sink.publish(state.toNowPlaying()) }
    }
    val nowPlaying by shell.sink.state.collectAsState()

    Window(
        // 关窗只收进托盘；托盘"退出"才真的退出（由 install 的 onQuit 负责）
        onCloseRequest = { shell.hideToTray() },
        // 标题必须与 WindowsShell.DEFAULT_MAIN_TITLE 逐字一致：任务栏按钮是按"pid + 标题"找 HWND 的
        title = WindowsShell.DEFAULT_MAIN_TITLE,
        state = rememberWindowState(width = WINDOW_WIDTH.dp, height = WINDOW_HEIGHT.dp),
        onKeyEvent = { event -> handleShortcut(event, store) },
    ) {
        // 在内容组合期同步设置：此时窗口还没 show，autoRequestFocus 才会生效
        if (background) runCatching { window.isAutoRequestFocus = false }
        DisposableEffect(Unit) {
            onDispose {
                shell.close()
                store.shutdown()
            }
        }
        LaunchedEffect(Unit) {
            // 1120 是"导航栏 + 两栏内容"的下限；再窄布局会开始挤
            window.minimumSize = Dimension(1120, 720)
            // 任务栏缩略图按钮需要 HWND：窗口句柄异步就绪，用重试而不是 sleep（会卡住事件线程）
            repeat(20) {
                if (shell.attachMainWindow(window, WindowsShell.DEFAULT_MAIN_TITLE)) return@LaunchedEffect
                delay(60)
            }
        }
        AppShell(store)
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

/** 全局快捷键。返回 true 表示事件已消费。 */
private fun handleShortcut(event: KeyEvent, store: MusicStore): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val state = store.state.value
    return when {
        event.isCtrlPressed && event.key == Key.L -> {
            store.openLyrics()
            true
        }

        event.isCtrlPressed && event.key == Key.P -> {
            store.openPosterWall()
            true
        }

        event.key == Key.Spacebar -> {
            store.togglePlay()
            true
        }

        event.key == Key.DirectionLeft -> {
            store.seekTo((state.positionMs - SEEK_STEP_MS).coerceAtLeast(0L))
            true
        }

        event.key == Key.DirectionRight -> {
            store.seekTo(state.positionMs + SEEK_STEP_MS)
            true
        }

        event.key == Key.Escape -> when {
            state.lyricsOpen -> {
                store.closeLyrics()
                true
            }

            state.playerOpen -> {
                store.closePlayer()
                true
            }

            state.posterOpen -> {
                store.closePosterWall()
                true
            }

            else -> false
        }

        else -> false
    }
}

/**
 * 截图模式：逐场景渲染 PNG。
 *
 * 每个场景一个独立 store（互不串状态）；先 `bootstrap()` 灌满数据，再执行场景动作，
 * 最后等异步取流 / 歌词落地。场景里的 `seekTo` 是必需的：
 * 定格在 0 秒的截图看不出进度条、歌词高亮是不是活的。
 */
private object ScreenshotRunner {

    private const val WIDTH = 1600
    private const val HEIGHT = 1000

    private class Shot(val name: String, val prepare: suspend (MusicStore) -> Unit)

    fun run(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("截图目录无法创建：${dir.absolutePath}")
            return
        }
        val shots = listOf(
            Shot("01-charts") { store ->
                store.navigate(Route.Charts)
                val queue = DemoFixtures.chartTracks("hot")
                queue.firstOrNull()?.let { store.playTrack(it, queue) }
                // 取流会把进度重置到 0，先等它落地再 seek
                delay(300)
                store.seekTo(48_000L)
            },
            Shot("02-playlists") { store -> store.navigate(Route.Playlists) },
            Shot("03-search") { store ->
                store.navigate(Route.Search)
                store.updateQuery("晴天")
                store.submitSearch()
                delay(200)
            },
            Shot("04-library") { store -> store.navigate(Route.Library) },
            Shot("05-player") { store ->
                val first = DemoFixtures.tracks.first { it.id == "t01" }
                val queue = listOf(first) + DemoFixtures.chartTracks("hot").filterNot { it.id == first.id }
                store.playTrack(first, queue)
                delay(300)
                store.seekTo(32_000L)
                store.openPlayer()
            },
            Shot("06-lyrics") { store ->
                val first = DemoFixtures.tracks.first { it.id == "t01" }
                store.playTrack(first, listOf(first))
                delay(300)
                store.seekTo(72_000L)
                store.openLyrics()
            },
            Shot("07-poster-wall") { store ->
                val queue = DemoFixtures.chartTracks("hot")
                queue.firstOrNull()?.let { store.playTrack(it, queue) }
                store.openPosterWall()
            },
            Shot("08-settings") { store -> store.navigate(Route.Settings) },
        )

        shots.forEach { shot -> render(dir, shot) }
        println("[screenshot] 全部完成：${dir.absolutePath}")
    }

    private fun render(dir: File, shot: Shot) {
        val store = DefaultMusicStore()
        try {
            runBlocking {
                store.bootstrap()
                shot.prepare(store)
                delay(700)
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
}
