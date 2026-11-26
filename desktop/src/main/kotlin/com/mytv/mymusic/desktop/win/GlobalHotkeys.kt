package com.mytv.mymusic.desktop.win

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 一条全局热键。
 *
 * [virtualKey] / [modifiers] 直接用 Win32 的 `VK_*` / `MOD_*` 数值，不做字符串解析：
 * 目前只有内置默认值（见 [MediaHotkeys]），等设置页需要可配置时再加一层映射，
 * 免得现在就要维护"字符串 → 虚拟键码"的表。
 */
data class HotkeySpec(
    val id: Int,
    val label: String,
    val modifiers: Int,
    val virtualKey: Int,
    val onTrigger: () -> Unit,
)

/**
 * 全局媒体热键。
 *
 * 为什么必须有独立消息循环线程：
 * `RegisterHotKey(NULL, id, ...)` 把热键挂到**调用线程**的消息队列上，`WM_HOTKEY` 会被投递到
 * 该线程。AWT 事件线程的消息循环由 JDK 自己跑，插不进我们的分发逻辑，所以这里开一条
 * 只跑 `GetMessageW` 的线程，`GetMessageW` 阻塞等待即零 CPU 占用。
 *
 * 停止方式：`PostThreadMessageW(WM_QUIT)` 让循环退出，随后**在同一线程**里
 * `UnregisterHotKey`——MSDN 要求注册与注销同线程，跨线程注销在部分系统上会静默失败。
 */
class GlobalHotkeys(private val specs: List<HotkeySpec>) : AutoCloseable {

    private val registered = ConcurrentHashMap<String, Boolean>()

    private val ready = CountDownLatch(1)

    @Volatile
    private var threadId: Int = 0

    @Volatile
    private var loop: Thread? = null

    /** 每个热键是否真的注册成功（媒体键可能被别的播放器占用）。 */
    val registrations: Map<String, Boolean>
        get() = registered.toMap()

    /** 注册成功的热键数量；0 表示这台机器上媒体键全被占用。 */
    val registeredCount: Int
        get() = registered.values.count { it }

    fun start(): GlobalHotkeys {
        Win32.requireX64()
        val worker = thread(name = "mymusic-global-hotkey", isDaemon = true) {
            threadId = Win32.kernel32.GetCurrentThreadId()
            val accepted = specs.filter { spec ->
                val ok = runCatching {
                    Win32.user32.RegisterHotKey(
                        null,
                        spec.id,
                        spec.modifiers or Win32.MOD_NOREPEAT,
                        spec.virtualKey,
                    )
                }.getOrDefault(false)
                registered[spec.label] = ok
                ok
            }
            ready.countDown()

            val byId = accepted.associateBy { it.id }
            val msg = Msg()
            while (true) {
                // GetMessageW 返回 0 = WM_QUIT，-1 = 错误；两种都退出循环
                val result = Win32.user32.GetMessageW(msg.pointer, null, 0, 0)
                if (result <= 0) break
                msg.read()
                if (msg.message == Win32.WM_HOTKEY) {
                    byId[msg.wParam.toInt()]?.let { spec ->
                        runCatching { spec.onTrigger() }
                    }
                }
            }
            accepted.forEach { spec ->
                runCatching { Win32.user32.UnregisterHotKey(null, spec.id) }
            }
        }
        loop = worker
        // 等注册完成再返回：调用方（以及自检）需要立刻读到 registrations
        ready.await(2, TimeUnit.SECONDS)
        return this
    }

    /**
     * 直接向热键线程投递一条 `WM_HOTKEY`。
     *
     * 测试用：`SendInput` 注入媒体键在"被别的会话抢先注册"的机器上会失败，
     * 而这条路径验证的是同一段分发逻辑（`WM_HOTKEY` → 命令）。
     */
    fun postHotkey(id: Int): Boolean =
        threadId != 0 && Win32.user32.PostThreadMessageW(threadId, Win32.WM_HOTKEY, id.toLong(), 0)

    override fun close() {
        val worker = loop ?: return
        loop = null
        if (threadId != 0) {
            runCatching { Win32.user32.PostThreadMessageW(threadId, Win32.WM_QUIT, 0, 0) }
        }
        runCatching { worker.join(1500) }
    }
}

/**
 * 默认热键表。
 *
 * 媒体键（播放暂停 / 上一首 / 下一首 / 停止）是**系统级**的：注册成功后无论是哪个
 * 窗口在前台，按键都落到本应用，这也正是"全局媒体键"的含义。
 * 同时给一组 `Ctrl+Alt+*` 组合键——键盘上没有媒体键的机型（或媒体键被其它播放器
 * 独占时）仍然有可用入口。
 */
object MediaHotkeys {

    /** 热键 id 从 1 开始：0 保留给"无效"。 */
    private const val BASE_ID = 1

    fun defaultBindings(sink: MediaCommandSink, onToggleLyrics: () -> Unit, onShowWindow: () -> Unit): List<HotkeySpec> =
        listOf(
            HotkeySpec(BASE_ID, "媒体键-播放暂停", 0, Win32.MEDIA_KEY_PLAY_PAUSE) {
                sink.dispatch(MediaCommand.TOGGLE_PLAY_PAUSE)
            },
            HotkeySpec(BASE_ID + 1, "媒体键-下一首", 0, Win32.MEDIA_KEY_NEXT) {
                sink.dispatch(MediaCommand.NEXT)
            },
            HotkeySpec(BASE_ID + 2, "媒体键-上一首", 0, Win32.MEDIA_KEY_PREV) {
                sink.dispatch(MediaCommand.PREVIOUS)
            },
            HotkeySpec(BASE_ID + 3, "媒体键-停止", 0, Win32.MEDIA_KEY_STOP) {
                sink.dispatch(MediaCommand.STOP)
            },
            HotkeySpec(BASE_ID + 4, "Ctrl+Alt+P 播放暂停", Win32.MOD_CONTROL or Win32.MOD_ALT, Win32.VK_P) {
                sink.dispatch(MediaCommand.TOGGLE_PLAY_PAUSE)
            },
            HotkeySpec(BASE_ID + 5, "Ctrl+Alt+→ 下一首", Win32.MOD_CONTROL or Win32.MOD_ALT, Win32.VK_RIGHT) {
                sink.dispatch(MediaCommand.NEXT)
            },
            HotkeySpec(BASE_ID + 6, "Ctrl+Alt+← 上一首", Win32.MOD_CONTROL or Win32.MOD_ALT, Win32.VK_LEFT) {
                sink.dispatch(MediaCommand.PREVIOUS)
            },
            HotkeySpec(BASE_ID + 7, "Ctrl+Alt+L 悬浮歌词", Win32.MOD_CONTROL or Win32.MOD_ALT, Win32.VK_L) {
                onToggleLyrics()
            },
            HotkeySpec(BASE_ID + 8, "Ctrl+Alt+M 显示窗口", Win32.MOD_CONTROL or Win32.MOD_ALT, Win32.VK_M) {
                onShowWindow()
            },
        )
}
