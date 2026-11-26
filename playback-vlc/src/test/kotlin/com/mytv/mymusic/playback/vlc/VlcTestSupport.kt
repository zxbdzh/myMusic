package com.mytv.mymusic.playback.vlc

import java.io.File

/**
 * native 用例的公共前置：本机没装 VLC（或没给音频夹具）时就跳过，不让 `:playback-vlc:test`
 * 在纯 CI 上变红——真正的播放验证靠命令行 runner 的 30 秒日志（`docs/pc/PLAYBACK-ENGINE.md`）。
 *
 * 本机跑法：
 * ```
 * $env:MYMUSIC_VLC_HOME = "F:\tmp\vlc\3.0.21\vlc-3.0.21"
 * $env:MYMUSIC_TEST_AUDIO_DIR = "F:\tmp\audio-fixtures"
 * ./gradlew :playback-vlc:test
 * ```
 */
object VlcTestSupport {
    private val explicitHome: String? =
        System.getProperty("mymusic.vlc.home")?.takeIf { it.isNotBlank() }
            ?: System.getenv("MYMUSIC_VLC_HOME")?.takeIf { it.isNotBlank() }

    val options: VlcOptions = VlcOptions.discover(explicitHome)

    /** 找到 libvlc 才跑 native 用例。 */
    val available: Boolean get() = options.home != null

    private val audioDir: File? =
        (System.getProperty("mymusic.test.audio") ?: System.getenv("MYMUSIC_TEST_AUDIO_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }

    /** 夹具文件名（6 秒的 a.flac / b.mp3）；没有夹具就返回 null，用例自己跳过。 */
    fun audio(name: String): File? = audioDir?.resolve(name)?.takeIf { it.isFile }

    /** 轮询等待条件成立；native 播放是异步的，只能等事件回填。 */
    fun await(timeoutMs: Long = 15_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
