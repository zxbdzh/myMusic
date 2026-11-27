package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.core.PlatformPaths
import java.io.File

/**
 * 桌面版的目录布局（`PlatformPaths` 的 JVM 实现）。
 *
 * 两种模式（落实 Q10）：
 * 1. **绿色便携模式**：程序所在目录或当前工作目录存在 `portable` / `portable.flag` 时，
 *    数据、缓存、日志全部落在同级 `data/` 下，不写入 `%APPDATA%`；
 * 2. **标准安装模式**：数据与日志落 `%APPDATA%\myMusic`，缓存落 `%LOCALAPPDATA%\myMusic\cache`，
 *    用户导出/下载落 `%USERPROFILE%\Music\myMusic`（避免写入只读的 Program Files）。
 *
 * 单测可通过 [overrideRoot] 注入临时目录，实现完全隔离的 Room / DataStore / DPAPI 测试。
 */
class DesktopPaths(
    overrideRoot: File? = null,
) : PlatformPaths {

    val isPortable: Boolean

    override val dataDir: File
    override val cacheDir: File
    override val logDir: File
    override val outputDir: File

    init {
        val portableRoot = overrideRoot ?: detectPortableRoot()
        if (portableRoot != null) {
            isPortable = true
            val base = File(portableRoot, "data")
            dataDir = base
            cacheDir = File(base, "cache")
            logDir = File(base, "logs")
            outputDir = File(base, "music")
        } else {
            isPortable = false
            val roaming = File(
                System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
                "myMusic",
            )
            val localBase = File(
                System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
                "myMusic",
            )
            dataDir = roaming
            cacheDir = File(localBase, "cache")
            logDir = File(roaming, "logs")
            outputDir = File(System.getProperty("user.home"), "Music/myMusic")
        }
        listOf(dataDir, cacheDir, logDir).forEach { dir ->
            runCatching { dir.mkdirs() }
        }
    }

    companion object {
        private val PORTABLE_MARKERS = listOf("portable", "portable.flag")

        /** 检查程序目录或工作目录是否存在便携标记文件。 */
        fun detectPortableRoot(): File? {
            val candidates = buildList {
                System.getProperty("compose.application.resources.dir")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it).parentFile }
                    ?.let(::add)
                add(File(System.getProperty("user.dir")))
            }
            return candidates.firstOrNull { dir ->
                dir.isDirectory && PORTABLE_MARKERS.any { File(dir, it).exists() }
            }
        }
    }
}
