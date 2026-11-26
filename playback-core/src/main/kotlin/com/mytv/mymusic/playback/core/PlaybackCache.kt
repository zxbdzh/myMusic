package com.mytv.mymusic.playback.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 播放缓存配置：目录 + 上限 + 三档缓存时长。
 *
 * 为什么三档而不是一个：libVLC 的 `:network-caching=` 是按毫秒数预读，直播流一预读就是几秒延迟，
 * 所以直播单独用更短的一档（Android 侧 ExoPlayer 的 `DefaultLoadControl` 也是这么分的）。
 *
 * [vlcOptions] 是纯函数：把 [PlaybackSource] 的防盗链请求头翻译成 libVLC 的媒体选项，
 * 不碰 native 库，可以单测。真正的缓存文件由 libVLC 自己写进 [dir]，
 * [trim] 在启动 / 切歌时按最久未用淘汰——libVLC 的缓存没有容量上限参数。
 */
data class PlaybackCacheConfig(
    val dir: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val fileCachingMs: Int = 1_000,
    val networkCachingMs: Int = 2_000,
    val liveCachingMs: Int = 300,
) {
    /** 本地文件与网络流用不同档；直播再压短一档，避免"看直播像录播"。 */
    fun cachingMs(source: PlaybackSource): Int = when {
        source.live -> liveCachingMs
        isLocal(source.url) -> fileCachingMs
        else -> networkCachingMs
    }

    /**
     * 交给 libVLC 的媒体选项（`mediaPlayer.media().prepare(url, *options)`）。
     *
     * 只放与这一路音源有关的：缓存时长、请求头、关掉视频与封面抓取。
     * `:http-user-agent` / `:http-referrer` 是 CDN 防盗链要的那两个头，Provider 已经算好，引擎照抄。
     */
    fun vlcOptions(source: PlaybackSource): List<String> = buildList {
        add(":file-caching=$fileCachingMs")
        add(":network-caching=${cachingMs(source)}")
        if (source.live) add(":live-caching=$liveCachingMs")
        // 纯音频播放器：不解视频、不抓封面、不生成快照预览
        add(":no-video")
        add(":no-snapshot-preview")
        add(":no-sub-autodetect-file")
        source.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { add(":http-user-agent=${it.value}") }
        source.headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true) || it.key.equals("Referrer", ignoreCase = true)
        }?.let { add(":http-referrer=${it.value}") }
    }

    /** 这一路音源的缓存文件路径；同一 URL 永远落在同一个文件上，续播时 libVLC 能接上。 */
    fun entryFor(source: PlaybackSource): File = File(dir, "${digest(source.url)}.${extOf(source)}")

    /** 缓存目录当前占用；目录不存在算 0。 */
    fun sizeBytes(): Long = files().sumOf { it.length() }

    /**
     * 按最久未用淘汰到 [maxBytes] 以内，返回删掉的字节数。
     *
     * [keep] 是"正在播放、别删"的文件（libVLC 打开着的缓存文件删了会立刻重新下载一遍）。
     */
    fun trim(keep: Collection<File> = emptyList()): Long {
        val keepPaths = keep.map { it.absolutePath }.toSet()
        // 先按访问时间排，再按修改时间兜底（Windows 的 atime 常常和 mtime 一样）
        val candidates = files()
            .filterNot { it.absolutePath in keepPaths }
            .sortedWith(compareBy({ it.lastModified() }, { it.length() }))
        var total = files().sumOf { it.length() }
        var removed = 0L
        for (file in candidates) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed += size
            }
        }
        return removed
    }

    /** 清空缓存目录（设置页"清除缓存"）。 */
    fun clear() {
        files().forEach { it.delete() }
    }

    private fun files(): List<File> = dir.listFiles()?.filter { it.isFile } ?: emptyList()

    private fun extOf(source: PlaybackSource): String =
        source.ext.ifBlank { source.url.substringAfterLast('.', "").substringBefore('?') }
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .ifBlank { "cache" }

    private fun isLocal(url: String): Boolean =
        url.startsWith("file:", ignoreCase = true) || url.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url)

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024

        /** URL → 固定文件名：SHA-1 前 16 个十六进制字符够用且不会和目录名冲突。 */
        fun digest(url: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
