package com.mytv.mymusic.desktop.win

import com.mytv.mymusic.core.PlatformPaths
import java.io.File

/**
 * Windows 下的 [PlatformPaths] 实现。
 *
 * 目录约定（与安装包、便携版、后续的云同步共用同一套）：
 *  · `dataDir`   = `%APPDATA%\myMusic`             —— 数据库、音源脚本、登录态
 *  · `cacheDir`  = `%LOCALAPPDATA%\myMusic\cache`  —— 解析出来的临时流、封面缓存
 *  · `logDir`    = `%LOCALAPPDATA%\myMusic\logs`   —— 崩溃与 SMTC 桥的 stderr
 *  · `outputDir` = `%USERPROFILE%\Music\myMusic`   —— 导出歌单、下载的音乐
 *
 * 为什么数据与缓存分开：`%APPDATA%` 会随漫游配置同步，缓存跟着同步是浪费；
 * `%LOCALAPPDATA%` 只在本机，符合 Windows 的既有语义。
 */
class WindowsPlatformPaths(
    private val appData: File? = System.getenv("APPDATA")?.let(::File),
    private val localAppData: File? = System.getenv("LOCALAPPDATA")?.let(::File),
    private val userHome: File = File(System.getProperty("user.home") ?: "."),
) : PlatformPaths {

    override val dataDir: File by lazy { (appData ?: userHome.resolve(".mymusic")).resolve("myMusic").ensure() }

    override val cacheDir: File by lazy { (localAppData ?: dataDir).resolve("myMusic/cache").ensure() }

    override val logDir: File by lazy { (localAppData ?: dataDir).resolve("myMusic/logs").ensure() }

    override val outputDir: File by lazy { userHome.resolve("Music/myMusic").ensure() }

    private fun File.ensure(): File = apply { mkdirs() }
}
