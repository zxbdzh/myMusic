package com.mytv.mymusic.desktop.win

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Windows 原生层：只声明桌面壳真正用到的 `user32` / `kernel32` / `ole32` / `comctl32` 符号。
 *
 * 为什么自己写接口而不用 `jna-platform`：
 *  · `jna-platform` 会把 `User32`/`WinUser` 的整套声明带进来（几百个方法、十几个结构体），
 *    而这个仓库只需要 20 来个；自己声明可以避免版本升级时"某个常量被改名"直接编译失败；
 *  · 所有结构体布局在这里集中可见，出问题时不用去翻 jna-platform 的源码。
 *
 * 平台约束：全部按 **x64** 的 LP64 规则声明（`LONG_PTR` / `WPARAM` / `LPARAM` 都是 8 字节）。
 * 32 位 JVM 上这些偏移不成立，[Win32.requireX64] 会显式拒绝。
 */
internal interface User32 : Library {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessageW(msg: Pointer, hWnd: Pointer?, msgFilterMin: Int, msgFilterMax: Int): Int
    fun PostThreadMessageW(threadId: Int, msg: Int, wParam: Long, lParam: Long): Boolean
    fun PostMessageW(hWnd: Pointer?, message: Int, wParam: Long, lParam: Long): Boolean

    fun EnumWindows(callback: EnumWindowsCallback, lParam: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: Pointer?, pid: IntByReference): Int
    fun GetWindowTextW(hWnd: Pointer?, buffer: CharArray, maxCount: Int): Int
    fun GetClassNameW(hWnd: Pointer?, buffer: CharArray, maxCount: Int): Int
    fun IsWindowVisible(hWnd: Pointer?): Boolean
    fun IsIconic(hWnd: Pointer?): Boolean
    fun ShowWindow(hWnd: Pointer?, command: Int): Boolean
    fun SetForegroundWindow(hWnd: Pointer?): Boolean
    fun GetForegroundWindow(): Pointer?

    fun GetWindowLongPtrW(hWnd: Pointer?, index: Int): Long
    fun SetWindowLongPtrW(hWnd: Pointer?, index: Int, value: Long): Long
    fun CallWindowProcW(prevWndProc: Pointer?, hWnd: Pointer?, message: Int, wParam: Long, lParam: Long): Long

    fun GetModuleHandleW(name: String?): Pointer?
    fun CreateIcon(
        hInstance: Pointer?,
        width: Int,
        height: Int,
        planes: Byte,
        bitsPerPixel: Byte,
        andBits: ByteArray?,
        xorBits: ByteArray?,
    ): Pointer?

    fun DestroyIcon(icon: Pointer?): Boolean
}

/** `kernel32`：线程 id 与模块句柄；两者都常被误当成 user32 的导出。 */
internal interface Kernel32 : Library {
    fun GetCurrentThreadId(): Int
    fun GetModuleHandleW(name: String?): Pointer?
}

internal interface Ole32 : Library {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoUninitialize()
    fun CoCreateInstance(
        clsid: Pointer,
        outer: Pointer?,
        clsContext: Int,
        iid: Pointer,
        out: PointerByReference,
    ): Int
}

/** `comctl32` 只有 `DefSubclassProc` 是必需的（子类化后的默认处理）。 */
internal interface ComCtl32 : Library {
    fun DefSubclassProc(hWnd: Pointer?, message: Int, wParam: Long, lParam: Long): Long
}

/** `EnumWindows` 的回调：返回 TRUE 继续枚举，FALSE 停止。 */
internal fun interface EnumWindowsCallback : Callback {
    fun callback(hWnd: Pointer?, lParam: Pointer?): Boolean
}

/**
 * `SetWindowSubclass` 的回调（comctl32 v6）。
 * 与 `WNDPROC` 的区别是多两个参数，所以必须是两个独立的回调类型。
 */
internal fun interface SubclassProc : Callback {
    fun callback(
        hWnd: Pointer?,
        message: Int,
        wParam: Long,
        lParam: Long,
        idSubclass: Long,
        refData: Long,
    ): Long
}

/** `WNDPROC` 的等价声明，用于 comctl32 不可用时的 `SetWindowLongPtrW` 兜底。 */
internal fun interface WndProc : Callback {
    fun callback(hWnd: Pointer?, message: Int, wParam: Long, lParam: Long): Long
}

/** `MSG`：x64 下 48 字节。`GetMessageW` 需要一块可写内存，Structure 负责按布局算好。 */
@Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY", "lPrivate")
internal class Msg : Structure() {
    @JvmField
    var hwnd: Pointer? = null

    @JvmField
    var message: Int = 0

    @JvmField
    var wParam: Long = 0

    @JvmField
    var lParam: Long = 0

    @JvmField
    var time: Int = 0

    @JvmField
    var ptX: Int = 0

    @JvmField
    var ptY: Int = 0

    @JvmField
    var lPrivate: Int = 0
}

/**
 * 16 字节的 GUID/IID 内存块（COM 的 `REFIID` 就是指向它的指针）。
 *
 * 直接用 `UUID.fromString` 解析：COM 的 GUID 字符串就是标准 8-4-4-4-12 布局，
 * 手写位移反而容易把 Data1 和 Data4 搞混（第一版就是这么错的，
 * `CoCreateInstance` 报 `REGDB_E_CLASSNOTREG`）。
 */
internal fun guid(text: String): Memory {
    val uuid = java.util.UUID.fromString(text)
    val most = uuid.mostSignificantBits
    val least = uuid.leastSignificantBits
    return Memory(16).apply {
        setInt(0L, (most ushr 32).toInt())
        setShort(4L, ((most ushr 16) and 0xFFFF).toShort())
        setShort(6L, (most and 0xFFFF).toShort())
        for (index in 0 until 8) {
            setByte(8L + index, ((least ushr (56 - index * 8)) and 0xFF).toByte())
        }
    }
}

