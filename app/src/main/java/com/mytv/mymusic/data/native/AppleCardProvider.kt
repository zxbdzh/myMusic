package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val APPLE_CARD_INFO = ProviderInfo(
    id = "apple-card",
    name = "Apple Music（卡密）",
    search = true,
    download = false,
    lyrics = false,
    parseSong = true,
    loginRequired = true,
    note = "卡密填在平台账号的 Cookie 栏；搜索走 Apple 公开目录，播放走卡密服务。",
)

/**
 * Apple Music 卡密源的最小闭环：目录复用 [AppleProvider] 的公开搜索，
 * 播放走 `source.shiqianjiang.cn` 的清单 + Temari 本地解密 + 本机 HLS。
 */
class AppleCardProvider : BaseNativeProvider(APPLE_CARD_INFO) {
    private val api = AppleCardApi()
    private val catalog = AppleProvider()
    private val servers = HashMap<String, AppleCardStreamServer>()
    private val mutex = Mutex()

    /** 搜索不需要卡密；账号栏的卡密只影响播放。 */
    override suspend fun searchSongs(cookie: String, keyword: String): List<Song> =
        catalog.searchSongs("", keyword).map { it.copy(source = info.id) }

    override suspend fun parseSong(cookie: String, link: String): Song =
        catalog.parseSong("", link).copy(source = info.id)

    override suspend fun playableStream(
        cookie: String,
        song: Song,
        workDir: File,
        quality: AudioQuality,
    ): PlayableStream {
        val card = cookie.trim()
        require(card.isNotBlank()) { "请先在平台账号里填写 Apple Music 卡密" }
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
            bitrate = 256_000,
            level = "256k",
        )
    }

    override suspend fun availableQualityOptions(cookie: String): List<AudioQualityOption> =
        listOf(AudioQualityOption(AudioQuality.High, "256k AAC", "m4a"))

    private fun qualityId(quality: AudioQuality): String = when (quality) {
        AudioQuality.Low -> "128k"
        AudioQuality.High, AudioQuality.Lossless, AudioQuality.HiRes -> "320k"
        else -> "320k"
    }
}
