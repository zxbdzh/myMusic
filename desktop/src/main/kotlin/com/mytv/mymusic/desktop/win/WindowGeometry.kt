package com.mytv.mymusic.desktop.win

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 主窗口几何：位置、大小、是否最大化。
 *
 * [x] / [y] 可空：首次启动（或上次位置落在已拔掉的显示器上）时由调用方决定居中，
 * 不把「-32000」这类最大化时的系统坐标写死进去。
 */
@Serializable
data class WindowGeometry(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val maximized: Boolean = false,
) {
    companion object {
        const val DEFAULT_WIDTH = 1440
        const val DEFAULT_HEIGHT = 900
        const val MIN_WIDTH = 960
        const val MIN_HEIGHT = 640
    }
}

/**
 * 窗口几何的落盘（`%APPDATA%\myMusic\window.json`）。
 *
 * 为什么不写进 `:core` 的 SettingsStore：那个是 DataStore 的键值设置，每次写都会让
 * `state` 流发一次新快照、整个界面跟着重组；拖动窗口时 geometry 每秒变几十次，
 * 会把设置流变成高频事件源。窗口几何是纯窗口层状态，独立小文件、只在关闭时写一次，
 * 互不干扰（这条取舍写在 docs/pc/WINDOW-CHROME.md）。
 */
class WindowGeometryStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 读上次的几何；文件不存在 / 内容坏了都返回 null（按默认尺寸居中打开）。 */
    fun load(): WindowGeometry? =
        runCatching { json.decodeFromString<WindowGeometry>(file.readText(Charsets.UTF_8)) }.getOrNull()

    /** 写盘；失败（只读盘、目录被删）只是丢一次记忆，不影响运行。 */
    fun save(geometry: WindowGeometry): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(geometry), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 当前窗口几何是否仍然可用：尺寸不小于下限即可（位置可以在屏幕外，交给系统钳制）。 */
    fun sanitize(geometry: WindowGeometry): WindowGeometry = geometry.copy(
        width = geometry.width.coerceAtLeast(WindowGeometry.MIN_WIDTH),
        height = geometry.height.coerceAtLeast(WindowGeometry.MIN_HEIGHT),
    )
}
