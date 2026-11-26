package com.mytv.mymusic.core

import java.io.File

/**
 * 平台目录视图：Android 与 Windows 的落盘位置完全不同，这里只暴露"要哪几种目录"，
 * 谁提供由薄适配层决定。目前 Android 侧的等价物散在 `Context.filesDir` 调用点，
 * 桌面侧按 `%APPDATA%\myMusic` 与 `%LOCALAPPDATA%\myMusic\cache` 实现。
 *
 * 之所以单独成接口：`LxTvStorage`、`QueueStore`、缓存目录这类代码一旦直接拿 Context，
 * 就无法搬进 :core，也无法在 JVM 单测里构造临时目录。
 */
interface PlatformPaths {
    /** 长期数据：数据库、音源脚本、下载的封面。 */
    val dataDir: File

    /** 可随时删除的缓存：解析出来的临时流、图片缓存。 */
    val cacheDir: File

    /** 日志与崩溃报告落盘目录。 */
    val logDir: File

    /** 用户可见的输出目录（导出的歌单、分享图、下载的音乐）。 */
    val outputDir: File
}
