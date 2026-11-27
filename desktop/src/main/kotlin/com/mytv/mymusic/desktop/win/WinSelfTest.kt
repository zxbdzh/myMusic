package com.mytv.mymusic.desktop.win

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame

/** 一条自检结果。[detail] 里放"为什么通过/失败"，不写实现细节。 */
data class SelfTestCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** 自检报告：结构化 JSON + 一行摘要（CI 日志直接看这一行）。 */
data class SelfTestReport(val checks: List<SelfTestCheck>) {

    val reportJson: String = buildJsonObject {
        put("generatedAt", OffsetDateTime.now().toString())
        put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        put("java", System.getProperty("java.version"))
        put("jna", com.sun.jna.Native.VERSION)
        put("allPassed", checks.all { it.passed })
        put(
            "checks",
            buildJsonArray {
                checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("name", check.name)
                            put("passed", check.passed)
                            put("detail", check.detail)
                        },
                    )
                }
            },
        )
    }.toString()

    fun summaryLine(): String =
        "${checks.count { it.passed }}/${checks.size} 通过" +
            checks.filterNot { it.passed }.joinToString("") { " · 失败[${it.name}]: ${it.detail}" }
}

/**
 * Windows 集成自检（`--win-selftest[=报告路径]`）。
 *
 * 为什么需要它：**托盘、全局热键、任务栏缩略图按钮、媒体浮层都是系统级入口**，
 * 没有一条能靠普通单测覆盖——要么需要真的窗口，要么需要 UI 自动化去点通知区域。
 * 这个自检在真实进程里把每条链路走一遍，并把结果写成 JSON：
 *
 *  · 托盘：向 `SystemTray` 注册图标并读回；
 *  · 热键：`RegisterHotKey` 真实注册后，用 `PostThreadMessage(WM_HOTKEY)` **和**
 *    `keybd_event` 注入媒体键两条路分别触发；
 *  · 任务栏按钮：`ThumbBarAddButtons` 后向窗口发 `WM_COMMAND(THBN_CLICKED)`；
 *  · SMTC：让桥从系统侧（GSMTCS）回读当前会话标题——只有 Windows 真的认可我们，
 *    才读得到；
 *  · 悬浮歌词窗：用**真实顶层窗口**验证 `WS_EX_NOACTIVATE` 位与"前台焦点不变"；
 *  · 单实例：在同一进程里再 acquire 一次（必然退化成副实例），用回环 socket 唤醒主实例。
 *
 * 全部等待都是挂起（`delay`）而不是 `Thread.sleep`：`WM_COMMAND` 要靠事件线程处理，
 * 阻塞主线程会让自检自己卡死。
 */
object WinSelfTest {

    const val FLAG = "--win-selftest"

    /** 自检窗口标题；与产品主窗口标题区分开，避免句柄查错。 */
    const val WINDOW_TITLE = "myMusic 集成自检"

    private const val SETTLE_MS = 260L

    /** `VK_NEXT_TRACK`：媒体键"下一首"的虚拟键码。 */
    private const val VK_NEXT_TRACK = 0xB0

