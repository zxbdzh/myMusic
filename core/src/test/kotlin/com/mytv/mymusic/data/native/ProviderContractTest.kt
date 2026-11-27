package com.mytv.mymusic.data.native

import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.aggregate.Resolution
import com.mytv.mymusic.aggregate.SourcePolicy
import com.mytv.mymusic.aggregate.SourceResolver
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackSource
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.ProviderInfo
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * provider 契约测试：把「每个平台实现都要守住、换源层与仓库层依赖」的语义钉死在这里。
 *
 * 与 `SourceResolverTest` 的分工：那边验证换源策略（候选排序、试听是否可播）；
 * 这边验证接口本身的默认行为与仓库路由——任何平台实现只要没覆写，就必须表现得像这里的断言，
 * 换源层才不会因为某个平台偷偷改了默认语义而静默降级或静默失败。
 *
 * 全部用例不联网：替身 provider 只做内存里的记录，内置 provider 只做构造与无副作用的声明检查。
 */
class ProviderContractTest {

    /** 注册进 [NativeProviders] 的动态替身，用例结束统一摘掉，避免跨用例串味。 */
    private val registered = mutableListOf<String>()

    @After
    fun tearDown() {
        registered.forEach(NativeProviders::unregister)
        registered.clear()
    }

    private fun <T : NativeProvider> register(provider: T): T {
        NativeProviders.register(provider)
        registered += provider.info.id
        return provider
    }

    // ---------------------------------------------------------------- 替身

    /** 只声明平台资料，其余全部走接口默认实现。 */
    private class MinimalProvider :
        BaseNativeProvider(ProviderInfo(id = "contract-minimal", name = "契约最小平台"))

    /** 只实现搜索，用来验证 [NativeProvider.searchSongsPage] 的默认单页包装。 */
    private class SearchOnlyProvider :
        BaseNativeProvider(ProviderInfo(id = "contract-search", name = "契约搜索平台", search = true)) {
        var searchCalls = 0
            private set

        override suspend fun searchSongs(cookie: String, keyword: String): List<Song> {
            searchCalls++
            return listOf(Song(id = "s1", name = keyword, source = info.id), Song(id = "s2", name = keyword, source = info.id))
        }
    }

    /** 只实现专辑曲目，用来验证 [NativeProvider.albumDetail] 的默认装配。 */
    private class AlbumSongsOnlyProvider :
        BaseNativeProvider(ProviderInfo(id = "contract-album", name = "契约专辑平台", albumSongs = true)) {
        override suspend fun albumSongs(cookie: String, id: String): List<Song> =
            listOf(Song(id = "a1", name = "专辑曲目", source = info.id))
    }

    /** 只覆写 [NativeProvider.downloadUrl]，`playableStream` 用接口默认实现。 */
    private class DownloadUrlRecorder(private val returned: String = "https://cdn.example.com/stream.mp3") :
        BaseNativeProvider(ProviderInfo(id = "contract-download", name = "契约取流平台", download = true)) {
        var downloadUrlCalls = 0
            private set
        var lastQuality: AudioQuality? = null
            private set

        override suspend fun downloadUrl(cookie: String, song: Song, quality: AudioQuality): String {
            downloadUrlCalls++
            lastQuality = quality
            return returned
        }
    }

    /** 覆写 [NativeProvider.playableStream]，用来控制「平台实际给了什么档位 / 是不是片段」。 */
    private class StreamingProvider(
        id: String = "contract-stream",
        private val stream: PlayableStream,
    ) : BaseNativeProvider(ProviderInfo(id = id, name = "契约播放平台", download = true)) {
        var playableStreamCalls = 0
            private set
        var lastQuality: AudioQuality? = null
            private set
        var invalidateCalls = 0
            private set

        override suspend fun playableStream(
            cookie: String,
            song: Song,
            workDir: File,
            quality: AudioQuality,
        ): PlayableStream {
            playableStreamCalls++
            lastQuality = quality
            return stream
        }

        /** 真实平台（网易云）在这里清自己的取流缓存；替身只记调用次数。 */
        override fun invalidateStreamUrls() {
            invalidateCalls++
        }
    }

