package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.core.PlatformPaths
import com.mytv.mymusic.data.ArtistDetail
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.SavedSongTombstone
import com.mytv.mymusic.data.SettingsStore
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.SongComment
import com.mytv.mymusic.data.SongCommentPage
import com.mytv.mymusic.data.TopList
import com.mytv.mymusic.data.Playlist as CorePlaylist
import com.mytv.mymusic.data.ProviderInfo as CoreProviderInfo
import com.mytv.mymusic.data.native.NativeProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * `MusicCatalog` 的真实实现：桥到 `:core` 的 [MusicRepository]、`NativeProviders`
 * 以及统一持久化底座 [DesktopStorage]（Room `mymusic.db` + DataStore + Win32 DPAPI）。
 *
 * 三条纪律：
 *  1. **只暴露 UI 需要的形状**：`Song` / `TopList` / `Playlist` 在这里一次性翻译成
 *     [Track] / [ChartSummary] / [PlaylistSummary]，UI 与用例层看不到 :core 的类型；
 *  2. **原始对象双落点**：内存中保留 `trackId -> Song` 注册表，收藏/历史/歌单写入 Room 时
 *     同时序列化完整 `Song` JSON，重启后反序列化即可恢复取流与歌词能力；
 *  3. **失败不抛给 UI**：每个平台调用都 runCatching，返回空列表并记录 `lastError`，
 *     取流失败或仅获试听片段时自动走跨平台换源兜底。
 */
