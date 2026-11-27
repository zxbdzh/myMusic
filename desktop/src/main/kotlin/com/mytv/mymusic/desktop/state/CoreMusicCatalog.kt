package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.core.PlatformPaths
import com.mytv.mymusic.data.ArtistDetail
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.SongComment
import com.mytv.mymusic.data.SongCommentPage
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.toAlbumSummary
import com.mytv.mymusic.data.Playlist as CorePlaylist
import com.mytv.mymusic.data.ProviderInfo as CoreProviderInfo
import com.mytv.mymusic.data.native.NativeProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File

/**
 * 平台 Cookie 的最小落地。
 *
 * B2 只要求"能带着登录态取数"，完整的账号管理（扫码、多账号、失效提示）是 B4 的事，
 * 所以这里就是一行一个 `providerId=cookie` 的纯文本文件，放在 `PlatformPaths.dataDir` 下。
 *
 * 为什么不用 DataStore：Preferences 的键值语义对"整串 Cookie"没有好处，
 * 而 `dataDir` 上的文件在打包/自定义目录时行为最可预测，出问题也能直接打开看。
 * Cookie 里有敏感信息，所以文件权限尽量收紧（Windows 上主要靠目录 ACL，这里只做 best-effort）。
 */
class PlatformCookieStore(private val dataDir: File) {

    private val file: File by lazy { File(dataDir, "platform-cookies.txt") }
    private val cache: MutableMap<String, String> by lazy { load() }

    fun get(providerId: String): String = cache[providerId].orEmpty()

    fun all(): Map<String, String> = cache.toMap()

    fun put(providerId: String, cookie: String) {
        if (providerId.isBlank()) return
        if (cookie.isBlank()) cache.remove(providerId) else cache[providerId] = cookie
        save()
    }

    private fun load(): MutableMap<String, String> = runCatching {
        if (!file.isFile) return@runCatching mutableMapOf()
        file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap(mutableMapOf())
    }.getOrElse { mutableMapOf() }

    private fun save() {
        runCatching {
            dataDir.mkdirs()
            file.writeText(cache.entries.joinToString("\n") { "${it.key}=${it.value}" }, Charsets.UTF_8)
        }
    }
}

/**
 * `MusicCatalog` 的真实实现：桥到 `:core` 的 [MusicRepository]（内部是 13 个平台实现）
 * 与 `NativeProviders`。
 *
 * 三条纪律：
 *  1. **只暴露 UI 需要的形状**：`Song` / `TopList` / `Playlist` 在这里一次性翻译成
 *     [Track] / [ChartSummary] / [PlaylistSummary]，UI 与用例层看不到 :core 的类型；
 *  2. **原始对象留在本类**：取流与歌词需要完整的 `Song`（带 `extra` 里的平台私有字段），
 *     用 `trackId -> Song` 的注册表保留，避免"用 UI 模型反推领域模型"造成的信息丢失；
 *  3. **失败不抛给 UI**：每个平台调用都 runCatching，返回空列表并记录 `lastError`，
 *     页面显示"这个平台暂时没有数据"，而不是整页崩掉。
 */