    /** core 里自带实现的 13 个平台；apple / spotify / mhub 由 :app 适配层登记，不在这里。 */
    private fun builtinProviders(): List<NativeProvider> = listOf(
        NetEaseProvider(),
        QQProvider(),
        KugouProvider(),
        KuwoProvider(),
        MiguProvider(),
        JooxProvider(),
        QianqianProvider(),
        BilibiliProvider(),
        FivesingProvider(),
        JamendoProvider(),
        YouTubeMusicProvider(),
        SodaProvider(),
        YuntingProvider(),
    )

    private fun song(
        provider: String,
        id: String = "track-1",
        url: String = "",
        bitrate: Int = 0,
        ext: String = "",
    ) = Song(
        id = id,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        duration = 269,
        source = provider,
        url = url,
        bitrate = bitrate,
        ext = ext,
    )

    private fun track(provider: String, song: Song): com.mytv.mymusic.aggregate.CanonicalTrack =
        TrackMatcher.singleSource(song, provider, provider)

    private fun resolverFor(
        repository: MusicRepository = MusicRepository(),
        quality: () -> AudioQuality = { AudioQuality.Auto },
    ) = SourceResolver(
        repository = repository,
        cookies = { emptyMap() },
        settings = { AggregationSettings() },
        workDir = { File(".") },
        quality = quality,
    )

