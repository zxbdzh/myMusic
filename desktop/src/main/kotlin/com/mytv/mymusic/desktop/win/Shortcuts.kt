package com.mytv.mymusic.desktop.win

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut

/**
 * 菜单分组：菜单栏按声明顺序渲染「文件 / 播放 / 视图 / 帮助」四栏。
 * [mnemonic] 是 Alt+字母 打开菜单的按键（Windows 惯例）。
 */
enum class MenuGroup(val label: String, val mnemonic: Char) {
    FILE("文件", 'F'),
    PLAYBACK("播放", 'P'),
    VIEW("视图", 'V'),
    HELP("帮助", 'H'),
}

/**
 * 一个快捷键组合（纯数据）。
 *
 * 刻意不直接用 Compose 的 [KeyShortcut] 作为唯一表示：单测要能不构造 KeyEvent 就验证
 * 「某个动作对应哪些键」；渲染菜单时再用 [toKeyShortcut] 转过去，让 Compose 自动画右侧提示，
 * 不手写 "Ctrl+F" 这类字符串。
 */
data class ShortcutKey(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun toKeyShortcut(): KeyShortcut = KeyShortcut(key = key, ctrl = ctrl, shift = shift, alt = alt)

    /** 人类可读的写法（"Ctrl+F"、"Shift+←"）；只用于对话框与文档，菜单栏由 Compose 自动渲染。 */
    fun displayText(): String = buildList {
        if (ctrl) add("Ctrl")
        if (shift) add("Shift")
        if (alt) add("Alt")
        add(keyLabel())
    }.joinToString("+")

    private fun keyLabel(): String = when (key) {
        Key.Spacebar -> "Space"
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        Key.DirectionUp -> "↑"
        Key.DirectionDown -> "↓"
        Key.Escape -> "Esc"
        Key.Comma -> ","
        // Compose 的 Key 是 value class，没有 name 属性；toString() 形如 "Key: F"
        else -> key.toString().removePrefix("Key: ")
    }
}

/**
 * 应用内动作：菜单项与快捷键共用同一份定义。
 *
 * 与 [GlobalHotkeys] 的媒体键**故意不重叠**——那些是系统级热键（窗口失焦也生效，
 * 由 RegisterHotKey 注册）；这里全部只在窗口聚焦时由 Compose 的 key 事件分发。
 * 冲突处理见 docs/pc/WINDOW-CHROME.md 的「快捷键分工」一节。
 */
enum class DesktopAction(
    val label: String,
    val shortcut: ShortcutKey? = null,
    /** 属于哪个菜单；null = 纯键盘动作（Esc / Ctrl+F 这类不占菜单栏的动作）。 */
    val group: MenuGroup? = null,
    /** false 时菜单项灰显：能力还没接，见 WINDOW-CHROME.md 的「菜单能力边界」。 */
    val enabled: Boolean = true,
) {
    // ---- 文件 ----
    IMPORT_PLAYLIST("导入歌单…", ShortcutKey(Key.I, ctrl = true), MenuGroup.FILE, enabled = false),
    EXPORT_PLAYLIST("导出歌单…", ShortcutKey(Key.E, ctrl = true), MenuGroup.FILE, enabled = false),
    QUIT("退出", ShortcutKey(Key.Q, ctrl = true), MenuGroup.FILE),

    // ---- 播放 ----
    PLAY_PAUSE("播放/暂停", ShortcutKey(Key.Spacebar), MenuGroup.PLAYBACK),
    PREVIOUS("上一首", ShortcutKey(Key.DirectionLeft, ctrl = true), MenuGroup.PLAYBACK),
    NEXT("下一首", ShortcutKey(Key.DirectionRight, ctrl = true), MenuGroup.PLAYBACK),
    VOLUME_UP("音量 +", ShortcutKey(Key.DirectionUp), MenuGroup.PLAYBACK),
    VOLUME_DOWN("音量 −", ShortcutKey(Key.DirectionDown), MenuGroup.PLAYBACK),

    // ---- 视图 ----
    REFRESH("刷新", ShortcutKey(Key.F5), MenuGroup.VIEW),
    FULLSCREEN("全屏", ShortcutKey(Key.F11), MenuGroup.VIEW),
    SHOW_PLAYER_PANE("显示播放窗格", ShortcutKey(Key.P, ctrl = true, shift = true), MenuGroup.VIEW),
    SHOW_LYRICS("显示歌词", ShortcutKey(Key.L, ctrl = true), MenuGroup.VIEW),

    // ---- 帮助 ----
    SHORTCUT_HELP("快捷键一览", ShortcutKey(Key.F1), MenuGroup.HELP),
    ABOUT("关于 myMusic", null, MenuGroup.HELP),

    // ---- 纯键盘动作（不进菜单栏）----
    FOCUS_SEARCH("搜索", ShortcutKey(Key.F, ctrl = true)),
    OPEN_SETTINGS("设置", ShortcutKey(Key.Comma, ctrl = true)),
    CLOSE_OVERLAY("关闭覆盖层", ShortcutKey(Key.Escape)),
    SEEK_BACKWARD("快退 5 秒", ShortcutKey(Key.DirectionLeft)),
    SEEK_FORWARD("快进 5 秒", ShortcutKey(Key.DirectionRight)),
    /** 保留现有行为：Ctrl+P 打开海报墙（与「显示播放窗格」区分开，后者是 Ctrl+Shift+P）。 */
    TOGGLE_POSTER_WALL("海报墙", ShortcutKey(Key.P, ctrl = true)),
    ;

    /** 是否出现在菜单栏里。 */
    val inMenu: Boolean get() = group != null
}

/**
 * 快捷键解析（纯函数）。
 *
 * 只做「按键 + 修饰键 → 动作」的精确匹配，不碰状态、不碰 Compose 事件对象：
 * 这样单测可以逐个动作验一遍，真机上的分发只需要把 KeyEvent 的三要素喂进来。
 */
object Shortcuts {

    /** 当前注册了快捷键的动作（顺序与 [DesktopAction] 声明一致，便于文档与测试对齐）。 */
    val bindings: List<DesktopAction> = DesktopAction.entries.filter { it.shortcut != null }

    /**
     * 匹配动作；没有对应动作时返回 null。
     *
     * 修饰键**精确匹配**：`Shift+←` 是快退、`Ctrl+←` 是上一首、`←` 不做事——
     * 不做「多按一个 Shift 也算」的宽容匹配，避免误触。
     */
    fun resolve(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): DesktopAction? = bindings.firstOrNull { action ->
        val shortcut = action.shortcut ?: return@firstOrNull false
        shortcut.key == key &&
            shortcut.ctrl == ctrl &&
            shortcut.shift == shift &&
            shortcut.alt == alt
    }

    /** 某个菜单分组里的动作（按声明顺序）。 */
    fun actionsIn(group: MenuGroup): List<DesktopAction> =
        DesktopAction.entries.filter { it.group == group }
}