class CoreMusicCatalog(
    paths: PlatformPaths,
    private val repository: MusicRepository = MusicRepository(),
) : MusicCatalog {

    private val cookies = PlatformCookieStore(paths.dataDir)
    private val songs = java.util.concurrent.ConcurrentHashMap<String, Song>()

    /** 平台列表：Apple / Spotify / MHub 需要平台层登记（B4），桌面这里自然缺席。 */
    override val providers: List<ProviderInfo> = runCatching {
        NativeProviders.infos().map { info -> info.toUi() }
    }.getOrElse { emptyList() }

    override val defaultProviderId: String =
        providers.firstOrNull { it.id == "netease" && it.searchable }?.id
            ?: providers.firstOrNull { it.searchable }?.id.orEmpty()

    @Volatile
    var lastError: String = ""
        private set

    override suspend fun charts(providerId: String): ChartsPayload {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = cookies.get(id)
        val topLists = guarded("$id 榜单") { repository.topLists(id, cookie) }.orEmpty()
        val hot = guarded("$id 热门单曲") { repository.hotSongs(id, cookie, 24) }.orEmpty()
        val charts = topLists.map { it.toUi(id) }
        // 部分平台不给 hotSongs（接口要登录），退一步用榜首榜单的前几首，首屏不至于空白
        val songs = if (hot.isNotEmpty()) hot else {
            val first = topLists.firstOrNull()
            first?.let { guarded("$id 榜单曲目") { repository.topListSongs(id, cookies.get(id), it.id) }.orEmpty() } ?: emptyList()
        }
        return ChartsPayload(
            charts = charts,
            featured = charts.firstOrNull(),
            hotSongs = songs.map { it.toUi(id) },
        )
    }

    override suspend fun playlists(providerId: String): PlaylistsPayload {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = cookies.get(id)
        val recommended = guarded("$id 推荐歌单") { repository.recommended(id, cookie) }.orEmpty()
        val categories = guarded("$id 歌单分类") { repository.categories(id, cookie) }.orEmpty()
        val byCategory = categories.take(8).associate { category ->
            category.name to guarded("$id 分类 ${category.name}") {
                repository.categoryPlaylists(id, cookie, category.id, 1, 20)
            }.orEmpty()
        }
        return PlaylistsPayload(
            recommended = recommended.map { it.toUi(id) },
            categories = listOf("全部") + categories.map { it.name }.distinct(),
            byCategory = byCategory.mapValues { (_, list) -> list.map { it.toUi(id) } },
        )
    }

    override suspend fun search(providerId: String, query: String, kind: SearchKind): List<Track> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        // 歌曲搜索走跨平台聚合（去重 + 多源），专辑/歌单/歌手用单平台接口：
        // 后者聚合器的分组是"规范曲目"，对集合类结果没有帮助，还会把 UI 模型搞复杂
        val providerIds = if (providerId.isBlank()) providers.filter { it.searchable }.map { it.id } else listOf(providerId)
        return when (kind) {
            SearchKind.Song -> searchSongs(providerIds, keyword)
            SearchKind.Album -> providerIds.flatMap { id ->
                guarded("$id 专辑搜索") { repository.searchAlbums(id, cookies.get(id), keyword) }
                    .orEmpty()
                    // 专辑/歌单在结果列表里先当「一行」展示，点开走详情（B2 不做详情）
                    .map { it.toUi(id).toTrackRow(id) }
            }.distinctBy { it.title + it.artist }

            SearchKind.Playlist -> providerIds.flatMap { id ->
                guarded("$id 歌单搜索") { repository.searchPlaylists(id, cookies.get(id), keyword) }
                    .orEmpty()
                    .map { it.toUi(id).toTrackRow(id) }
            }.distinctBy { it.title + it.artist }
        }
    }

    /**
     * 单曲搜索：并发打各平台，按"歌名 + 歌手"去重。
     *
     * 不用 `AggregateSearchEngine`：那个引擎面向"同一首歌的多源备选"（CanonicalTrack + 分组），
     * 桌面 B2 的搜索页只需要"一列结果 + 来源标签"。等 B3 做音源切换时再切到聚合引擎，
     * 那时 UI 需要的就是分组结构了。
     */
    private suspend fun searchSongs(providerIds: List<String>, keyword: String): List<Track> =
        kotlinx.coroutines.coroutineScope {
            val results = providerIds.map { id ->
                async(Dispatchers.IO) {
                    id to guarded("$id 搜索") { repository.searchSongs(id, cookies.get(id), keyword) }.orEmpty()
                }
            }.map { it.await() }
            results
                .flatMap { (id, list) -> list.map { it.toUi(id) } }
                .distinctBy { it.title.trim() + "|" + it.artist.trim() }
                .take(60)
        }

    override suspend fun chartTracks(chart: ChartSummary): List<Track> {
        val id = chart.sourceId.ifBlank { defaultProviderId }
        return guarded("榜单曲目") { repository.topListSongs(id, cookies.get(id), chart.id) }
            .orEmpty()
            .map { it.toUi(id) }
    }

    override suspend fun playlistTracks(playlist: PlaylistSummary): List<Track> {
        val id = playlist.sourceId.ifBlank { defaultProviderId }
        return guarded("歌单曲目") { repository.playlistSongs(id, cookies.get(id), playlist.id) }
            .orEmpty()
            .map { it.toUi(id) }
    }

    // --------------------------------------------------------------- B2：歌手

    override suspend fun searchArtists(providerId: String, keyword: String): List<ArtistSummary> {
        val id = providerId.ifBlank { defaultProviderId }
        if (keyword.isBlank()) return emptyList()
        // 歌手搜索走单平台接口：聚合层没有歌手维度，跨平台拼结果反而会把同名歌手混在一列
        return guarded("$id 歌手搜索") { repository.searchArtists(id, cookies.get(id), keyword) }
            .orEmpty()
            .map { it.toArtistUi(id) }
    }

    override suspend fun artistDetail(providerId: String, artistId: String): ArtistPayload? {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = cookies.get(id)
        val detail = guarded("$id 歌手详情") { repository.artistDetail(id, cookie, artistId) }
            ?: return null
        // 专辑第一页：和热歌互不依赖，单独取（热歌已在 detail 里带回）
        val albums = guarded("$id 歌手专辑") { repository.artistAlbums(id, cookie, artistId, 1, 24) }
        return detail.toUi(id, albums?.albums.orEmpty(), albums?.hasMore ?: false)
    }

    override suspend fun albumDetail(providerId: String, albumId: String): AlbumPayload? {
        val id = providerId.ifBlank { defaultProviderId }
        val detail = guarded("$id 专辑详情") { repository.albumDetail(id, cookies.get(id), albumId) }
            ?: return null
        return AlbumPayload(
            album = detail.album.toUi(id),
            tracks = detail.songs.map { it.toUi(id) },
        )
    }

    // --------------------------------------------------------------- B2：评论

    override suspend fun comments(track: Track, page: Int, providerId: String): CommentPage? {
        val id = providerId.ifBlank { track.providerId }
        val song = songs[track.id] ?: return null
        val result = guarded("$id 评论") {
            // :core 的 comments 按 song.source 找 provider，签名 (song, cookie, page, limit)，没有 provider 参数
            repository.comments(song, cookies.get(id), page.coerceAtLeast(1), 20)
        } ?: return null
        return result.toUi()
    }

    override fun supportsComments(providerId: String): Boolean =
        // 桌面 B2 直接问 :core 的能力位；provider 未注册时 NaturalProviders 返回 null，视为不支持
        runCatching { repository.supportsComments(providerId) }.getOrDefault(false)

    // --------------------------------------------------------------- B2：链接解析

    override suspend fun parseLink(providerId: String, link: String): ParsedLink? {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = cookies.get(id)
        val text = link.trim()
        if (text.isBlank()) return null
        // 先按歌单解析，失败再按专辑，最后按单曲：三条路径互斥，串行尝试成本最低
        guarded("$id 解析歌单链接") { repository.parsePlaylist(id, cookie, text) }?.let { parsed ->
            return ParsedLink(
                kind = "playlist",
                title = parsed.item.name,
                subtitle = parsed.item.creator.ifBlank { "${parsed.songs.size} 首" },
                sourceId = id,
                collectionId = parsed.item.id,
                tracks = parsed.songs.map { it.toUi(id) },
            )
        }
        guarded("$id 解析专辑链接") { repository.parseAlbum(id, cookie, text) }?.let { parsed ->
            return ParsedLink(
                kind = "album",
                title = parsed.item.name,
                subtitle = parsed.item.creator,
                sourceId = id,
                collectionId = parsed.item.id,
                tracks = parsed.songs.map { it.toUi(id) },
            )
        }
        val song = guarded("$id 解析单曲链接") { repository.parseSong(id, cookie, text) } ?: return null
        return ParsedLink(
            kind = "song",
            title = song.name,
            subtitle = song.artist,
            sourceId = id,
            tracks = listOf(song.toUi(id)),
        )
    }

    override suspend fun library(): LibraryPayload {
        // 收藏 / 历史还没有本地库（Room 在 t2b），先用最近一次会话里点过的歌撑起"我的"页，
        // 收藏走内存 + 文件；等本地库落地后换实现，页面不用改
        val recent = songs.values.toList().takeLast(20).map { it.toUi(it.source.ifBlank { defaultProviderId }) }
        return LibraryPayload(
            favorites = favorites.map { it.toUi(it.source.ifBlank { defaultProviderId }) },
            history = recent,
            playlists = emptyList(),
            stats = LibraryStats(favorites = favorites.size, history = recent.size, playlists = 0, localTracks = 0),
        )
    }

    private val favorites = mutableListOf<Song>()

    fun toggleFavorite(track: Track): Boolean {
        val song = songs[track.id] ?: return false
        val removed = favorites.removeAll { it.id == song.id && it.source == song.source }
        if (!removed) favorites += song
        return !removed
    }

    override suspend fun lyrics(track: Track): String {
        val song = songs[track.id] ?: return ""
        return guarded("歌词") { repository.lyrics(song, cookies.get(song.source)) }.orEmpty()
    }

    override suspend fun stream(track: Track): AudioStream? {
        val song = songs[track.id] ?: return null
        val url = guarded("取流") { repository.downloadUrl(song, cookies.get(song.source)) }.orEmpty()
        if (url.isBlank()) return null
        return AudioStream(
            url = url,
            // 平台多半会带 ext；没带就从 URL 路径猜一个，libVLC 其实不需要它，只用于界面提示
            ext = song.ext.ifBlank { extensionOf(url) },
            note = if (song.source.isBlank()) "" else "来源：${song.source}",
        )
    }

    private fun extensionOf(url: String): String =
        runCatching { java.net.URI(url).path }.getOrNull()
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.take(5)
            .orEmpty()

    /** 记住这次见过的原始 Song：取流 / 歌词 / 收藏都要完整对象。 */
    private fun remember(song: Song): String {
        val trackId = "${song.source}:${song.id}"
        songs[trackId] = song
        return trackId
    }

    private fun Song.toUi(providerId: String): Track = Track(
        id = remember(this),
        title = name,
        artist = artist,
        album = album,
        providerId = source.ifBlank { providerId },
        providerName = providerNameOf(source.ifBlank { providerId }),
        durationMs = duration.toLong() * 1000L,
        coverUrl = cover,
        playable = true,
    )

    private fun TopList.toUi(providerId: String): ChartSummary = ChartSummary(
        id = id,
        name = name,
        subtitle = description.ifBlank { updateFrequency },
        playCount = playCount,
        coverUrl = cover,
        sourceId = source.ifBlank { providerId },
    )

    private fun CorePlaylist.toUi(providerId: String): PlaylistSummary = PlaylistSummary(
        id = id,
        name = name,
        curator = creator,
        trackCount = trackCount,
        playCount = playCount,
        coverUrl = cover,
        sourceId = source.ifBlank { providerId },
    )

    /** 搜索结果里的歌手行：Playlist 形状 → ArtistSummary。头像取歌单封面（平台没单独给）。 */
    private fun CorePlaylist.toArtistUi(providerId: String): ArtistSummary = ArtistSummary(
        id = id,
        name = name,
        sourceId = source.ifBlank { providerId },
        avatarUrl = cover,
        alias = creator,
    )

    private fun ArtistDetail.toUi(providerId: String, albums: List<com.mytv.mymusic.data.AlbumSummary>, hasMore: Boolean): ArtistPayload {
        val source = profile.source.ifBlank { providerId }
        return ArtistPayload(
            artist = ArtistSummary(
                id = profile.id,
                name = profile.name,
                sourceId = source,
                avatarUrl = profile.avatar,
                alias = profile.alias,
                songCount = profile.songCount,
                albumCount = profile.albumCount,
                fans = profile.fans,
            ),
            description = profile.description,
            hotSongs = hotSongs.map { it.toUi(source) },
            albums = albums.map { it.toUi(source) },
            hasMoreAlbums = hasMore,
        )
    }

    private fun com.mytv.mymusic.data.AlbumSummary.toUi(providerId: String): AlbumSummary = AlbumSummary(
        id = id,
        name = name,
        sourceId = source.ifBlank { providerId },
        coverUrl = cover,
        artist = artist,
        artistId = artistId,
        publishTime = publishTime,
        type = type,
        trackCount = trackCount,
        company = company,
        description = description,
    )

    private fun SongComment.toUi(): CommentItem = CommentItem(
        id = id,
        userName = userName,
        avatarUrl = avatar,
        content = content,
        likedCount = likedCount,
        // 平台给了文案就直用；没给就换算成"N 天前"这种相对时间
        timeText = timeText.ifBlank { relativeTime(time) },
        location = location,
    )

    private fun SongCommentPage.toUi(): CommentPage = CommentPage(
        page = page,
        comments = comments.map { it.toUi() },
        hotComments = hotComments.map { it.toUi() },
        total = total,
        hasMore = hasMore,
    )

    /** 秒级时间戳 → 「3 天前」；超过一年直接给日期。0 视为未知。 */
    private fun relativeTime(seconds: Long): String {
        if (seconds <= 0) return ""
        val diff = System.currentTimeMillis() / 1000 - seconds
        return when {
            diff < 3600 -> "${(diff / 60).coerceAtLeast(1)} 分钟前"
            diff < 86_400 -> "${diff / 3600} 小时前"
            diff < 86_400 * 365 -> "${diff / 86_400} 天前"
            else -> java.time.Instant.ofEpochSecond(seconds)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }

    /**
     * 专辑 / 歌单在搜索结果里先按「一行」展示：标题取集合名，歌手位放创建者，没有就放曲目数。
     * 点开详情是后续批次的事，B2 只要求列表能看、能点。
     */
    private fun PlaylistSummary.toTrackRow(providerId: String): Track {
        val source = sourceId.ifBlank { providerId }
        return Track(
            id = id,
            title = name,
            artist = curator.ifBlank { if (trackCount > 0) "$trackCount 首" else "" },
            providerId = source,
            providerName = providers.firstOrNull { it.id == source }?.name ?: source,
            coverUrl = coverUrl,
        )
    }

    private fun CoreProviderInfo.toUi(): ProviderInfo = ProviderInfo(
        id = id,
        name = name,
        // 桌面 B2 的搜索/榜单只关心"这个平台能不能查"，搜索或下载任一可用就列出来；
        // 具体能力位（评论、歌单写、二维码登录）留给对应的 B3/B4 页面去判断
        searchable = search || download,
    )

    private fun providerNameOf(id: String): String = providers.firstOrNull { it.id == id }?.name ?: id

    private suspend fun <T> guarded(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        lastError = "$action 失败：${error.message ?: error::class.java.simpleName}"
        null
    }
}
