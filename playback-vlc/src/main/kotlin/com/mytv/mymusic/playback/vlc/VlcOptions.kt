package com.mytv.mymusic.playback.vlc

import com.mytv.mymusic.playback.core.PlaybackCacheConfig
import java.io.File

/** 找不到 libvlc 时的专用异常：消息里带上怎么装，别让用户对着 UnsatisfiedLinkError 猜。 */
class VlcUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * libVLC 的发现与启动参数。
 *
 * 为什么需要这一层：vlcj 的默认发现只认注册表和 `C:\Program Files\VideoLAN\VLC`，
 * 而桌面版有"随包分发"与"用户自装"两种形态。这里按
 * 显式参数 → `MYMUSIC_VLC_HOME` → `VLC_HOME` → 安装目录 → `jna.library.path` 的顺序找，
 * 找到就把 **jna.library.path** 指过去（JNA 加载 libvlc.dll 用），再用
 * **--plugin-path** 告诉 libvlc 插件在哪（libvlc 只认命令行参数，运行时改不了环境变量）。
 */
data class VlcOptions(
    /** VLC 根目录（含 libvlc.dll 与 plugins/）；null 表示交给 vlcj 自己找。 */
    val home: File? = null,
    /** 追加给 libvlc 的参数（例如自定义 `--audio-filter`）。 */
    val extraArgs: List<String> = emptyList(),
) {
    /** libVLC 的插件目录；VLC 3.0.x 起由 libvlc.dll 自己的位置推断，只用于日志与排查。 */
    val pluginsDir: File? get() = home?.resolve("plugins")?.takeIf { it.isDirectory }

    fun factoryArgs(): List<String> = buildList {
        // 纯音频播放器：不解视频、不要任何界面、日志交给引擎自己打。
        // 注意 VLC 3.0.21 已经删掉 --plugin-path（插件由 libvlc.dll 的位置推断），
        // 传了只会打一行 "option no longer exists" 警告，所以这里不传。
        add("--no-video")
        add("--intf=dummy")
        add("--quiet")
        addAll(extraArgs)
    }

    /** 必须在第一次碰 vlcj 之前调用（JNA 只读一次 jna.library.path）。 */
    fun configureNativePath() {
        val dir = home ?: return
        if (!dir.isDirectory) return
        val existing = System.getProperty("jna.library.path").orEmpty()
        val paths = existing.split(File.pathSeparator).filter { it.isNotBlank() }
        if (dir.absolutePath !in paths) {
            System.setProperty("jna.library.path", (listOf(dir.absolutePath) + paths).joinToString(File.pathSeparator))
        }
    }

    /** 默认缓存目录：`%LOCALAPPDATA%\myMusic\cache\playback`，其它系统落到 `~/.cache/myMusic/playback`。 */
    fun cacheDir(): File = File(appDir(), "cache${File.separator}playback")

    /** 续播队列目录：`%LOCALAPPDATA%\myMusic\playback`（和 Android 的 `filesDir/playback` 对应）。 */
    fun stateDir(): File = File(appDir(), "playback")

    private fun appDir(): File {
        val local = System.getenv("LOCALAPPDATA")
        return if (!local.isNullOrBlank()) {
            File(local, "myMusic")
        } else {
            File(System.getProperty("user.home"), ".local/share/myMusic")
        }
    }

    fun cacheConfig(maxBytes: Long = PlaybackCacheConfig.DEFAULT_MAX_BYTES): PlaybackCacheConfig =
        PlaybackCacheConfig(dir = cacheDir(), maxBytes = maxBytes)

    companion object {
        /** 本机常见安装位置；按优先级排，命中第一个就用。 */
        private fun installCandidates(): List<File> = buildList {
            System.getenv("ProgramFiles")?.let { add(File(it, "VideoLAN${File.separator}VLC")) }
            System.getenv("ProgramFiles(x86)")?.let { add(File(it, "VideoLAN${File.separator}VLC")) }
            // Linux / macOS 常见位置：开发机与 CI 上也可能已经装了
            add(File("/usr/lib/x86_64-linux-gnu"))
            add(File("/usr/lib"))
            add(File("/Applications/VLC.app/Contents/MacOS/lib"))
        }

        private fun looksLikeVlcDir(dir: File?): Boolean {
            if (dir == null || !dir.isDirectory) return false
            @Suppress("DEPRECATION")
            val libs = dir.listFiles()?.map { it.name.lowercase() }.orEmpty()
            return libs.any {
                it == "libvlc.dll" || it == "libvlc.so" || it.startsWith("libvlc.so.") || it == "libvlc.dylib" ||
                    it == "libvlccore.dll"
            }
        }

        /** 按"显式 → 环境变量 → 安装目录 → jna.library.path"找；都找不到返回 home=null（交给 vlcj）。 */
        fun discover(explicitHome: String? = null): VlcOptions {
            val fromArgs = explicitHome?.takeIf { it.isNotBlank() }?.let(::File)
            val fromEnv = System.getenv("MYMUSIC_VLC_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: System.getenv("VLC_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            val fromJna = System.getProperty("jna.library.path")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.map(::File)
                ?.firstOrNull()
            val found = sequenceOf(fromArgs, fromEnv, fromJna)
                .firstOrNull { looksLikeVlcDir(it) }
                ?: installCandidates().firstOrNull { looksLikeVlcDir(it) }
            return VlcOptions(home = found)
        }
    }
}

/** 把 libVLC 的版本与路径写进日志——出问题时第一眼看的就是这几行。 */
fun VlcOptions.describe(version: String = ""): String = buildString {
    append("libVLC ")
    append(version.ifBlank { "未知版本" })
    append(" @ ")
    append(home?.absolutePath ?: "（vlcj 默认发现）")
    pluginsDir?.let { append(" 插件目录=").append(it.absolutePath) }
}

private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/].*")

/**
 * libVLC 只认三斜杠的 `file:///F:/...`。
 *
 * Java 的 `File.toURI().toString()` 给的是**单斜杠** `file:/F:/...`，libvlc 会把它当相对路径
 * 拼到工作目录后面（日志里是 `F:\github\myMusic\file:\F:\tmp\...`），随后报 "无法打开 MRL"。
 * 这一步把两种常见的本地写法都归一化，其它（http/https/rtsp/裸 Windows 路径）原样返回。
 */
fun normalizeMrl(url: String): String = when {
    url.startsWith("file://") -> url
    url.startsWith("file:/") -> "file:///" + url.removePrefix("file:/")
    WINDOWS_PATH.matches(url) -> File(url).toPath().toUri().toString()
    else -> url
}