internal fun charArrayToString(buffer: CharArray, length: Int): String =
    if (length <= 0) "" else String(buffer, 0, minOf(length, buffer.size)).trimEnd('\u0000')

/**
 * 原生符号的持有者。
 *
 * `SetWindowSubclass` 是 comctl32 v6 才导出的函数，而 `java.exe` 的清单是否激活 v6
 * 取决于 JDK 发行版。这里**先探测再决定**走子类化还是 `SetWindowLongPtrW` 兜底，
 * 避免在没激活 v6 的 JVM 上直接 `UnsatisfiedLinkError`。
 */
internal object Win32 {
    const val WM_QUIT = 0x0012
    const val WM_COMMAND = 0x0111
    const val WM_HOTKEY = 0x0312

    /** 缩略图工具栏按钮被点击时 `LOWORD(wParam)` 的值（ShObjIdl_core.h）。 */
    const val THBN_CLICKED = 0x1800

    const val GWL_EXSTYLE = -20
    const val GWLP_WNDPROC = -4

    const val WS_EX_NOACTIVATE = 0x08000000
    const val WS_EX_TOOLWINDOW = 0x00000080

    const val SW_HIDE = 0
    const val SW_RESTORE = 9

    const val MOD_ALT = 0x0001
    const val MOD_CONTROL = 0x0002
    const val MOD_SHIFT = 0x0004
    const val MOD_WIN = 0x0008
    const val MOD_NOREPEAT = 0x4000

    const val MEDIA_KEY_PREV = 0xB1
    const val MEDIA_KEY_NEXT = 0xB0
    const val MEDIA_KEY_STOP = 0xB2
    const val MEDIA_KEY_PLAY_PAUSE = 0xB3
    const val VK_LEFT = 0x25
    const val VK_RIGHT = 0x27
    const val VK_L = 0x4C
    const val VK_M = 0x4D
    const val VK_P = 0x50

    const val COINIT_APARTMENTTHREADED = 0x2
    const val CLSCTX_INPROC_SERVER = 0x1

    /** `ITaskbarList3` 虚表下标：IUnknown 3 个 + ITaskbarList 5 个 + ITaskbarList2 1 个 + 之后 12 个。 */
    const val VTBL_HR_INIT = 3L
    const val VTBL_THUMB_BAR_ADD_BUTTONS = 15L
    const val VTBL_THUMB_BAR_UPDATE_BUTTONS = 16L

    /** 每个缩略图按钮的图标边长（px）；DWM 会按 DPI 缩放，16 是 100% 下的标准尺寸。 */
    const val THUMB_ICON_SIZE = 16

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    val ole32: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }
    val comctl32: ComCtl32 by lazy { Native.load("comctl32", ComCtl32::class.java) }

    /** `CLSID_TaskbarList` = 56FDF344-FD6D-11d0-958A-006097C9A090 */
    fun taskbarListClsid(): Memory = guid("56FDF344-FD6D-11d0-958A-006097C9A090")

    /**
     * `IID_ITaskbarList3`。
     *
     * 注意结尾是 `9eeFAF` 不是 `9eeA84`——后者是常见的记忆错误，`CoCreateInstance`
     * 会返回 `E_NOINTERFACE (0x80004002)`。这个值取自本机 SDK 的
     * `ShObjIdl_core.h:15658` 的 `MIDL_INTERFACE`。
     */
    fun taskbarList3Iid(): Memory = guid("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF")

    /** comctl32 的 `SetWindowSubclass`；没激活 v6 时为 null。 */
    val setWindowSubclass: Function? by lazy {
        if (!isWindows) {
            null
        } else {
            try {
                NativeLibrary.getInstance("comctl32").getFunction("SetWindowSubclass")
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 32 位 JVM 上本文档里的结构体偏移全部不成立，宁可启动时就说清楚。 */
    fun requireX64() {
        check(Native.POINTER_SIZE == 8) {
            "Windows 集成层按 x64 声明（LP64），当前 JVM 指针宽度为 ${Native.POINTER_SIZE * 8} 位"
        }
    }

    /**
     * 取本进程里的顶层窗口句柄。
     *
     * 为什么不走 `sun.awt.windows.WComponentPeer.getHWnd()`：那个方法不导出，需要
     * `--add-exports java.desktop/sun.awt.windows=ALL-UNNAMED`，而 jpackage 打出来的
     * 运行时并不带这个参数。用 `EnumWindows` + 进程 ID 过滤是零配置的。
     *
     * @param title 窗口标题；同一进程里可能有多个顶层窗口（主窗口 + 悬浮歌词窗），标题是
     *   唯一稳定的区分依据。
     */
    fun findTopLevelWindow(title: String, className: String = "SunAwtFrame"): Pointer? {
        val pid = ProcessHandle.current().pid().toInt()
        var found: Pointer? = null
        val callback = EnumWindowsCallback { hWnd, _ ->
            if (hWnd == null || !user32.IsWindowVisible(hWnd)) {
                true
            } else {
                val owner = IntByReference()
                user32.GetWindowThreadProcessId(hWnd, owner)
                if (owner.value != pid) {
                    true
                } else {
                    val cls = CharArray(256)
                    val clsLen = user32.GetClassNameW(hWnd, cls, cls.size)
                    val text = CharArray(512)
                    val textLen = user32.GetWindowTextW(hWnd, text, text.size)
                    if (charArrayToString(cls, clsLen).contains(className) &&
                        charArrayToString(text, textLen) == title
                    ) {
                        found = hWnd
                        false
                    } else {
                        true
                    }
                }
            }
        }
        user32.EnumWindows(callback, null)
        return found
    }
}
