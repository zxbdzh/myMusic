package com.mytv.mymusic.desktop.win

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷键映射单测：每个动作至少一条，外加「修饰键精确匹配」与显示文本。
 *
 * 只测纯函数 [Shortcuts.resolve] / [ShortcutKey.displayText]，不构造 KeyEvent——
 * 映射表本身就是最容易出错的地方（漏一个键、Ctrl 与 Shift 写反），
 * 事件分发的正确性交给真机自检（runWinSelfTest 的热键项）。
 */
class ShortcutsTest {

    /** 每个绑定的动作都能被自己的快捷键反向解析出来：漏项、写错修饰键都会在这里红。 */
    @Test
    fun `every binding resolves back to its action`() {
        assertTrue("至少要有 10 个绑定", Shortcuts.bindings.size >= 10)
        Shortcuts.bindings.forEach { action ->
            val shortcut = requireNotNull(action.shortcut) { "${action.name} 没有快捷键" }
            assertEquals(
                "动作 ${action.name} 的快捷键 ${shortcut.displayText()} 解析不一致",
                action,
                Shortcuts.resolve(shortcut.key, shortcut.ctrl, shortcut.shift, shortcut.alt),
            )
        }
    }

    @Test
    fun `space toggles play pause`() {
        assertEquals(DesktopAction.PLAY_PAUSE, Shortcuts.resolve(Key.Spacebar))
    }

    @Test
    fun `ctrl arrows switch tracks`() {
        assertEquals(DesktopAction.PREVIOUS, Shortcuts.resolve(Key.DirectionLeft, ctrl = true))
        assertEquals(DesktopAction.NEXT, Shortcuts.resolve(Key.DirectionRight, ctrl = true))
    }

    @Test
    fun `plain arrows seek and adjust volume`() {
        assertEquals(DesktopAction.SEEK_BACKWARD, Shortcuts.resolve(Key.DirectionLeft))
        assertEquals(DesktopAction.SEEK_FORWARD, Shortcuts.resolve(Key.DirectionRight))
        assertEquals(DesktopAction.VOLUME_UP, Shortcuts.resolve(Key.DirectionUp))
        assertEquals(DesktopAction.VOLUME_DOWN, Shortcuts.resolve(Key.DirectionDown))
    }

    @Test
    fun `search and settings shortcuts`() {
        assertEquals(DesktopAction.FOCUS_SEARCH, Shortcuts.resolve(Key.F, ctrl = true))
        assertEquals(DesktopAction.OPEN_SETTINGS, Shortcuts.resolve(Key.Comma, ctrl = true))
    }

    @Test
    fun `escape closes overlay`() {
        assertEquals(DesktopAction.CLOSE_OVERLAY, Shortcuts.resolve(Key.Escape))
    }

    @Test
    fun `view menu shortcuts`() {
        assertEquals(DesktopAction.REFRESH, Shortcuts.resolve(Key.F5))
        assertEquals(DesktopAction.FULLSCREEN, Shortcuts.resolve(Key.F11))
        assertEquals(DesktopAction.SHOW_LYRICS, Shortcuts.resolve(Key.L, ctrl = true))
        assertEquals(DesktopAction.SHOW_PLAYER_PANE, Shortcuts.resolve(Key.P, ctrl = true, shift = true))
        // Ctrl+P 保留给海报墙，不能与「显示播放窗格」混掉
        assertEquals(DesktopAction.TOGGLE_POSTER_WALL, Shortcuts.resolve(Key.P, ctrl = true))
    }

    @Test
    fun `help shortcuts`() {
        assertEquals(DesktopAction.SHORTCUT_HELP, Shortcuts.resolve(Key.F1))
    }

    /** 修饰键精确匹配：多按/少按修饰键都不算命中，避免误触。 */
    @Test
    fun `modifiers must match exactly`() {
        assertNull(Shortcuts.resolve(Key.Spacebar, ctrl = true))
        assertNull(Shortcuts.resolve(Key.Spacebar, shift = true))
        assertNull(Shortcuts.resolve(Key.DirectionLeft, shift = true))
        assertNull(Shortcuts.resolve(Key.P, ctrl = true, shift = true, alt = true))
        // 没有任何绑定的键
        assertNull(Shortcuts.resolve(Key.Z))
    }

    @Test
    fun `menus only contain grouped actions`() {
        val inMenu = DesktopAction.entries.filter { it.inMenu }
        assertEquals(4, inMenu.map { it.group }.distinct().size)
        assertTrue(inMenu.any { it.group == MenuGroup.FILE })
        assertTrue(inMenu.any { it.group == MenuGroup.PLAYBACK })
        assertTrue(inMenu.any { it.group == MenuGroup.VIEW })
        assertTrue(inMenu.any { it.group == MenuGroup.HELP })
        // 纯键盘动作不占菜单栏
        assertTrue(DesktopAction.CLOSE_OVERLAY.group == null)
        assertTrue(DesktopAction.FOCUS_SEARCH.group == null)
    }

    /** 菜单能力边界：导入/导出在接通前灰显，不能悄悄变成可点。 */
    @Test
    fun `import export stay disabled until wired`() {
        assertEquals(false, DesktopAction.IMPORT_PLAYLIST.enabled)
        assertEquals(false, DesktopAction.EXPORT_PLAYLIST.enabled)
        assertEquals(true, DesktopAction.PLAY_PAUSE.enabled)
    }

    @Test
    fun `display text is human readable`() {
        assertEquals("Space", DesktopAction.PLAY_PAUSE.shortcut!!.displayText())
        assertEquals("Ctrl+F", DesktopAction.FOCUS_SEARCH.shortcut!!.displayText())
        assertEquals("Ctrl+Shift+P", DesktopAction.SHOW_PLAYER_PANE.shortcut!!.displayText())
        assertEquals("←", DesktopAction.SEEK_BACKWARD.shortcut!!.displayText())
        assertEquals("Esc", DesktopAction.CLOSE_OVERLAY.shortcut!!.displayText())
        assertEquals("Ctrl+,", DesktopAction.OPEN_SETTINGS.shortcut!!.displayText())
    }

    @Test
    fun `window title follows now playing`() {
        // 窗口标题与 Win32.findMainWindow 的匹配规则必须一致：等于 myMusic 或以 " - myMusic" 结尾
        assertEquals("myMusic", com.mytv.mymusic.desktop.windowTitle(null))
        assertEquals("myMusic", com.mytv.mymusic.desktop.windowTitle("  "))
        assertEquals(
            "正在播放 - 晴天 - myMusic",
            com.mytv.mymusic.desktop.windowTitle("晴天"),
        )
        assertTrue(com.mytv.mymusic.desktop.windowTitle("晴天").endsWith(" - myMusic"))
    }
}
