package com.mytv.mymusic.playback.vlc

import java.util.Locale

/**
 * 播放侧日志：每行带"进程启动以来的秒数"，和 Android 的 `AppLog` 同一个思路
 * （那边出 `logcat`，这边出 stdout / 文件）。
 *
 * 为什么要自己写而不是直接 println：验收要求"30 秒播放日志"，日志得能一眼看出
 * 第几秒发生了什么（装载 / 开始 / 均衡器 / 淡化 / 定时）；同时 [sink] 可替换，
 * 单测里能截获。
 */
object PlaybackLog {
    private val startedAt = System.nanoTime()

    /** 输出目标；默认 stdout，验收时重定向到文件。 */
    @Volatile
    var sink: (String) -> Unit = { line -> println(line) }

    fun info(tag: String, message: String) = emit("I", tag, message)

    fun warn(tag: String, message: String) = emit("W", tag, message)

    fun error(tag: String, message: String) = emit("E", tag, message)

    private fun emit(level: String, tag: String, message: String) {
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        sink(String.format(Locale.ROOT, "[%8.3fs] %s/%-9s %s", seconds, level, tag, message))
    }
}
