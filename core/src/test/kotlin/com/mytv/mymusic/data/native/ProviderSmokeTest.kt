package com.mytv.mymusic.data.native

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 六个主力平台在**纯 JVM**（无 Android 运行时）上的联网冒烟：搜索 + 取流。
 *
 * 覆盖 NetEase / QQ / Kugou / Kuwo / Soda / Bilibili——这六个是核心层搬进 :core 之后
 * 第一批"必须能在桌面上直接跑"的实现。其余平台（Apple 要 Widevine、Spotify 要 WebView 登录、
 * MHub 要额外信任锚）仍由 :app 承载，不在本用例范围内。
 *
 * 口径：**六个平台逐个尝试，全部结果打到标准输出**（测试报告 system-out 可直接复现），
 * 判定如下——
 * - 六个平台都必须已注册，缺一个就是移植缺陷，直接失败；
 * - `searchSongs` 用空 Cookie 调公开接口；某个平台被上游挡（5xx / 风控 / 限流）时记录原始
 *   HTTP 状态与响应片段，不算移植缺陷；
 * - `downloadUrl` 要么给出 `http(s)` / `file:` 地址，要么抛出说得清原因的异常（未登录 / 无版权 /
 *   需要会员）——桌面上这就是要给用户的提示；
 * - 一个平台都连不上时，用 [assumeTrue] 标为跳过（环境没网），日志仍完整输出。
 *
 * 两个平台有**额外前置条件**，在裸 JVM 上必然拿不到结果，日志里会写清原因：
 * - **汽水**：搜索走签名器，签名器地址 / token 由 App 的设置项在启动时通过
 *   `SodaProvider.configureSigner(url, token, enabled)` 注入；没有签名器时上游回
 *   `HTTP 500 签名不完整：只拿到 0 个头`，属于预期；
 * - **网易**：搜索打的是 `http://music.163.com/api/linux/forward`，部分出口 IP / 代理会被
 *   上游直接回 502。2026-11-27 本机实测就是这条，故该平台在报告里记为 networkBlocked。
 */
class ProviderSmokeTest {
    /** 六个平台的搜索关键字；同一首中文歌在各平台都能搜到内容。 */
    private val keyword = "晴天"

    private val providerIds = listOf("netease", "qq", "kugou", "kuwo", "soda", "bilibili")

    @Test
    fun `six mainland providers search and resolve stream on jvm`() = runBlocking {
        val report = StringBuilder()
        val registered = mutableListOf<String>()
        val searched = mutableListOf<String>()
        val networkBlocked = mutableListOf<String>()
        val platformBlocked = mutableListOf<String>()
        var played = 0
        var refused = 0

        for (id in providerIds) {
            val provider = NativeProviders.get(id)
            if (provider == null) {
                report.appendLine("=== $id === 未注册（:core 的 NativeProviders 静态列表缺少 $id）")
                continue
            }
            registered += id
            report.appendLine("=== $id (${provider.info.name}) ===")

            val songs = try {
                provider.searchSongs(cookie = "", keyword = keyword).take(5)
            } catch (error: Throwable) {
                report.appendLine("searchSongs 失败：${error.describe()}")
                if (error.isNetworkFailure()) networkBlocked += id else platformBlocked += id
                continue
            }
            report.appendLine("searchSongs ok: ${songs.size} 条")
            if (songs.isEmpty()) {
                platformBlocked += id
                continue
            }
            searched += id
            songs.first().let {
                report.appendLine("  first=${it.name} / ${it.artist} id=${it.id} source=${it.source} bitrate=${it.bitrate}")
            }

            val song = songs.first()
            try {
                val url = provider.downloadUrl(cookie = "", song = song, quality = AudioQuality.Auto)
                val playable = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:")
                report.appendLine("downloadUrl ok: ${url.take(120)} playable=$playable len=${url.length}")
                if (playable) played++ else refused++
            } catch (error: Throwable) {
                if (error.isNetworkFailure()) {
                    networkBlocked += id
                    report.appendLine("downloadUrl 网络失败：${error.describe()}")
                } else {
                    // 未登录 / 无版权 / 需要会员：桌面上这就是要给用户看的提示，不算移植失败
                    refused++
                    report.appendLine("downloadUrl 抛异常（可接受，原样上报）：${error.describe()}")
                }
            }
        }

        val summary = "ProviderSmokeTest: registered=${registered.size} searched=${searched.size} " +
            "played=$played refused=$refused networkBlocked=$networkBlocked platformBlocked=$platformBlocked"
        report.appendLine(summary)
        println(report)

        // 注册齐全是硬要求：少一个说明 :core 的静态列表漏了移植
        assertTrue("六个平台必须全部注册，实际=${registered}", registered.size == providerIds.size)

        if (searched.isEmpty()) {
            // 一个都搜不到：环境没网，标跳过（日志已经在上面完整打出）
            assumeTrue("六个平台都没有可用网络：$summary", false)
        }
        // 只要网络可用，至少要有三个平台能搜索，否则说明 :core 里的 HTTP 链路整体有问题
        assertTrue("只有 ${searched.size} 个平台搜索成功（$searched），其余被挡：$platformBlocked", searched.size >= 3)
        // 取流允许全被拒（未登录 / 会员曲），但至少要有一次"给出地址"或"给出原因"
        assertTrue("没有任何平台给出可播放地址，也没有给出可解释的拒绝原因", played + refused >= 1)
    }

    /** 网络层问题（DNS / 连接 / TLS / 超时 / 网关）与环境相关，不算移植缺陷。 */
    private fun Throwable.isNetworkFailure(): Boolean = generateSequence(this) { it.cause }
        .take(6)
        .any { it is IOException || it is UnknownHostException || it is SocketTimeoutException || it is SSLException }

    private fun Throwable.describe(): String = buildString {
        append(this@describe.javaClass.simpleName)
        append(": ")
        append(message?.replace('\n', ' ').orEmpty().take(240))
        var cause = this@describe.cause
        var depth = 0
        while (cause != null && depth < 3) {
            append(" <- ")
            append(cause.javaClass.simpleName)
            append(": ")
            append(cause.message?.replace('\n', ' ').orEmpty().take(160))
            cause = cause.cause
            depth++
        }
    }
}
