package com.mytv.mymusic.desktop.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 窗口截图：`PrintWindow` + GDI（不抢焦点、不需要窗口在前台）。
 *
 * 为什么不用 `java.awt.Robot` 截屏：Robot 截的是**屏幕区域**，后台进程抢不到前台
 * （Windows 前台锁）时会把用户正在看的其它窗口拍进来——这在验收时既无效又侵犯隐私。
 * `PrintWindow(PW_RENDERFULLCONTENT=2)` 让窗口自己把内容画到给定的内存 DC 上，
 * 不需要前置、不移动鼠标，Compose/Skia 的硬件加速窗口在 Win8.1+ 也能画出来。
 *
 * 只在 `--window-shot` 验收路径使用，产品运行时不调用。
 */
internal object WindowCapture {

    private const val PW_RENDERFULLCONTENT = 2
    private const val DIB_RGB_COLORS = 0
    private const val BI_RGB = 0

    /** 截图失败（窗口句柄失效、GDI 分配失败）返回 false，调用方只记日志。 */
    fun captureToFile(hwnd: Pointer?, file: File): Boolean {
        if (hwnd == null || !Win32.isWindows) return false
        val rect = WinRect()
        if (!user32.GetWindowRect(hwnd, rect)) return false
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return false

        val windowDc = user32.GetWindowDC(hwnd) ?: return false
        val memDc = gdi32.CreateCompatibleDC(windowDc) ?: run {
            user32.ReleaseDC(hwnd, windowDc)
            return false
        }
        val bitmap = gdi32.CreateCompatibleBitmap(windowDc, width, height) ?: run {
            gdi32.DeleteDC(memDc)
            user32.ReleaseDC(hwnd, windowDc)
            return false
        }
        try {
            val previous = gdi32.SelectObject(memDc, bitmap)
            try {
                if (!user32.PrintWindow(hwnd, memDc, PW_RENDERFULLCONTENT)) return false
                val pixels = ByteArray(width * height * 4)
                val info = BitmapInfo()
                info.bmiHeader.biWidth = width
                info.bmiHeader.biHeight = -height // 负值 = 自上而下，省一次翻转
                info.bmiHeader.biSizeImage = pixels.size
                info.write()
                val lines = gdi32.GetDIBits(memDc, bitmap, 0, height, pixels, info, DIB_RGB_COLORS)
                if (lines <= 0) return false

                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                var index = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        // GetDIBits 给的是 BGRA
                        val b = pixels[index].toInt() and 0xFF
                        val g = pixels[index + 1].toInt() and 0xFF
                        val r = pixels[index + 2].toInt() and 0xFF
                        image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                        index += 4
                    }
                }
                file.parentFile?.mkdirs()
                return ImageIO.write(image, "png", file)
            } finally {
                if (previous != null) gdi32.SelectObject(memDc, previous)
            }
        } finally {
            gdi32.DeleteObject(bitmap)
            gdi32.DeleteDC(memDc)
            user32.ReleaseDC(hwnd, windowDc)
        }
    }

    private val user32: User32Ex by lazy { Native.load("user32", User32Ex::class.java) }
    private val gdi32: Gdi32Ex by lazy { Native.load("gdi32", Gdi32Ex::class.java) }

    private interface User32Ex : Library {
        fun GetWindowRect(hwnd: Pointer?, rect: WinRect?): Boolean
        fun GetWindowDC(hwnd: Pointer?): Pointer?
        fun ReleaseDC(hwnd: Pointer?, hdc: Pointer?): Int
        fun PrintWindow(hwnd: Pointer?, hdc: Pointer?, flags: Int): Boolean
    }

    private interface Gdi32Ex : Library {
        fun CreateCompatibleDC(hdc: Pointer?): Pointer?
        fun CreateCompatibleBitmap(hdc: Pointer?, width: Int, height: Int): Pointer?
        fun SelectObject(hdc: Pointer?, obj: Pointer?): Pointer?
        fun DeleteObject(obj: Pointer?): Boolean
        fun DeleteDC(hdc: Pointer?): Boolean
        fun GetDIBits(
            hdc: Pointer?,
            bitmap: Pointer?,
            start: Int,
            lines: Int,
            bits: ByteArray?,
            bmi: BitmapInfo?,
            usage: Int,
        ): Int
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class WinRect : Structure() {
        @JvmField var left: Int = 0
        @JvmField var top: Int = 0
        @JvmField var right: Int = 0
        @JvmField var bottom: Int = 0
    }

    @Structure.FieldOrder(
        "biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
        "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
        "biClrUsed", "biClrImportant",
    )
    class BitmapInfoHeader : Structure() {
        @JvmField var biSize: Int = size()
        @JvmField var biWidth: Int = 0
        @JvmField var biHeight: Int = 0
        @JvmField var biPlanes: Short = 1
        @JvmField var biBitCount: Short = 32
        @JvmField var biCompression: Int = BI_RGB
        @JvmField var biSizeImage: Int = 0
        @JvmField var biXPelsPerMeter: Int = 0
        @JvmField var biYPelsPerMeter: Int = 0
        @JvmField var biClrUsed: Int = 0
        @JvmField var biClrImportant: Int = 0
    }

    @Structure.FieldOrder("bmiHeader", "bmiColors")
    class BitmapInfo : Structure() {
        @JvmField var bmiHeader = BitmapInfoHeader()

        /** 只在小于 8bpp 时有意义；32bpp 下保留一个占位元素满足布局。 */
        @JvmField var bmiColors = IntArray(3)
    }
}
