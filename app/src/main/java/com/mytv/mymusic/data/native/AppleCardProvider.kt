package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.Song
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Apple Music 的聆澜卡密取流方式。目录和账号由 [AppleProvider] 管理。
 */
class AppleCardProvider {
    private val api = AppleCardApi()
    private val servers = HashMap<String, AppleCardStreamServer>()
    private val mutex = Mutex()

    suspend fun playableStream(
        cardKey: String,
        song: Song,
        workDir: File,
        quality: AudioQuality,
    ): PlayableStream {
        val card = cardKey.trim()
        require(card.isNotBlank()) { "请先填写聆澜卡密" }
        require(TemariNative.available) { "当前设备没有可用的 Apple 解密库" }
        val key = "${song.id}|$card"
        val server = mutex.withLock {
            servers[key] ?: AppleCardStreamServer(api, card, song.id, workDir).also {
                it.prepare(qualityId(quality))
                servers[key] = it
            }
        }
        return PlayableStream(
            url = server.startAndUrl(),
            ext = "m3u8",
            bitrate = 256,
            level = "256k",
        )
    }

    private fun qualityId(quality: AudioQuality): String = when (quality) {
        AudioQuality.Low -> "128k"
        AudioQuality.High, AudioQuality.Lossless, AudioQuality.HiRes -> "320k"
        else -> "320k"
    }
}