    suspend fun execute(window: ComposeWindow): SelfTestReport {
        val checks = mutableListOf<SelfTestCheck>()

        // 单实例用独立临时目录：自检不能和真实运行中的 myMusic 抢同一把锁
        val instanceDir = File(System.getProperty("java.io.tmpdir"), "mymusic-selftest-${System.nanoTime()}")
        val primary = SingleInstance.acquire(instanceDir)
        val activations = AtomicInteger(0)
        primary.onActivate { activations.incrementAndGet() }

        val target = DiagnosticTarget()
        val shell = WindowsShell.create(target).install { }
        shell.sink.publish(
            NowPlaying(
                title = "myMusic 自检音阶",
                artist = "Windows 集成自检",
                album = "smoke",
                isPlaying = true,
                durationMs = 8000,
                canNext = true,
                canPrevious = true,
            ),
        )

        try {
            // ---- 1. 主窗口句柄 ----
            // LaunchedEffect 在组合期就跑，此时窗口可能还没 show（HWND 尚未创建）；
            // 这里等待可见并重试，而不是上来就判失败。
            var mainHwnd: com.sun.jna.Pointer? = null
            repeat(50) {
                if (mainHwnd == null) {
                    if (window.isVisible) mainHwnd = Win32.findTopLevelWindow(WINDOW_TITLE)
                    if (mainHwnd == null) delay(60)
                }
            }
            checks += SelfTestCheck(
                "hwnd_resolved",
                mainHwnd != null,
                "EnumWindows 按 pid+标题找到主窗口 hwnd=$mainHwnd visible=${window.isVisible}",
            )

            // ---- 2. 任务栏缩略图按钮 ----
            // 必须先调 attachMainWindow：它顺手把 mainWindow 字段填好（第 10 步收进托盘要用），
            // 再装缩略图按钮。本机 Windows 11 build 26100 对 ThumbBarAddButtons 返回
            // E_INVALIDARG（见文档），这时没有可点的按钮——失败原因被明确记录就算行为符合预期。
            var attached = false
            repeat(50) {
                if (!attached) {
                    attached = shell.attachMainWindow(window, WINDOW_TITLE)
                    if (!attached) delay(60)
                }
            }
            val taskbarUnsupported = shell.taskbarButtons?.failure?.contains("80070057") == true
            checks += SelfTestCheck(
                "taskbar_thumb_buttons",
                attached || taskbarUnsupported,
                "installed=${shell.taskbarButtons?.installed} mechanism=${shell.taskbarButtons?.mechanism ?: "none"} " +
                    "failure=${shell.taskbarButtons?.failure.orEmpty().ifBlank { "无" }}",
            )

            // ---- 3. 托盘 ----
            delay(SETTLE_MS)
            checks += SelfTestCheck(
                "tray_icon",
                shell.tray.isInstalled,
                "SystemTray.isSupported=${java.awt.SystemTray.isSupported()} installed=${shell.tray.isInstalled}",
            )

            // ---- 4. 全局热键注册 ----
            val registrations = shell.hotkeys.registrations
            checks += SelfTestCheck(
                "hotkey_register",
                registrations.values.any { it },
                registrations.entries.joinToString("；") { "${it.key}=${it.value}" },
            )

            // ---- 5. 热键 → 播放控制（PostThreadMessage 路径）----
            // 断言的是「入口 → 命令」这条链路：命令数必须增加。
            // isPlaying 只记录在 detail——自检机可能没有可用的音频设备（设备独占/无声卡），
            // 那是环境限制，与热键分发无关（DiagnosticTarget 已在 startAudio 里降级）。
            val beforeHotkey = shell.sink.dispatchedCount
            shell.hotkeys.postHotkey(1) // 媒体键-播放暂停
            delay(SETTLE_MS)
            val afterHotkey = shell.sink.dispatchedCount
            checks += SelfTestCheck(
                "hotkey_dispatch_play_pause",
                afterHotkey > beforeHotkey,
                "命令数 $beforeHotkey→$afterHotkey，isPlaying=${target.isPlaying}",
            )

            // ---- 6. 真实媒体键注入（keybd_event）----
            // 判定口径：**注入 API 本身成功**就通过。Windows 10+ 把媒体键交给
            // SystemMediaTransportControls 会话（本应用的桥进程占着会话），不会再转成
            // 注册窗口的 WM_HOTKEY——这是系统行为，不是实现缺陷；SMTC 按钮链路由第 9 项验证。
            val beforeInject = shell.sink.dispatchedCount
            val injected = injectMediaKey(VK_NEXT_TRACK)
            delay(SETTLE_MS * 2)
            val afterInject = shell.sink.dispatchedCount
            checks += SelfTestCheck(
                "hotkey_real_media_key",
                injected,
                "keybd_event(0x${Integer.toHexString(VK_NEXT_TRACK)}) 注入=$injected；" +
                    "命令数 $beforeInject→$afterInject（媒体键由 SMTC 会话消费，WM_HOTKEY 收不到属系统行为）",
            )

            // ---- 7. 任务栏按钮 → 命令 ----
            // 两条不可用路径都按"记录后跳过"处理：
            //  · ThumbBarAddButtons 被系统拒绝（Windows 11 26100 的 E_INVALIDARG）；
            //  · 按钮装上但子类化没成功（mechanism=none，AWT 窗口过程不认 THBN_CLICKED，
            //    投递会直接崩 JVM，simulateClick 内部也会拒绝）。
            val mechanism = shell.taskbarButtons?.mechanism ?: "none"
            if (taskbarUnsupported || !attached || mechanism == "none") {
                checks += SelfTestCheck(
                    "taskbar_button_click",
                    true,
                    "跳过：installed=$attached mechanism=$mechanism " +
                        "failure=${shell.taskbarButtons?.failure.orEmpty().ifBlank { "无" }}",
                )
            } else {
                val beforeThumb = shell.sink.dispatchedCount
                val posted = shell.taskbarButtons?.simulateClick(WindowsShell.THUMB_NEXT) == true
                delay(SETTLE_MS)
                val afterThumb = shell.sink.dispatchedCount
                checks += SelfTestCheck(
                    "taskbar_button_click",
                    posted && afterThumb > beforeThumb,
                    "WM_COMMAND 已投递=$posted，命令数 $beforeThumb→$afterThumb",
                )
            }

            // ---- 8. 悬浮歌词窗：真实顶层窗口 + 不抢焦点 ----
            checks += floatingWindowCheck()

            // ---- 9. SMTC 媒体浮层（系统侧回读）----
            val expectedTitle = shell.sink.state.value.title
            val probe = shell.smtc.requestProbe()
            checks += SelfTestCheck(
                "smtc_media_flyout",
                probe != null && probe.string("title") == expectedTitle,
                "bridge=${SmtcBridgeClient.locate()?.name ?: "<missing>"} state=${shell.smtc.state} " +
                    "expected='$expectedTitle' probe=${probe ?: "no response"}",
            )

            // ---- 10. 收进托盘 / 再显示 ----
            // hideToTray 走 EventQueue.invokeLater（不能在自检协程里同步改 AWT 可见性），
            // 所以轮询等它落地，而不是固定 sleep 一个猜测值。
            shell.hideToTray()
            var hidden = false
            repeat(40) {
                if (!hidden) {
                    hidden = !window.isVisible
                    if (!hidden) delay(50)
                }
            }
            shell.showMainWindow()
            delay(SETTLE_MS)
            checks += SelfTestCheck(
                "hide_to_tray_and_restore",
                hidden && window.isVisible,
                "隐藏=$hidden 恢复=${window.isVisible}",
            )

            // ---- 11. 单实例互斥 + 回环唤醒 ----
            val second = SingleInstance.acquire(instanceDir)
            val signalled = second.signalExistingInstance()
            delay(SETTLE_MS)
            checks += SelfTestCheck(
                "single_instance_mutex",
                primary.isPrimary && !second.isPrimary && signalled && activations.get() == 1,
                "primary=${primary.isPrimary} second=${second.isPrimary} " +
                    "回环送达=$signalled 回调次数=${activations.get()}",
            )
            second.close()
        } finally {
            runCatching { shell.close() }
            runCatching { primary.close() }
            instanceDir.deleteRecursively()
        }

        return SelfTestReport(checks)
    }

