package com.mytv.mymusic.desktop.win

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * 系统托盘（通知区域图标 + 右键菜单）。
 *
 * 用 AWT 的 [SystemTray] 而不是 shell 的 `Shell_NotifyIcon`：
 * `SystemTray` 内部就是 `Shell_NotifyIcon`，但替我们处理了窗口过程、DPI 缩放与
 * 消息循环；自己写一遍只会多一份不能跨平台的代码。Compose Desktop 本来就跑在
 * AWT 之上，不引入新依赖。
 *
 * 线程模型：`TrayIcon`/`PopupMenu` 的方法都必须在 AWT 事件线程上调用，而菜单项的
 * 回调也在该线程触发。状态更新因此统一 `EventQueue.invokeLater` 回投——
 * 否则在 Windows 上会出现"菜单偶发不弹出"的经典竞态。
 */
class TrayControl(
    private val sink: MediaCommandSink,
    private val onShowWindow: () -> Unit,
    private val onToggleLyrics: () -> Unit,
    private val onQuit: () -> Unit,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var trayIcon: TrayIcon? = null

    @Volatile
    private var playPauseItem: MenuItem? = null

    @Volatile
    private var installed = false

    /** 托盘是否真的加上去了（无桌面会话/被策略禁用时为 false）。 */
    val isInstalled: Boolean
        get() = installed

    fun install(): TrayControl {
        if (!SystemTray.isSupported()) {
            return this
        }
        EventQueue.invokeLater {
            runCatching {
                val menu = PopupMenu()
                menu.add(menuItem("显示主窗口") { onShowWindow() })
                menu.addSeparator()

                // 标签随播放状态在"播放/暂停"之间切换，避免用户看到两个都不对
                val playPause = menuItem("播放") { sink.dispatch(MediaCommand.TOGGLE_PLAY_PAUSE) }
                playPauseItem = playPause
                menu.add(playPause)
                menu.add(menuItem("上一首") { sink.dispatch(MediaCommand.PREVIOUS) })
                menu.add(menuItem("下一首") { sink.dispatch(MediaCommand.NEXT) })
                menu.add(menuItem("停止") { sink.dispatch(MediaCommand.STOP) })
                menu.addSeparator()
                menu.add(menuItem("悬浮歌词") { onToggleLyrics() })
                menu.addSeparator()
                menu.add(menuItem("退出") { onQuit() })

                val icon = TrayIcon(renderIcon(playing = false), "myMusic", menu).apply {
                    isImageAutoSize = true
                    // 双击托盘 = 显示主窗口，是 Windows 上的普遍预期
                    addActionListener { onShowWindow() }
                }
                SystemTray.getSystemTray().add(icon)
                trayIcon = icon
                installed = true
            }.onFailure {
                // 托盘不可用不影响主窗口：只是少一个入口
                System.err.println("myMusic: 托盘初始化失败 ${it.message}")
            }
        }

        scope.launch {
            sink.state.collect { state ->
                val text = state.displayLine.take(120)
                EventQueue.invokeLater {
                    val icon = trayIcon ?: return@invokeLater
                    icon.toolTip = text
                    icon.image = renderIcon(state.isPlaying)
                    playPauseItem?.label = if (state.isPlaying) "暂停" else "播放"
                }
            }
        }
        return this
    }

    private fun menuItem(label: String, action: () -> Unit): MenuItem =
        MenuItem(label).apply { addActionListener { runCatching { action() } } }

    override fun close() {
        scope.cancel()
        val icon = trayIcon
        trayIcon = null
        if (icon != null) {
            EventQueue.invokeLater { runCatching { SystemTray.getSystemTray().remove(icon) } }
        }
        installed = false
    }

    private companion object {
        private const val SIZE = 16

        /**
         * 托盘图标：圆角底 + 播放三角 / 暂停双竖条。
         *
         * 用代码画而不是读 `icons/app.ico`：托盘图标需要按"是否在播放"变体，
         * 而且 AWT 的 Image 与 ICO 解码之间没有标准桥，画一次最省事。
         * 颜色取与 app.ico 一致的青绿→靛蓝。
         */
        fun renderIcon(playing: Boolean): BufferedImage {
            val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
            val g: Graphics2D = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = if (playing) Color(38, 198, 173) else Color(110, 120, 145)
            g.fillRoundRect(0, 0, SIZE, SIZE, 6, 6)
            g.color = Color.WHITE
            g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (playing) {
                g.drawLine(6, 4, 6, 12)
                g.drawLine(10, 4, 10, 12)
            } else {
                g.fillPolygon(intArrayOf(6, 6, 12), intArrayOf(4, 12, 8), 3)
            }
            g.dispose()
            return image
        }
    }
}
