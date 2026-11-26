package com.mytv.mymusic.desktop.win

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.Window as AwtWindow

/**
 * 从"按时间排序的歌词行"里定位当前行。
 *
 * 抽成纯函数是为了能脱离窗口单测——悬浮歌词最容易出错的不是渲染，
 * 而是"seek 到 0 但第一行是负时间""两行时间相同""歌词比音频长"这些边界。
 */
object LyricResolver {

    /** 当前行下标；没有任何已到达的行时返回 -1。 */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** 当前行文本；还没到第一行时给空串（而不是提前显示第一句）。 */
    fun current(lines: List<LyricLine>, positionMs: Long): String =
        indexAt(lines, positionMs).let { if (it < 0) "" else lines[it].text }

    /** 下一行文本（浮层用来做"渐入"提示）。 */
    fun next(lines: List<LyricLine>, positionMs: Long): String {
        val index = indexAt(lines, positionMs)
        val nextIndex = index + 1
        return if (nextIndex in lines.indices) lines[nextIndex].text else ""
    }
}

/** 悬浮窗标题；[findTopLevelWindow] 靠它把句柄和 AWT 窗口对上。 */
const val FLOATING_LYRICS_TITLE: String = "myMusic 悬浮歌词"

/**
 * 置顶无边框悬浮歌词窗。
 *
 * 三个硬约束：
 *  1. **不抢焦点**：`focusable = false` + 额外的 `WS_EX_NOACTIVATE`。CMP 的 `focusable`
 *     在 Windows 上映射为 `WS_EX_NOACTIVATE`，但版本差异下不一定可靠，所以两条都做；
 *  2. **不进 Alt+Tab**：`WS_EX_TOOLWINDOW`，否则切窗口会多出一个"悬浮歌词"项；
 *  3. **可拖动**：无边框就没有标题栏，靠鼠标拖拽改窗口位置。
 *
 * 注意：这个窗口只在 Windows 上创建。其它平台没有 `WS_EX_NOACTIVATE` 的等价物，
 * 做出来的"悬浮"会抢焦点，不如不做——[WindowsShell.toggleFloatingLyrics] 会自动跳过。
 */
@Composable
fun FloatingLyricsWindow(
    visible: Boolean,
    nowPlaying: NowPlaying,
    onClose: () -> Unit,
) {
    if (!Win32.isWindows) return

    val windowState = rememberWindowState(
        size = DpSize(640.dp, 132.dp),
        // 默认落在屏幕底部中间，用户拖走后由系统记住相对位置
        position = WindowPosition(Alignment.BottomCenter),
    )
    val scope = rememberCoroutineScope()

    Window(
        onCloseRequest = onClose,
        state = windowState,
        visible = visible,
        title = FLOATING_LYRICS_TITLE,
        undecorated = true,
        transparent = true,
        resizable = false,
        // 关键：不抢焦点，点它不会把前台窗口挤走
        focusable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
    ) {
        // 窗口真正可见之后才能拿到句柄：过早调用 SetWindowLongPtrW 会作用在 0 上
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            val window = window
            repeat(20) {
                val hwnd = Win32.findTopLevelWindow(FLOATING_LYRICS_TITLE)
                if (hwnd != null) {
                    applyNoActivateStyles(hwnd)
                    return@LaunchedEffect
                }
                delay(50)
            }
        }

        // DPI 缩放：窗口位置是 dp，鼠标位移是像素，必须换算，否则 125%/150% 缩放下拖动会跑偏
        val scale = remember { (window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6151820))
                .pointerInput(Unit) {
                    // 无边框窗口只能自己实现拖动：把像素位移换算成 dp 后累加到窗口位置。
                    // 起点用当前窗口的屏幕坐标——WindowPosition.Aligned 拿不到绝对坐标。
                    detectDragGestures { change, drag ->
                        change.consume()
                        val location = window.location
                        val nextX = (location.x + drag.x) / scale
                        val nextY = (location.y + drag.y) / scale
                        windowState.position = WindowPosition(nextX.dp, nextY.dp)
                    }
                }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            MaterialTheme {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val lines = nowPlaying.lyrics
                    val current = LyricResolver.current(lines, nowPlaying.positionMs)
                    val next = LyricResolver.next(lines, nowPlaying.positionMs)
                    Text(
                        text = current.ifBlank { nowPlaying.displayLine },
                        color = Color(0xFFF2F5FF),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = next.ifBlank { " " },
                        color = Color(0x99F2F5FF),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}


/**
 * 给窗口补上 `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`。
 *
 * 抽成公开函数是为了让自检能对"任意窗口句柄"验证这条路径，而不必真的等悬浮窗出现。
 */
fun applyNoActivateStyles(hwnd: com.sun.jna.Pointer): Boolean {
    val user32 = Win32.user32
    val current = user32.GetWindowLongPtrW(hwnd, Win32.GWL_EXSTYLE)
    val desired = current or Win32.WS_EX_NOACTIVATE.toLong() or Win32.WS_EX_TOOLWINDOW.toLong()
    if (desired == current) return true
    user32.SetWindowLongPtrW(hwnd, Win32.GWL_EXSTYLE, desired)
    return (user32.GetWindowLongPtrW(hwnd, Win32.GWL_EXSTYLE) and Win32.WS_EX_NOACTIVATE.toLong()) != 0L
}

/** 悬浮窗用的最小窗口尺寸，防止被用户拖成负数的窗口状态。 */
internal val FLOATING_MIN_SIZE = Dimension(320, 80)
