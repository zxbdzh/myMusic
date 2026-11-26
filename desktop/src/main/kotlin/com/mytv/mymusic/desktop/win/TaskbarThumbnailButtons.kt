package com.mytv.mymusic.desktop.win

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 缩略图按钮上画的图形。用代码画而不是装图标资源：4 个 16×16 图形不值得进仓库。 */
enum class ThumbGlyph { PREVIOUS, PLAY, PAUSE, NEXT, LYRICS }

/**
 * 任务栏缩略图工具栏的一个按钮。
 *
 * [id] 会出现在 `WM_COMMAND` 的高位字里，必须是 1..0xFFFF 的稳定值——
 * 更新按钮状态时靠它定位。
 */
data class ThumbButton(
    val id: Int,
    val label: String,
    val glyph: ThumbGlyph,
    val onClick: () -> Unit,
)

/** `THUMBBUTTON`；x64 下 552 字节（[Win32] 里有断言）。 */
@Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
internal class ThumbButtonStruct : Structure() {
    @JvmField
    var dwMask: Int = 0

    @JvmField
    var iId: Int = 0

    @JvmField
    var iBitmap: Int = 0

    @JvmField
    var hIcon: Pointer? = null

    @JvmField
    var szTip: CharArray = CharArray(260)

    @JvmField
    var dwFlags: Int = 0

    init {
        szTip[0] = '\u0000'
    }
}

/**
 * 任务栏缩略图工具栏（`ITaskbarList3::ThumbBarAddButtons`）。
 *
 * 两个 Windows 细节决定了这个类的形状：
 *
 * 1. **COM 必须在 STA 线程上创建**：`ITaskbarList3` 是 apartment-threaded 的，
 *    在任意线程上 `CoCreateInstance` 会拿到 `CO_E_NOTINITIALIZED`。这里开一条
 *    常驻 STA 线程，创建/初始化/加按钮全在它上面做，`IoClose` 时再退出。
 *
 * 2. **点击事件只到 `WM_COMMAND`**：`THBN_CLICKED` 通过窗口过程发给窗口，而 AWT
 *    的窗口过程不认识这些 id，会直接丢弃。所以必须**子类化**：优先用
 *    `SetWindowSubclass`（comctl32 v6），拿不到符号时退回 `SetWindowLongPtrW` +
 *    `CallWindowProcW` 链式调用——两条路的回调签名不同，因此有两个回调类型。
 *
 * 失败不影响主流程：任务栏按钮只是锦上添花，[installed] 为 false 时播放器照常工作。
 */