    private suspend fun assertUnsupported(provider: NativeProvider, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: UnsupportedOperationException) {
            e
        }
        assertNotNull("未实现的接口必须显式抛 UnsupportedOperationException", error)
        assertTrue(
            "异常信息要带平台名，界面才能提示是哪个平台不支持：${error?.message}",
            error?.message.orEmpty().contains(provider.info.name),
        )
    }

    // ------------------------------------------------- 一、接口默认实现

    @Test
    fun `minimal provider defaults are safe and explicit`() = runBlocking {
        val provider = MinimalProvider()
        // 没有分页能力的平台不能假装有，否则界面会一直点“加载更多”
        assertFalse(provider.supportsSearchPagination)
        assertFalse(provider.supportsCollect())
        // 拿不到资料就返回 null，让账号卡片只显示“已登录”，不要抛
        assertNull(provider.accountInfo(""))
        // 判断不了能否写歌单时返回 null，调用方按“可以试”处理
        assertNull(provider.playlistWritable("", "playlist-1"))
        // 空表 = 界面只显示“自动”，不暴露平台其实没有的档位
        assertTrue(provider.availableQualityOptions("").isEmpty())
        // 放弃扫码登录必须是空操作：平台侧没有会话时也要能安全调用
        provider.cancelLogin("session-1")
    }

    @Test
    fun `unsupported operations name the provider in the message`() = runBlocking {
        val provider = MinimalProvider()
        val song = song(provider.info.id)
        assertUnsupported(provider) { provider.searchSongs("", "晴天") }
        assertUnsupported(provider) { provider.parseSong("", "https://example.com/song/1") }
        assertUnsupported(provider) { provider.downloadUrl("", song) }
        assertUnsupported(provider) { provider.lyrics("", song) }
        assertUnsupported(provider) { provider.comments("", song, 1, 20) }
        assertUnsupported(provider) { provider.searchPlaylists("", "晴天") }
        assertUnsupported(provider) { provider.playlistSongs("", "p1") }
        assertUnsupported(provider) { provider.parsePlaylist("", "https://example.com/playlist/1") }
        assertUnsupported(provider) { provider.recommendedPlaylists("") }
        assertUnsupported(provider) { provider.listeningModes("") }
        assertUnsupported(provider) { provider.searchAlbums("", "叶惠美") }
        assertUnsupported(provider) { provider.searchArtists("", "周杰伦") }
        assertUnsupported(provider) { provider.artistSongs("", "artist-1") }
        assertUnsupported(provider) { provider.albumSongs("", "album-1") }
        assertUnsupported(provider) { provider.parseAlbum("", "https://example.com/album/1") }
        assertUnsupported(provider) { provider.artistDetail("", "artist-1") }
        assertUnsupported(provider) { provider.artistAlbums("", "artist-1", 1, 20) }
        assertUnsupported(provider) { provider.categories("") }
        assertUnsupported(provider) { provider.categoryPlaylists("", "c1", 1, 20) }
        assertUnsupported(provider) { provider.userPlaylists("", 1, 20) }
        assertUnsupported(provider) { provider.topLists("") }
        assertUnsupported(provider) { provider.topListSongs("", "t1") }
        assertUnsupported(provider) { provider.hotSongs("", 20) }
        assertUnsupported(provider) { provider.createLogin("qq") }
        assertUnsupported(provider) { provider.checkLogin("s1", "k1") }
        assertUnsupported(provider) { provider.passwordLogin("u", "p") }
        assertUnsupported(provider) { provider.collectTrack("", song) }
        assertUnsupported(provider) { provider.uncollectTrack("", song) }
    }

    @Test
    fun `searchSongsPage default wraps searchSongs into a single page`() = runBlocking {
        val provider = register(SearchOnlyProvider())
        val page = provider.searchSongsPage("", "晴天", page = 3, limit = 20)
        // 默认实现只能给一页：页号原样带回、hasMore 必须是 false，否则界面会无限翻页
        assertEquals(3, page.page)
        assertFalse(page.hasMore)
        assertEquals(listOf("s1", "s2"), page.songs.map { it.id })
        assertEquals(1, provider.searchCalls)
    }

    @Test
    fun `albumDetail default returns album songs with an empty header`() = runBlocking {
        val provider = register(AlbumSongsOnlyProvider())
        val detail = provider.albumDetail("", "album-1")
        // 头部资料由调用方用打开时带的名字、封面补；这里只保证 id 与 source 对得上
        assertEquals("album-1", detail.album.id)
        assertEquals(provider.info.id, detail.album.source)
        assertEquals("", detail.album.name)
        assertEquals(listOf("a1"), detail.songs.map { it.id })
    }

    @Test
    fun `playlist writes fail as unsupported instead of silently succeeding`() = runBlocking {
        val provider = MinimalProvider()
        val writes: List<suspend () -> Unit> = listOf(
            { provider.addPlaylistTracks("", "p1", emptyList()) },
            { provider.removePlaylistTracks("", "p1", emptyList()) },
            { provider.reorderPlaylistTracks("", "p1", emptyList()) },
        )
        for (write in writes) {
            val error = try {
                write()
                null
            } catch (e: PlaylistWriteException) {
                e
            }
            assertNotNull("写歌单没有实现时必须抛 PlaylistWriteException，不能静默成功", error)
            assertEquals(PlaylistWriteFailure.Unsupported, error!!.failure)
        }
    }

    // ------------------------------------ 二、playableStream 的默认实现与 preview 语义

    @Test
    fun `playableStream default prefers song url and never marks a full track as preview`() = runBlocking {
        val provider = register(DownloadUrlRecorder())
        val stream = provider.playableStream(
            "",
            song(provider.info.id, url = "https://cdn.example.com/a.flac", bitrate = 900),
            File("."),
        )
        assertEquals("https://cdn.example.com/a.flac", stream.url)
        // 扩展名从地址里取，播控据此选解码器
        assertEquals("flac", stream.ext)
        assertEquals(900, stream.bitrate)
        // 默认实现拿到的都是整曲地址：preview 必须是 false，否则换源层会白白丢掉可播的源
        assertFalse(stream.preview)
        assertTrue(stream.extra.isEmpty())
        assertEquals("song.url 已有地址时不该再向平台要一次", 0, provider.downloadUrlCalls)
    }

    @Test
    fun `playableStream default falls back to downloadUrl and infers ext`() = runBlocking {
        val provider = register(DownloadUrlRecorder(returned = "https://cdn.example.com/stream.mp3"))
        val stream = provider.playableStream("", song(provider.info.id), File("."))
        assertEquals("https://cdn.example.com/stream.mp3", stream.url)
        assertEquals("mp3", stream.ext)
        assertEquals(1, provider.downloadUrlCalls)
        assertFalse(stream.preview)
    }

    @Test
    fun `playableStream default forwards the locked quality to downloadUrl`() = runBlocking {
        val provider = register(DownloadUrlRecorder(returned = "https://cdn.example.com/a.flac"))
        provider.playableStream("", song(provider.info.id), File("."), AudioQuality.Lossless)
        // 用户锁了无损就必须把无损传下去，不能在接口层被换成 auto
        assertEquals(AudioQuality.Lossless, provider.lastQuality)
    }

    @Test
    fun `preview stream from a provider is rejected when previews are disallowed`() = runBlocking {
        val provider = register(
            StreamingProvider(stream = PlayableStream(url = "https://cdn.example.com/trial.mp3", preview = true)),
        )
        val resolver = resolverFor()
        val none = resolver.resolve(
            track(provider.info.id, song(provider.info.id)),
            policy = SourcePolicy(priority = listOf(provider.info.id), allowPreview = false),
        ) as Resolution.None
        // provider 取流后才声明的试听标记，要一路传到换源层并当成“这不是整曲”
        assertEquals(listOf("仅提供试听"), none.attempts.map { it.reason })
    }

    @Test
    fun `preview stream plays when previews are allowed and keeps the marker`() = runBlocking {
        val provider = register(
            StreamingProvider(stream = PlayableStream(url = "https://cdn.example.com/trial.mp3", preview = true)),
        )
        val ready = resolverFor().resolve(
            track(provider.info.id, song(provider.info.id)),
            policy = SourcePolicy(priority = listOf(provider.info.id), allowPreview = true),
        ) as Resolution.Ready
        assertEquals("https://cdn.example.com/trial.mp3", ready.url)
        // 允许试听时也要把标记带回界面，播放页才知道自己放的是片段
        assertTrue(ready.isPreview)
    }

    // ---------------------------------------------------- 三、invalidateStreamUrls

    @Test
    fun `invalidateStreamUrls default is a no-op`() {
        // 没有取流缓存的平台什么都不用做；调用方（下载 403 后的重试）不区分平台
        MinimalProvider().invalidateStreamUrls()
    }

    @Test
    fun `repository routes invalidateStreamUrls to the matching provider only`() {
        val first = register(StreamingProvider(id = "contract-invalidate-a", stream = PlayableStream(url = "https://cdn.example.com/a.mp3")))
        val second = register(StreamingProvider(id = "contract-invalidate-b", stream = PlayableStream(url = "https://cdn.example.com/b.mp3")))
        MusicRepository().invalidateStreamUrls(first.info.id)
        assertEquals(1, first.invalidateCalls)
        assertEquals(0, second.invalidateCalls)
    }

    @Test
    fun `repository ignores unknown providers when invalidating`() {
        // 洛雪这类自定义源没有对应的 NativeProvider：重新取流不能因此抛错
        MusicRepository().invalidateStreamUrls("lx-custom-source")
    }

    @Test
    fun `repository invalidate does not clear the resolver address cache`() = runBlocking {
        val provider = register(
            StreamingProvider(stream = PlayableStream(url = "https://cdn.example.com/a.mp3", ext = "mp3")),
        )
        val repository = MusicRepository()
        val resolver = resolverFor(repository)
        val target = track(provider.info.id, song(provider.info.id))

        resolver.resolve(target, policy = SourcePolicy(priority = listOf(provider.info.id)))
        assertEquals(1, provider.playableStreamCalls)

        repository.invalidateStreamUrls(provider.info.id)
        assertEquals("仓库层要把失效请求转发给平台", 1, provider.invalidateCalls)

        // 两层缓存是分开的：换源层自己的地址缓存还在，这次不会再向平台取流
        resolver.resolve(target, policy = SourcePolicy(priority = listOf(provider.info.id)))
        assertEquals(1, provider.playableStreamCalls)

        // 调用方要同时清换源层的缓存，才真的会重新取流
        resolver.invalidate(target.sources.first())
        resolver.resolve(target, policy = SourcePolicy(priority = listOf(provider.info.id)))
        assertEquals(2, provider.playableStreamCalls)
    }

    // ------------------------------------------- 四、音质档位不静默降级

    @Test
    fun `locked quality reaches the platform unchanged`() = runBlocking {
        val provider = register(
            StreamingProvider(stream = PlayableStream(url = "https://cdn.example.com/lossless.flac", ext = "flac", bitrate = 900)),
        )
        resolverFor(quality = { AudioQuality.Lossless }).resolve(
            track(provider.info.id, song(provider.info.id)),
            policy = SourcePolicy(priority = listOf(provider.info.id)),
        )
        assertEquals(AudioQuality.Lossless, provider.lastQuality)
        // 拿到地址就结束：不能因为“可能不是无损”再退回 auto 重取一次（那才是静默降级）
        assertEquals(1, provider.playableStreamCalls)
    }

    @Test
    fun `resolution reports the tier the platform actually returned`() = runBlocking {
        // 平台只给 128k mp3：Resolution 里带回的必须是 128k，界面才能显示真实档位
        val provider = register(
            StreamingProvider(stream = PlayableStream(url = "https://cdn.example.com/128.mp3", ext = "mp3", bitrate = 128)),
        )
        val resolver = resolverFor(quality = { AudioQuality.Lossless })
        val ready = resolver.resolve(
            track(provider.info.id, song(provider.info.id)),
            policy = SourcePolicy(priority = listOf(provider.info.id)),
        ) as Resolution.Ready
        assertEquals(128, ready.source.song.bitrate)
        assertEquals("mp3", ready.source.song.ext)
        // 请求档位不能覆盖实际档位，否则 atLeast 永远判达标、降级被藏起来
        assertEquals(AudioQuality.Lossless, provider.lastQuality)
        assertFalse(ready.isPreview)
    }

    // ------------------------------------ 五、core 内置平台的声明契约（不联网）

    @Test
    fun `registry exposes every provider implemented in core`() {
        val ids = NativeProviders.all().map { it.info.id }.toSet()
        val expected = setOf(
            "netease", "qq", "kugou", "kuwo", "migu", "joox",
            "qianqian", "bilibili", "fivesing", "jamendo", "youtubemusic", "soda", "yunting",
        )
        // 桌面端没有 :app 适配层也要能拿到这 13 个平台；少一个就说明注册表漏了实现
        assertEquals(expected, ids.intersect(expected))
    }

    @Test
    fun `builtin providers have unique non-blank ids and names`() {
        val builtin = builtinProviders()
        assertEquals("平台 id 不能重复", builtin.size, builtin.map { it.info.id }.distinct().size)
        builtin.forEach { provider ->
            assertTrue("provider id 不能为空", provider.info.id.isNotBlank())
            assertTrue("provider 名称不能为空：${provider.info.id}", provider.info.name.isNotBlank())
        }
    }

    @Test
    fun `builtin providers do not throw when the stream cache is invalidated`() {
        // 下载撞到 403 后仓库会无条件调用一次；任何平台在这里抛都会让重试整条链路失败
        builtinProviders().forEach { provider ->
            provider.invalidateStreamUrls()
        }
    }

    @Test
    fun `builtin providers only expose quality options they actually implement`() = runBlocking {
        // 默认空表 = 界面只显示“自动”。这里验证默认实现没有凭空造出档位；
        // 覆写了 availableQualityOptions 的平台需要联网探测，不在此用例覆盖。
        // suspend 方法在 JVM 上多一个 Continuation 参数，getMethod 按单参签名找不到；
        // 这里按名字找重写，语义不变：仍是判断「有没有覆写音质探测」
        builtinProviders()
            .filter { provider ->
                provider::class.java.methods
                    .firstOrNull { it.name == "availableQualityOptions" }
                    ?.declaringClass == NativeProvider::class.java
            }
            .forEach { provider ->
                assertTrue("${provider.info.id} 没有覆写音质探测，就必须返回空表", provider.availableQualityOptions("").isEmpty())
            }
    }

    @Test
    fun `preview source helper only treats vip soda without signer as preview`() {
        // 换源层的静态判断：汽水会员曲在没注入签名器时只能拿片段
        val sodaVip = song("soda").copy(isVip = true)
        val neteaseVip = song("netease").copy(isVip = true)
        val sodaFree = song("soda")
        assertEquals(SodaProvider.isSignerConfigured(), !com.mytv.mymusic.aggregate.isPreviewSource("soda", sodaVip))
        assertFalse(com.mytv.mymusic.aggregate.isPreviewSource("netease", neteaseVip))
        assertFalse(com.mytv.mymusic.aggregate.isPreviewSource("soda", sodaFree))
    }
}