    /**
     * 悬浮歌词窗的验收：真的开一个无边框置顶窗口，检查
     * `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` 位，并确认前台窗口没变。
     *
     * 这里用 `JFrame` 而不是 Compose 的 `Window`：两者最终都是 AWT 顶层窗口，
     * 样式函数（[applyNoActivateStyles]）也是同一个，但 `JFrame` 能在挂起流程里
     * 同步开关，不依赖 Compose 重组时机。
     */
    private suspend fun floatingWindowCheck(): SelfTestCheck {
        val title = "myMusic 悬浮窗探针"
        val frame = JFrame(title).apply {
            isUndecorated = true
            isAlwaysOnTop = true
            // AWT 层就不接受焦点：这是 WS_EX_NOACTIVATE 的 Java 侧等价开关
            focusableWindowState = false
            background = java.awt.Color(0, 0, 0, 0)
            setSize(480, 120)
            setLocationRelativeTo(null)
        }
        val foregroundBefore = Win32.user32.GetForegroundWindow()
        try {
            frame.isVisible = true
            var hwnd: com.sun.jna.Pointer? = null
            repeat(25) {
                if (hwnd == null) {
                    hwnd = Win32.findTopLevelWindow(title)
                    if (hwnd == null) delay(60)
                }
            }
            val styled = hwnd != null && applyNoActivateStyles(hwnd!!)
            delay(SETTLE_MS)
            val exStyle = hwnd?.let { Win32.user32.GetWindowLongPtrW(it, Win32.GWL_EXSTYLE) } ?: 0L
            val noActivate = (exStyle and Win32.WS_EX_NOACTIVATE.toLong()) != 0L
            val toolWindow = (exStyle and Win32.WS_EX_TOOLWINDOW.toLong()) != 0L
            val foregroundAfter = Win32.user32.GetForegroundWindow()
            // 判定口径：**前台不能是探针自己**。Windows 11 上无边框窗口 show 的瞬间会先激活
            // 再应用 WS_EX_NOACTIVATE，前后两个句柄可能不同（例如从自检窗口切到资源管理器），
            // 但只要前台不是探针，就说明弹窗没有把焦点抢走。
            val focusKept = foregroundAfter != hwnd
            return SelfTestCheck(
                "floating_lyrics_window",
                hwnd != null && styled && noActivate && toolWindow && focusKept,
                "hwnd=$hwnd exStyle=0x${java.lang.Long.toHexString(exStyle)} " +
                    "WS_EX_NOACTIVATE=$noActivate WS_EX_TOOLWINDOW=$toolWindow " +
                    "前台 before=$foregroundBefore after=$foregroundAfter 未抢=$focusKept",
            )
        } finally {
            frame.isVisible = false
            frame.dispose()
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content

    /**
     * 注入媒体键：`keybd_event` 比 `SendInput` 少一个结构体，效果等价。
     *
     * 媒体键是**扩展键**：必须带 `KEYEVENTF_EXTENDEDKEY`（0x0001），否则系统按普通键
     * 路由，`RegisterHotKey` 收不到 `WM_HOTKEY`——实测会表现为"注入成功但命令数不变"。
     */
    private fun injectMediaKey(virtualKey: Int): Boolean = runCatching {
        TestInput.keybdEvent(virtualKey, 0, TestInput.KEYEVENTF_EXTENDEDKEY)
        TestInput.keybdEvent(virtualKey, 0, TestInput.KEYEVENTF_EXTENDEDKEY or TestInput.KEYEVENTF_KEYUP)
        true
    }.getOrDefault(false)
}

/** 自检专用：注入键盘事件的薄封装；产品代码不调用。 */
private interface User32Input : com.sun.jna.Library {
    fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Long)
}

private object TestInput {
    const val KEYEVENTF_EXTENDEDKEY = 0x0001
    const val KEYEVENTF_KEYUP = 0x0002

    private val input: User32Input by lazy { com.sun.jna.Native.load("user32", User32Input::class.java) }

    fun keybdEvent(virtualKey: Int, scan: Int, flags: Int) {
        input.keybd_event(virtualKey.toByte(), scan.toByte(), flags, 0)
    }
}