class TaskbarThumbnailButtons(
    private val hwnd: Pointer,
    private val buttons: List<ThumbButton>,
) : AutoCloseable {

    @Volatile
    var installed: Boolean = false
        private set

    /** 实际走的是哪条子类化路径，自检里会打印出来。 */
    @Volatile
    var mechanism: String = "none"
        private set

    /** 失败原因；成功时为空。 */
    @Volatile
    var failure: String = ""
        private set

    private val ready = CountDownLatch(1)

    @Volatile
    private var comPointer: Pointer? = null

    @Volatile
    private var staThreadId: Int = 0

    @Volatile
    private var running = true

    /** JNA 回调必须保持强引用，否则会被 GC 回收成野指针。 */
    private val keepAlive = mutableListOf<Any>()

    private val buttonsById: Map<Int, ThumbButton> = buttons.associateBy { it.id }

    fun install(): TaskbarThumbnailButtons {
        Win32.requireX64()
        thread(name = "mymusic-taskbar-sta", isDaemon = true) {
            staThreadId = Win32.kernel32.GetCurrentThreadId()
            try {
                Win32.ole32.CoInitializeEx(null, Win32.COINIT_APARTMENTTHREADED)
                addButtons()
                installSubclass()
                installed = true
            } catch (t: Throwable) {
                failure = t::class.java.simpleName + ": " + (t.message ?: "")
            } finally {
                ready.countDown()
            }
            // 保持 STA 线程存活：COM 接口与子类化回调都挂在这条线程的 apartment 上
            while (running) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        return this
    }

    private fun addButtons() {
        val out = PointerByReference()
        val hr = Win32.ole32.CoCreateInstance(
            Win32.taskbarListClsid(),
            null,
            Win32.CLSCTX_INPROC_SERVER,
            Win32.taskbarList3Iid(),
            out,
        )
        check(hr == 0) { "CoCreateInstance(ITaskbarList3) 失败: 0x${Integer.toHexString(hr)}" }
        val com = out.value ?: error("ITaskbarList3 为 null")
        comPointer = com
        val vtable = com.getPointer(0)

        val initHr = Function.getFunction(vtable.getPointer(Win32.VTBL_HR_INIT * 8)).invokeInt(arrayOf(com))
        check(initHr == 0) { "ITaskbarList3::HrInit 失败: 0x${Integer.toHexString(initHr)}" }

        val instance = Win32.kernel32.GetModuleHandleW(null)
        val structSize = ThumbButtonStruct().size()
        val array = Memory((structSize * buttons.size).toLong())
        val iconHandles = mutableListOf<Pointer>()

        buttons.forEachIndexed { index, button ->
            val icon = createIcon(instance, button.glyph)
            iconHandles += icon
            val entry = ThumbButtonStruct().apply {
                // 三个位都要置：不置 THB_ICON 按钮就是空白，不置 THB_TOOLTIP 没悬停提示
                dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
                iId = button.id
                hIcon = icon
                // 固定 260 个 wchar 的提示缓冲，必须自己保证以 NUL 结尾
                val tip = CharArray(260)
                button.label.take(259).toCharArray().copyInto(tip)
                szTip = tip
                dwFlags = THBF_ENABLED
            }
            entry.write()
            array.write(index * structSize.toLong(), entry.pointer.getByteArray(0, structSize), 0, structSize)
        }
        keepAlive.add(iconHandles)

        val addHr = Function.getFunction(vtable.getPointer(Win32.VTBL_THUMB_BAR_ADD_BUTTONS * 8))
            .invokeInt(arrayOf(com, buttons.size, array))
        check(addHr == 0) {
            "ThumbBarAddButtons 失败: 0x${Integer.toHexString(addHr)} " +
                "(count=${buttons.size} structSize=$structSize memory=$array)"
        }
    }

    private fun installSubclass() {
        val onClick: (Int) -> Unit = { id ->
            buttonsById[id]?.let { runCatching { it.onClick() } }
        }

        val subclassFunction = Win32.setWindowSubclass
        if (subclassFunction != null) {
            val proc = SubclassProc { _, message, wParam, lParam, _, _ ->
                if (message == Win32.WM_COMMAND && (wParam and 0xFFFF).toInt() == Win32.THBN_CLICKED) {
                    onClick(((wParam ushr 16) and 0xFFFF).toInt())
                    0L
                } else {
                    Win32.comctl32.DefSubclassProc(hwnd, message, wParam, lParam)
                }
            }
            keepAlive.add(proc)
            // dwRefData 用来回找 this；这里用固定值，因为回调闭包已经捕获了实例
            val ok = subclassFunction.invokeInt(arrayOf(hwnd, proc, SUBCLASS_ID, 0L)) != 0
            if (ok) {
                mechanism = "SetWindowSubclass"
                return
            }
        }

        // 兜底：直接换窗口过程，靠自己把消息转发给原来的过程
        var previousProc = 0L
        val proc = WndProc { h, message, wParam, lParam ->
            if (message == Win32.WM_COMMAND && (wParam and 0xFFFF).toInt() == Win32.THBN_CLICKED) {
                onClick(((wParam ushr 16) and 0xFFFF).toInt())
                0L
            } else {
                Win32.user32.CallWindowProcW(Pointer(previousProc), h, message, wParam, lParam)
            }
        }
        keepAlive.add(proc)
        previousProc = Win32.user32.SetWindowLongPtrW(hwnd, Win32.GWLP_WNDPROC, com.sun.jna.Pointer.nativeValue(com.sun.jna.CallbackReference.getFunctionPointer(proc)))
        check(previousProc != 0L) { "SetWindowLongPtrW(GWLP_WNDPROC) 失败" }
        mechanism = "SetWindowLongPtrW"
    }

    /**
     * 只换某个按钮的图标/提示，不动其它按钮。
     *
     * 播放/暂停按钮要用它切图标：Windows 不会因为 `iId` 相同就自动重画，
     * 必须再发一次 `ThumbBarUpdateButtons`（虚表第 16 项）。
     */
    fun updateGlyph(buttonId: Int, glyph: ThumbGlyph, label: String? = null): Boolean {
        val com = comPointer ?: return false
        return runCatching {
            val vtable = com.getPointer(0)
            val instance = Win32.kernel32.GetModuleHandleW(null)
            val icon = createIcon(instance, glyph)
            keepAlive.add(icon)

            val structSize = ThumbButtonStruct().size()
            val array = Memory(structSize.toLong())
            val entry = ThumbButtonStruct().apply {
                dwMask = if (label != null) THB_ICON or THB_TOOLTIP else THB_ICON
                iId = buttonId
                hIcon = icon
                if (label != null) {
                    val tip = CharArray(260)
                    label.take(259).toCharArray().copyInto(tip)
                    szTip = tip
                }
            }
            entry.write()
            array.write(0, entry.pointer.getByteArray(0, structSize), 0, structSize)
            Function.getFunction(vtable.getPointer(Win32.VTBL_THUMB_BAR_UPDATE_BUTTONS * 8))
                .invokeInt(arrayOf(com, 1, array)) == 0
        }.getOrDefault(false)
    }

    /**
     * 模拟点击：`PostMessageW(WM_COMMAND, MAKEWPARAM(id, THBN_CLICKED))`。
     *
     * 自检与单测用它验证"任务栏按钮 → 命令"这条链路；真实鼠标点击走的
     * 是同一个窗口过程，只是来源不同。
     *
     * **子类化没装上时必须返回 false**：AWT 的窗口过程不认识 `THBN_CLICKED`，
     * 会把它当菜单命令去解引用 `lParam`（0）直接访问违例——实测会杀掉整个 JVM。
     */
    fun simulateClick(buttonId: Int): Boolean {
        if (mechanism == "none") return false
        return Win32.user32.PostMessageW(
            hwnd,
            Win32.WM_COMMAND,
            ((buttonId.toLong() and 0xFFFF) shl 16) or Win32.THBN_CLICKED.toLong(),
            0,
        )
    }

    override fun close() {
        running = false
        val com = comPointer
        comPointer = null
        if (com != null) {
            // 释放 COM 引用（IUnknown::Release 是虚表第 3 项）
            runCatching { Function.getFunction(com.getPointer(0).getPointer(2 * 8)).invokeInt(arrayOf(com)) }
        }
        if (staThreadId != 0) {
            runCatching { Win32.user32.PostThreadMessageW(staThreadId, Win32.WM_QUIT, 0, 0) }
        }
    }

    private companion object {
        const val SUBCLASS_ID = 0x4D59L // 'MY'，进程内唯一即可

        const val THB_ICON = 0x2
        const val THB_TOOLTIP = 0x4
        const val THB_FLAGS = 0x8

        const val THBF_ENABLED = 0x0

        /** 16×16 单色 AND 掩码：全 0 表示"整块不透明"，透明度交给 XOR 的 alpha 通道。 */
        private fun createIcon(instance: Pointer?, glyph: ThumbGlyph): Pointer {
            val size = Win32.THUMB_ICON_SIZE
            val xor = ByteArray(size * size * 4)
            val argb = rasterize(glyph, size)
            for (i in argb.indices) {
                val pixel = argb[i]
                // Windows 的 32bpp DIB 是 BGRA 小端
                xor[i * 4] = (pixel and 0xFF).toByte()
                xor[i * 4 + 1] = ((pixel ushr 8) and 0xFF).toByte()
                xor[i * 4 + 2] = ((pixel ushr 16) and 0xFF).toByte()
                xor[i * 4 + 3] = ((pixel ushr 24) and 0xFF).toByte()
            }
            val and = ByteArray(((size + 31) / 32) * 4 * size)
            return Win32.user32.CreateIcon(instance, size, size, 1, 32, and, xor)
                ?: error("CreateIcon 失败")
        }
    }
}

/**
 * 16×16 的极简光栅化：只画实心三角与矩形。
 *
 * 之所以不引字体/图标库：任务栏按钮只有 16 像素，四个图形用点内判定就够，
 * 引入资源文件反而要处理 DPI 与打包路径。
 */
private fun rasterize(glyph: ThumbGlyph, size: Int): IntArray {
    val pixels = IntArray(size * size)
    val white = 0xFFFFFFFF.toInt()
    fun put(x: Int, y: Int) {
        if (x in 0 until size && y in 0 until size) pixels[y * size + x] = white
    }
    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0 until y1) for (x in x0 until x1) put(x, y)
    }
    fun fillTriangle(ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int) {
        val minY = minOf(ay, by, cy)
        val maxY = maxOf(ay, by, cy)
        for (y in minY..maxY) {
            for (x in 0 until size) {
                val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
                val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
                val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
                val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
                val hasPos = d1 > 0 || d2 > 0 || d3 > 0
                if (!(hasNeg && hasPos)) put(x, y)
            }
        }
    }

    when (glyph) {
        ThumbGlyph.PLAY -> fillTriangle(5, 3, 5, 12, 12, 7)
        ThumbGlyph.PAUSE -> {
            fillRect(4, 3, 7, 13)
            fillRect(9, 3, 12, 13)
        }
        ThumbGlyph.NEXT -> {
            fillTriangle(3, 3, 3, 12, 8, 7)
            fillTriangle(8, 3, 8, 12, 13, 7)
        }
        ThumbGlyph.PREVIOUS -> {
            fillTriangle(13, 3, 13, 12, 8, 7)
            fillTriangle(8, 3, 8, 12, 3, 7)
        }
        ThumbGlyph.LYRICS -> {
            fillRect(3, 4, 13, 5)
            fillRect(3, 7, 13, 8)
            fillRect(3, 10, 10, 11)
        }
    }
    return pixels
}