class CoreMusicCatalog(
    paths: PlatformPaths,
    private val repository: MusicRepository = MusicRepository(),
    val storage: DesktopStorage = DesktopStorage(paths),
) : MusicCatalog {

    private val secrets = storage.secrets
    private val db = storage.database
    val settingsStore: SettingsStore get() = storage.settings
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val songs = java.util.concurrent.ConcurrentHashMap<String, Song>()

    override fun getCookie(providerId: String): String = secrets.getCookie(providerId)

    override fun setCookie(providerId: String, cookie: String) = secrets.putCookie(providerId, cookie)

    override fun close() = storage.close()

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
        val cookie = getCookie(id)
        val topLists = guarded("$id 榜单") { repository.topLists(id, cookie) }.orEmpty()
        val hot = guarded("$id 热门单曲") { repository.hotSongs(id, cookie, 24) }.orEmpty()
        val charts = topLists.map { it.toUi(id) }
        // 部分平台不给 hotSongs（接口要登录），退一步用榜首榜单的前几首，首屏不至于空白
        val songs = if (hot.isNotEmpty()) hot else {
            val first = topLists.firstOrNull()
            first?.let { guarded("$id 榜单曲目") { repository.topListSongs(id, getCookie(id), it.id) }.orEmpty() } ?: emptyList()
        }
        return ChartsPayload(
            charts = charts,
            featured = charts.firstOrNull(),
            hotSongs = songs.map { it.toUi(id) },
        )
    }

    override suspend fun playlists(providerId: String): PlaylistsPayload {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = getCookie(id)
        val recommended = guarded("$id 推荐歌单") { repository.recommended(id, cookie) }.orEmpty()
        val categories = guarded("$id 歌单分类") { repository.categories(id, cookie) }.orEmpty()
        val byCategory = categories.take(8).associate { category ->
            category.name to guarded("$id 分类 ${category.name}") {
                repository.categoryPlaylists(id, cookie, category.id, 1, 20)
            }.orEmpty()
        }
        return PlaylistsPayload(
            recommended = recommended.map { it.toUi(id) },
            categories = (listOf("全部") + categories.map { it.name }).distinct(),
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
                guarded("$id 专辑搜索") { repository.searchAlbums(id, getCookie(id), keyword) }
                    .orEmpty()
                    // 专辑/歌单在结果列表里先当「一行」展示，点开走详情（B2 不做详情）
                    .map { it.toUi(id).toTrackRow(id) }
            }.distinctBy { it.title + it.artist }

            SearchKind.Playlist -> providerIds.flatMap { id ->
                guarded("$id 歌单搜索") { repository.searchPlaylists(id, getCookie(id), keyword) }
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
                    id to guarded("$id 搜索") { repository.searchSongs(id, getCookie(id), keyword) }.orEmpty()
                }
            }.map { it.await() }
            results
                .flatMap { (id, list) -> list.map { it.toUi(id) } }
                .distinctBy { it.title.trim() + "|" + it.artist.trim() }
                .take(60)
        }

    override suspend fun chartTracks(chart: ChartSummary): List<Track> {
        val id = chart.sourceId.ifBlank { defaultProviderId }
        return guarded("榜单曲目") { repository.topListSongs(id, getCookie(id), chart.id) }
            .orEmpty()
            .map { it.toUi(id) }
    }

    override suspend fun playlistTracks(playlist: PlaylistSummary): List<Track> {
        if (playlist.sourceId == LOCAL_PLAYLIST_SOURCE) {
            return userPlaylistTracks(playlist.id)
        }
        val id = playlist.sourceId.ifBlank { defaultProviderId }
        return guarded("歌单曲目") { repository.playlistSongs(id, getCookie(id), playlist.id) }
            .orEmpty()
            .map { it.toUi(id) }
    }

    // --------------------------------------------------------------- B2：歌手

    override suspend fun searchArtists(providerId: String, keyword: String): List<ArtistSummary> {
        val id = providerId.ifBlank { defaultProviderId }
        if (keyword.isBlank()) return emptyList()
        // 歌手搜索走单平台接口：聚合层没有歌手维度，跨平台拼结果反而会把同名歌手混在一列
        return guarded("$id 歌手搜索") { repository.searchArtists(id, getCookie(id), keyword) }
            .orEmpty()
            .map { it.toArtistUi(id) }
    }

    override suspend fun artistDetail(providerId: String, artistId: String): ArtistPayload? {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = getCookie(id)
        val detail = guarded("$id 歌手详情") { repository.artistDetail(id, cookie, artistId) }
            ?: return null
        // 专辑第一页：和热歌互不依赖，单独取（热歌已在 detail 里带回）
        val albums = guarded("$id 歌手专辑") { repository.artistAlbums(id, cookie, artistId, 1, 24) }
        return detail.toUi(id, albums?.albums.orEmpty(), albums?.hasMore ?: false)
    }

    override suspend fun albumDetail(providerId: String, albumId: String): AlbumPayload? {
        val id = providerId.ifBlank { defaultProviderId }
        val detail = guarded("$id 专辑详情") { repository.albumDetail(id, getCookie(id), albumId) }
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
            repository.comments(song, getCookie(id), page.coerceAtLeast(1), 20)
        } ?: return null
        return result.toUi()
    }

    override fun supportsComments(providerId: String): Boolean =
        // 桌面 B2 直接问 :core 的能力位；provider 未注册时 NaturalProviders 返回 null，视为不支持
        runCatching { repository.supportsComments(providerId) }.getOrDefault(false)

    // --------------------------------------------------------------- B2：链接解析

    override suspend fun parseLink(providerId: String, link: String): ParsedLink? {
        val id = providerId.ifBlank { defaultProviderId }
        val cookie = getCookie(id)
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

    override suspend fun library(): LibraryPayload = withContext(Dispatchers.IO) {
        val favRows = runCatching { db.dao().songsOnce(FAVORITE_BUCKET) }.getOrDefault(emptyList())
        val histRows = runCatching { db.dao().songsOnce(HISTORY_BUCKET) }.getOrDefault(emptyList())
        val playlistEntities = runCatching { db.library().playlistsOnce() }.getOrDefault(emptyList())
        val localEntities = runCatching { db.localTracks().all().filterNot { it.missing } }.getOrDefault(emptyList())

        val favTracks = favRows.mapNotNull { decodeSong(it.payload)?.toUi(defaultProviderId) }
        val histTracks = histRows.take(100).mapNotNull { decodeSong(it.payload)?.toUi(defaultProviderId) }
        val localTracks = localEntities.map { entity ->
            val song = Song(
                id = entity.localId,
                name = entity.title,
                artist = entity.artist,
                album = entity.album,
                duration = entity.durationSec,
                source = com.mytv.mymusic.data.local.LOCAL_SOURCE,
                url = entity.uri,
                ext = entity.extension,
                extra = buildMap {
                    put("data_path", entity.dataPath)
                    if (entity.lrcUri.isNotBlank()) put("lrc_uri", entity.lrcUri)
                },
            )
            song.toUi(com.mytv.mymusic.data.local.LOCAL_SOURCE).copy(providerName = "本地音乐")
        }
        val userPlaylists = playlistEntities.map { entity ->
            val entries = runCatching { db.library().entriesOnce(entity.id) }.getOrDefault(emptyList())
            val firstCover = entity.linkCover.ifBlank {
                entries.firstOrNull()?.let { decodeSong(it.payload)?.cover }.orEmpty()
            }
            PlaylistSummary(
                id = entity.id,
                name = entity.name,
                curator = if (entity.bound) "已绑定平台歌单" else "自建歌单",
                trackCount = entries.size,
                coverUrl = firstCover,
                sourceId = LOCAL_PLAYLIST_SOURCE,
            )
        }
        LibraryPayload(
            favorites = favTracks,
            history = histTracks,
            localTracks = localTracks,
            playlists = userPlaylists,
            stats = LibraryStats(
                favorites = favTracks.size,
                history = histTracks.size,
                playlists = userPlaylists.size,
                localTracks = localTracks.size,
            ),
        )
    }

    /** 切换收藏并落入 Room `saved_songs`（带墓碑以兼容 Android 三方同步）。 */
    override suspend fun toggleFavoritePersisted(track: Track): Boolean = withContext(Dispatchers.IO) {
        val song = resolveSong(track)
        val existing = db.dao().songsOnce(FAVORITE_BUCKET).any { it.key == song.key }
        val now = System.currentTimeMillis()
        if (existing) {
            db.dao().remove(FAVORITE_BUCKET, song.key)
            db.dao().saveTombstone(SavedSongTombstone(song.key, FAVORITE_BUCKET, now))
            false
        } else {
            db.dao().clearTombstone(FAVORITE_BUCKET, song.key)
            db.dao().save(SavedSong(song.key, FAVORITE_BUCKET, encodeSong(song), now))
            true
        }
    }

    override fun toggleFavorite(track: Track) {
        runBlocking(Dispatchers.IO) { toggleFavoritePersisted(track) }
    }

    /** 记录最近播放到 Room `saved_songs(bucket = "history")`，重开不丢。 */
    override suspend fun recordHistory(track: Track) = withContext(Dispatchers.IO) {
        val song = resolveSong(track)
        runCatching {
            db.dao().save(SavedSong(song.key, HISTORY_BUCKET, encodeSong(song), System.currentTimeMillis()))
        }
        Unit
    }

    // ---- 自建歌单 CRUD（Room `playlists` + `playlist_entries`） ----

    override suspend fun createUserPlaylist(name: String): PlaylistSummary? = withContext(Dispatchers.IO) {
        val trimmed = name.trim().ifBlank { return@withContext null }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val entity = PlaylistEntity(
            id = id,
            name = trimmed,
            createdAt = now,
            updatedAt = now,
            dirty = true,
        )
        runCatching { db.library().putPlaylist(entity) }.getOrNull() ?: return@withContext null
        PlaylistSummary(
            id = id,
            name = trimmed,
            curator = "自建歌单",
            trackCount = 0,
            coverUrl = "",
            sourceId = LOCAL_PLAYLIST_SOURCE,
        )
    }

    override suspend fun renameUserPlaylist(playlistId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (playlistId.isBlank() || trimmed.isBlank()) return@withContext false
        runCatching {
            db.library().renamePlaylist(playlistId, trimmed, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    override suspend fun deleteUserPlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        if (playlistId.isBlank()) return@withContext false
        // 走软删除保留 deleted_at + dirty 标记，保证和 Android 同步时能正确传播删除
        runCatching {
            db.library().softDelete(playlistId, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Boolean = withContext(Dispatchers.IO) {
        if (playlistId.isBlank()) return@withContext false
        val song = resolveSong(track)
        runCatching {
            val current = db.library().entriesOnce(playlistId)
            // 已存在同曲目时不重复追加，保持歌单整洁
            if (current.any { it.songKey == song.key }) return@runCatching true
            val updated = current + PlaylistEntryEntity(
                playlistId = playlistId,
                position = current.size,
                songKey = song.key,
                payload = encodeSong(song),
            )
            db.library().replaceEntries(playlistId, updated)
            db.library().markDirty(playlistId, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        if (playlistId.isBlank() || trackId.isBlank()) return@withContext false
        runCatching {
            val current = db.library().entriesOnce(playlistId)
            val remaining = current
                .filterNot { it.songKey == trackId }
                .mapIndexed { idx, entry -> entry.copy(position = idx) }
            if (remaining.size == current.size) return@runCatching false
            db.library().replaceEntries(playlistId, remaining)
            db.library().markDirty(playlistId, System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    override suspend fun userPlaylistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        if (playlistId.isBlank()) return@withContext emptyList()
        runCatching {
            db.library().entriesOnce(playlistId)
                .mapNotNull { entry -> decodeSong(entry.payload)?.toUi(defaultProviderId) }
        }.getOrDefault(emptyList())
    }

    override suspend fun scanLocalFolder(folder: java.io.File): Int = withContext(Dispatchers.IO) {
        val scanned = LocalMusicScanner.scanFolder(folder)
        if (scanned.isEmpty()) return@withContext 0
        runCatching { db.localTracks().upsert(scanned) }.getOrNull() ?: return@withContext 0
        scanned.size
    }

    override suspend fun lyrics(track: Track): String {
        val song = resolveSong(track)
        if (song.source == com.mytv.mymusic.data.local.LOCAL_SOURCE) {
            val lrcPath = song.extra["lrc_uri"].orEmpty()
            if (lrcPath.isNotBlank()) {
                val file = java.io.File(lrcPath)
                if (file.isFile) return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            }
            return ""
        }
        return guarded("歌词") { repository.lyrics(song, getCookie(song.source)) }.orEmpty()
    }

    /**
     * 取可播放流（带本地文件直出 + 自动跨平台换源兜底）：
     * 0. 本地音乐直接返回 `file:///` URI 交给 libVLC 本地解码；
     * 1. 在线曲目先向原平台请求 `playableStream`（保留真实音质、是否试听及附加信息）；
     * 2. 若原平台取流为空、失败或仅给 30s 试听片段，则在备选可用平台并发搜索同名同歌手曲目并取流；
     * 3. 备选全部无完整流但原平台有试听流时，退回试听流并注明。
     */
    override suspend fun stream(track: Track): AudioStream? {
        val song = resolveSong(track)
        if (song.source == com.mytv.mymusic.data.local.LOCAL_SOURCE) {
            val path = song.extra["data_path"].orEmpty()
            val url = when {
                path.isNotBlank() && java.io.File(path).isFile -> java.io.File(path).toURI().toString()
                song.url.isNotBlank() -> song.url
                else -> return null
            }
            return AudioStream(
                url = url,
                ext = song.ext.ifBlank { extensionOf(url) },
                note = "本地文件",
            )
        }
        val primary = resolveDirectStream(song)
        if (primary != null && !primary.preview) {
            return primary.toAudioStream(song, switched = false)
        }
        // 原平台失败或仅返回试听片段：尝试跨平台自动换源
        val fallback = resolveFallbackStream(track, excludeProvider = song.source)
        if (fallback != null) return fallback
        return primary?.toAudioStream(song, switched = false)
    }

    private suspend fun resolveDirectStream(song: Song): com.mytv.mymusic.data.native.PlayableStream? {
        if (song.source.isBlank() || song.id.isBlank()) return null
        val cookie = getCookie(song.source)
        val playable = guarded("${song.source} 取流") {
            repository.playableStream(song, cookie, storage.paths.cacheDir)
        }
        if (playable != null && playable.url.isNotBlank()) return playable
        val url = guarded("${song.source} 直链") { repository.downloadUrl(song, cookie) }.orEmpty()
        return if (url.isNotBlank()) com.mytv.mymusic.data.native.PlayableStream(url = url, ext = song.ext) else null
    }

    private suspend fun resolveFallbackStream(track: Track, excludeProvider: String): AudioStream? =
        kotlinx.coroutines.coroutineScope {
            val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (query.isBlank()) return@coroutineScope null
            val fallbackProviders = FALLBACK_PROVIDER_ORDER
                .filter { id -> id != excludeProvider && providers.any { it.id == id && it.searchable } }
            val candidates = fallbackProviders.map { pid ->
                async(Dispatchers.IO) {
                    val found = guarded("$pid 换源搜索") { repository.searchSongs(pid, getCookie(pid), query) }.orEmpty()
                    pickBestMatch(track, found)
                }
            }.mapNotNull { it.await() }

            for (candidate in candidates) {
                val stream = resolveDirectStream(candidate)
                if (stream != null && !stream.preview) {
                    remember(candidate)
                    return@coroutineScope stream.toAudioStream(candidate, switched = true)
                }
            }
            null
        }

    private fun pickBestMatch(target: Track, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null
        val wantTitle = normalizeTitle(target.title)
        val wantArtist = normalizeTitle(target.artist)
        return candidates.maxByOrNull { song ->
            var score = 0
            val title = normalizeTitle(song.name)
            val artist = normalizeTitle(song.artist)
            if (title == wantTitle) score += 60
            else if (title.contains(wantTitle) || wantTitle.contains(title)) score += 30
            if (wantArtist.isNotBlank() && (artist.contains(wantArtist) || wantArtist.contains(artist))) score += 30
            if (!song.isVip) score += 10
            if (target.durationMs > 0L && song.duration > 0) {
                val diffSec = kotlin.math.abs(target.durationMs / 1000L - song.duration)
                if (diffSec <= 5L) score += 10
            }
            score
        }
    }

    private fun normalizeTitle(raw: String): String =
        raw.lowercase().replace(Regex("[\\s\\p{Punct}（）【】《》·]"), "")

    private fun com.mytv.mymusic.data.native.PlayableStream.toAudioStream(
        song: Song,
        switched: Boolean,
    ): AudioStream {
        val headers = buildMap {
            extra["referer"]?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            extra["user_agent"]?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val sourceName = providerNameOf(song.source)
        val note = buildString {
            if (switched) append("已自动换源至 ").append(sourceName) else append("来源：").append(sourceName)
            if (level.isNotBlank()) append(" · ").append(level)
            if (preview) append(" · 试听片段")
        }
        return AudioStream(
            url = url,
            ext = ext.ifBlank { song.ext.ifBlank { extensionOf(url) } },
            headers = headers,
            note = note,
        )
    }

    private fun encodeSong(song: Song): String = json.encodeToString(song)

    private fun decodeSong(payload: String): Song? =
        runCatching { json.decodeFromString<Song>(payload) }.getOrNull()?.also { remember(it) }

    /** 从内存注册表拿原始 Song；若重启后未命中则按 UI Track 构造最小 Song 以便落库。 */
    private fun resolveSong(track: Track): Song {
        songs[track.id]?.let { return it }
        val rawId = track.id.substringAfter(':', track.id)
        val reconstructed = Song(
            id = rawId,
            name = track.title,
            artist = track.artist,
            album = track.album,
            duration = (track.durationMs / 1000L).toInt().coerceAtLeast(0),
            source = track.providerId,
            cover = track.coverUrl,
        )
        songs[track.id] = reconstructed
        return reconstructed
    }

    private fun extensionOf(url: String): String =
        runCatching { java.net.URI(url).path }.getOrNull()
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.take(5)
            .orEmpty()

    companion object {
        const val FAVORITE_BUCKET = "favorite"
        const val HISTORY_BUCKET = "history"
        const val LOCAL_PLAYLIST_SOURCE = "local_playlist"

        /** 跨平台自动换源优先级：优先当前实测可用率高且免登录可取流的平台。 */
        private val FALLBACK_PROVIDER_ORDER = listOf("qq", "kuwo", "migu", "kugou", "bilibili", "netease")
    }

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
