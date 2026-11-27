package com.mytv.mymusic.desktop.win

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.math.roundToInt

/**
 * 主窗口顶部菜单栏（「文件 / 播放 / 视图 / 帮助」）。
 *
 * 三个约束：
 *  1. **快捷键提示由 Compose 渲染**：菜单项的 `shortcut = KeyShortcut(...)` 会自动在右侧
 *     画出 "Space"、"Ctrl+F"，不在 label 里手写字符串——否则改键位要改两处、中英文环境不一致；
 *  2. **动作与快捷键同一份定义**：[DesktopAction] 同时是菜单项和 [Shortcuts] 的匹配来源，
 *     菜单里能点的东西与键盘上能按的东西永远一致；
 *  3. **能力边界明确**：导入/导出、播放模式这些菜单项在能力接通前 `enabled = false`（灰显），
 *     不做"点了没反应"的假入口——理由逐条写在 docs/pc/WINDOW-CHROME.md。
 *
 * 这个函数必须挂在 `FrameWindowScope` 上（`Window { AppMenuBar(...) }`），
 * Compose Desktop 的 MenuBar 只能挂在窗口作用域里。
 */
@Composable
fun FrameWindowScope.AppMenuBar(
    playing: Boolean,
    volume: Float,
    onAction: (DesktopAction) -> Unit,
) {
    MenuBar {
        Menu(MenuGroup.FILE.label, mnemonic = MenuGroup.FILE.mnemonic) {
            menuItem(DesktopAction.IMPORT_PLAYLIST, onAction)
            menuItem(DesktopAction.EXPORT_PLAYLIST, onAction)
            Separator()
            menuItem(DesktopAction.QUIT, onAction)
        }

        Menu(MenuGroup.PLAYBACK.label, mnemonic = MenuGroup.PLAYBACK.mnemonic) {
            // 播放项的文案随状态变（Spotify / iTunes 的习惯）：正在播时显示"暂停"
            menuItem(
                action = DesktopAction.PLAY_PAUSE,
                text = if (playing) "暂停" else "播放",
                onClick = onAction,
            )
            menuItem(DesktopAction.PREVIOUS, onAction)
            menuItem(DesktopAction.NEXT, onAction)
            Separator()
            // 当前音量：只显示、不可点（真正的调节在下面两项）
            Item("音量 ${(volume.coerceIn(0f, 1f) * 100).roundToInt()}%", enabled = false, onClick = {})
            menuItem(DesktopAction.VOLUME_UP, onAction)
            menuItem(DesktopAction.VOLUME_DOWN, onAction)
            Separator()
            // 播放模式：队列层的模式开关还没接（见文档），先灰显，避免点了没反应
            Menu("播放模式", enabled = false) {
                RadioButtonItem("顺序播放", selected = true, enabled = false, onClick = {})
                RadioButtonItem("单曲循环", selected = false, enabled = false, onClick = {})
                RadioButtonItem("随机播放", selected = false, enabled = false, onClick = {})
            }
        }

        Menu(MenuGroup.VIEW.label, mnemonic = MenuGroup.VIEW.mnemonic) {
            menuItem(DesktopAction.REFRESH, onAction)
            menuItem(DesktopAction.FULLSCREEN, onAction)
            Separator()
            menuItem(DesktopAction.SHOW_PLAYER_PANE, onAction)
            menuItem(DesktopAction.SHOW_LYRICS, onAction)
        }

        Menu(MenuGroup.HELP.label, mnemonic = MenuGroup.HELP.mnemonic) {
            menuItem(DesktopAction.SHORTCUT_HELP, onAction)
            Separator()
            menuItem(DesktopAction.ABOUT, onAction)
        }
    }
}

/** 一个菜单项：label / 快捷键 / 可用性全部取自 [DesktopAction]。 */
@Composable
private fun MenuScope.menuItem(
    action: DesktopAction,
    onClick: (DesktopAction) -> Unit,
) {
    menuItem(action, action.label, onClick)
}

@Composable
private fun MenuScope.menuItem(
    action: DesktopAction,
    text: String,
    onClick: (DesktopAction) -> Unit,
) {
    Item(
        text = text,
        shortcut = action.shortcut?.toKeyShortcut(),
        enabled = action.enabled,
        onClick = { onClick(action) },
    )
}

/** 快捷键一览对话框（F1 / 帮助菜单）。用 AWT 原生对话框：菜单本身也是系统菜单位，风格一致。 */
fun showShortcutHelp() {
    val rows = buildString {
        appendLine("应用内快捷键（窗口聚焦时生效；系统媒体键由 SMTC 处理，见帮助文档）")
        appendLine()
        DesktopAction.entries
            .filter { it.shortcut != null }
            .sortedWith(compareBy({ it.group?.ordinal ?: Int.MAX_VALUE }, { it.ordinal }))
            .forEach { action ->
                appendLine("${action.shortcut!!.displayText().padEnd(14)}${action.label}")
            }
    }
    showTextDialog("快捷键一览", rows)
}

/** 关于对话框。 */
fun showAbout() {
    showTextDialog(
        title = "关于 myMusic",
        text = buildString {
            appendLine("myMusic 桌面版（Compose Multiplatform Desktop）")
            appendLine()
            appendLine("Android 客户端的一比一复刻，Android 与桌面共用同一套 :core。")
            appendLine("播放后端：libVLC（随包分发）")
            appendLine("系统集成：托盘 / 全局媒体热键 / 任务栏缩略图按钮 / SMTC 媒体浮层 / 悬浮歌词窗")
        },
    )
}

private fun showTextDialog(title: String, text: String) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    JOptionPane.showMessageDialog(null, text, title, JOptionPane.INFORMATION_MESSAGE)
}
