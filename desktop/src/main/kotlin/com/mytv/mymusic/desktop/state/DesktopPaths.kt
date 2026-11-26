package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.core.PlatformPaths
import java.io.File

/**
 * 桌面版的目录布局（`PlatformPaths` 的 JVM 实现）。
 *
 * 选 %APPDATA% 而不是 exe 同级目录：jpackage 装到 Program Files 时那里不可写，
 * 而用户数据（Cookie、缓存、导出）本来就该跟着用户走。
 *
 * | 用途 | 位置 |
 * | --- | --- |
 * | 长期数据（Cookie、音源脚本、库） | `%APPDATA%\myMusic` |
 * | 缓存（封面、解析出的临时流） | `%LOCALAPPDATA%\myMusic\cache` |
 * | 日志与崩溃报告 | `%APPDATA%\myMusic\logs` |
 * | 用户可见输出（导出歌单、下载） | `%USERPROFILE%\Music\myMusic` |
 *
 * 为什么不用 `win/WindowsPlatformPaths`：那一个是 t5 的 Windows 集成自带实现，
 * 而 UI 层在非 Windows 上也要能起来（开发机、CI），这里给一个跨平台的最小实现，
 * 两者接口相同，t5 那边换成自己的实现即可。
 */
class DesktopPaths : PlatformPaths {

    private val roaming: File = File(
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        "myMusic",
    )

    private val localBase: File = File(
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        "myMusic",
    )

    override val dataDir: File = roaming
    override val cacheDir: File = File(localBase, "cache")
    override val logDir: File = File(roaming, "logs")
    override val outputDir: File = File(System.getProperty("user.home"), "Music/myMusic")

    init {
        // 目录按需创建：不建的话第一次写 Cookie 会因为没有父目录失败
        listOf(dataDir, cacheDir, logDir).forEach { dir ->
            runCatching { dir.mkdirs() }
        }
    }
}
