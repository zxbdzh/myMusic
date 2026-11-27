package com.mytv.mymusic.desktop.state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mytv.mymusic.desktop.state.audio.AudioOutput
import com.mytv.mymusic.desktop.state.audio.AudioOutputs
import com.mytv.mymusic.desktop.state.usecase.ChartsUseCase
import com.mytv.mymusic.desktop.state.usecase.LibraryUseCase
import com.mytv.mymusic.desktop.state.usecase.PlayMode
import com.mytv.mymusic.desktop.state.usecase.PlaybackUseCase
import com.mytv.mymusic.desktop.state.usecase.PlaylistsUseCase
import com.mytv.mymusic.desktop.state.usecase.SearchUseCase
import com.mytv.mymusic.desktop.state.usecase.readable
import com.mytv.mymusic.desktop.theme.Appearance
import com.mytv.mymusic.desktop.theme.BrandSeed
import com.mytv.mymusic.desktop.theme.PaletteKind
import com.mytv.mymusic.desktop.theme.ThemeMode
import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.SleepTimer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 状态层实现：把 4 个取数用例 + 1 个播放用例拼成一个 [MusicUiState]。
 *
 * 这是 Android `MusicViewModel`（7101 行）的桌面版对应物，但只保留主界面读得到的那部分：
 *  - 没有 `savedStateHandle`、没有 `Lifecycle`、没有 DataStore 读取的门闸（桌面没有进程重建语义）；
 *  - 所有 intent 都是"改状态 → 起一个协程 → 再改状态"，没有回调链；
 *  - 播放域状态由 [PlaybackUseCase] 单向汇入，避免同一个字段两处可写。
 *
 * 线程模型：UI 线程只调方法，内部一律 `scope.launch`（[Dispatchers.Default]）。
 * Compose 侧 `collectAsState()` 会自动切回主线程，够用；接 :core 时如果换成 Swing 主线程调度，
 * 只需要把 [scope] 换成 `Dispatchers.Swing`。
 */
class DefaultMusicStore(
    private val catalog: MusicCatalog = DemoMusicCatalog(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    initialAppearance: Appearance = Appearance(),
    /** 音频后端工厂：生产默认走真实 libVLC（缺库明确报错不假播）；离屏截图与单测可显式注入模拟后端。 */
    audioFactory: (CoroutineScope) -> AudioOutput = AudioOutputs::create,
) : MusicStore {

    private val chartsUseCase = ChartsUseCase(catalog)
    private val playlistsUseCase = PlaylistsUseCase(catalog)
    private val searchUseCase = SearchUseCase(catalog)
    private val libraryUseCase = LibraryUseCase(catalog)

    private val audio: AudioOutput = audioFactory(scope)
    private val playback = PlaybackUseCase(catalog, audio, scope)

    override val equalizer: EqualizerControl? get() = audio.equalizer
    override val crossfade: CrossfadeControl? get() = audio.crossfade
    override val sleepTimer: SleepTimer? get() = audio.sleepTimer

    private val _state = MutableStateFlow(
        MusicUiState(
            providers = catalog.providers,
            providerId = catalog.defaultProviderId,
            providerName = providerName(catalog.defaultProviderId),
            engine = EngineUiState(
                backend = audio.label,
                ready = audio.ready,
                note = audio.detail,
            ),
            appearance = initialAppearance,
        ),
    )
    override val state: StateFlow<MusicUiState> = _state.asStateFlow()

    init {
        scope.launch {
            playback.state.collect { playing ->
                _state.update {
                    it.copy(
                        current = playing.current,
                        queue = playing.queue,
                        queueIndex = playing.queueIndex,
                        positionMs = playing.positionMs,
                        durationMs = playing.durationMs,
                        playing = playing.playing,
                        buffering = playing.buffering,
                        volume = playing.volume,
                        playMode = playing.playMode,
                        streamNote = playing.streamNote,
                        lyrics = playing.lyrics,
                        lyricsLoading = playing.lyricsLoading,
                        // 播放域的错误或换源提示带出来，但不要冲掉页面自己的错误提示
                        notice = playing.error.ifBlank { it.notice },
                    )
                }
            }
        }
        scope.launch { restoreSettings() }
        scope.launch { bootstrap() }
    }

    /** 启动时从 `:core` SettingsStore 恢复持久化的外观与搜索历史（与 Android 共用一份设置键）。 */
    private suspend fun restoreSettings() {
        val coreCatalog = catalog as? CoreMusicCatalog ?: return
        val saved = runCatching { coreCatalog.settingsStore.state.first() }.getOrNull() ?: return
        val mode = when (saved.theme.lowercase()) {
            "dark" -> ThemeMode.Dark
            "light" -> ThemeMode.Light
            else -> ThemeMode.System
        }
        val palette = PaletteKind.entries.firstOrNull { it.name.equals(saved.browseStyle, ignoreCase = true) }
            ?: PaletteKind.TonalSpot
        val seed = if (saved.customSeed != 0) Color(saved.customSeed) else BrandSeed
        _state.update {
            it.copy(
                appearance = Appearance(
                    mode = mode,
                    palette = palette,
                    seed = seed,
                    amoled = saved.amoled,
                    contrast = saved.contrast,
                    reduceMotion = saved.reduceMotion,
                ),
            )
        }
    }

    /**
     * 首屏取数。截图模式在组合之前显式调用它，保证第一帧就有内容（不靠 sleep 等协程）。
     * 三个请求并发发出：它们互不依赖，串行只会让冷启动多等两倍。
     */
    suspend fun bootstrap() {
        val providerId = _state.value.providerId
        _state.update { it.copy(charts = it.charts.copy(loading = true), playlists = it.playlists.copy(loading = true), library = it.library.copy(loading = true)) }
        kotlinx.coroutines.coroutineScope {
            val charts = async { chartsUseCase.load(providerId) }
            val playlists = async { playlistsUseCase.load(providerId) }
            val library = async { libraryUseCase.load() }
            val chartsState = charts.await()
            val playlistsState = playlists.await()
            val libraryState = library.await()
            _state.update {
                it.copy(
                    ready = true,
                    charts = chartsState,
                    playlists = playlistsState,
                    library = libraryState,
                    // 重启后从 Room 恢复红心集合，避免列表里有喜欢但曲目行不亮红心
                    favorites = libraryState.favorites.mapTo(linkedSetOf()) { track -> track.id },
                    search = it.search.copy(history = searchUseCase.historySnapshot()),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 导航

    override fun navigate(route: Route) {
        _state.update { it.copy(route = route) }
        // 进入「我的」时重拉一次：收藏/历史在别的页面会变，缓存到首屏那份早就过期了
        if (route == Route.Library) refreshLibrary()
    }

    // ---------------------------------------------------------------- 音源

    override fun selectProvider(providerId: String) {
        _state.update {
            it.copy(
                providerId = providerId,
                providerName = providerName(providerId),
                collection = CollectionUiState(),
            )
        }
        refreshCharts()
        refreshPlaylists()
    }

    // ---------------------------------------------------------------- 榜单

    override fun refreshCharts() {
        val providerId = _state.value.providerId
        _state.update { it.copy(charts = it.charts.copy(loading = true, error = "")) }
        scope.launch {
            val loaded = chartsUseCase.load(providerId)
            _state.update { it.copy(charts = loaded, error = loaded.error.ifBlank { it.error }) }
        }
    }

    override fun playChart(chart: ChartSummary) {
        scope.launch {
            val tracks = runCatching { catalog.chartTracks(chart) }.getOrElse {
                _state.update { state -> state.copy(notice = it.readable("榜单播放失败")) }
                return@launch
            }
            tracks.firstOrNull()?.let { playback.play(it, tracks) }
                ?: _state.update { it.copy(notice = "榜单「${chart.name}」暂无可播放曲目") }
        }
    }

    override fun openChart(chart: ChartSummary) {
        _state.update {
            it.copy(
                route = Route.Charts,
                collection = CollectionUiState(
                    title = chart.name,
                    subtitle = chart.subtitle,
                    source = "榜单",
                    coverUrl = chart.coverUrl,
                    loading = true,
                ),
            )
        }
        scope.launch {
            val tracks = runCatching { catalog.chartTracks(chart) }.getOrElse {
                _state.update { state -> state.copy(collection = state.collection.copy(loading = false, error = it.readable("榜单内容加载失败"))) }
                return@launch
            }
            _state.update { state -> state.copy(collection = state.collection.copy(tracks = tracks, loading = false)) }
        }
    }

    override fun closeCollection() {
        _state.update { it.copy(collection = CollectionUiState()) }
    }

    // ---------------------------------------------------------------- 歌单

    override fun refreshPlaylists() {
        val providerId = _state.value.providerId
        _state.update { it.copy(playlists = it.playlists.copy(loading = true, error = "")) }
        scope.launch {
            val loaded = playlistsUseCase.load(providerId)
            _state.update { it.copy(playlists = loaded) }
        }
    }

    override fun selectPlaylistCategory(name: String) {
        val providerId = _state.value.providerId
        _state.update { it.copy(playlists = it.playlists.copy(category = name, categoryLoading = true)) }
        scope.launch {
            val list = playlistsUseCase.select(providerId, name)
            _state.update { it.copy(playlists = it.playlists.copy(category = name, categoryPlaylists = list, categoryLoading = false)) }
        }
    }

    override fun openPlaylist(playlist: PlaylistSummary) {
        _state.update {
            it.copy(
                route = Route.Playlists,
                collection = CollectionUiState(
                    title = playlist.name,
                    subtitle = if (playlist.trackCount > 0) "${playlist.trackCount} 首 · ${playlist.curator}" else playlist.curator,
                    source = "歌单",
                    coverUrl = playlist.coverUrl,
                    loading = true,
                ),
            )
        }
        scope.launch {
            val tracks = runCatching { catalog.playlistTracks(playlist) }.getOrElse {
                _state.update { state -> state.copy(collection = state.collection.copy(loading = false, error = it.readable("歌单内容加载失败"))) }
                return@launch
            }
            _state.update { state -> state.copy(collection = state.collection.copy(tracks = tracks, loading = false)) }
        }
    }

    // ---------------------------------------------------------------- 搜索

    override fun updateQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
    }

    override fun submitSearch(query: String) {
        val keyword = query.ifBlank { _state.value.search.query }.trim()
        if (keyword.isBlank()) return
        _state.update { it.copy(search = it.search.copy(query = keyword, loading = true, error = "")) }
        scope.launch {
            val result = searchUseCase.search(_state.value.providerId, keyword, _state.value.search.kind)
            _state.update { it.copy(search = result, error = result.error.ifBlank { it.error }) }
        }
    }

    override fun submitSearchFromHistory(query: String) {
        updateQuery(query)
        submitSearch(query)
    }

    override fun selectSearchKind(kind: SearchKind) {
        _state.update { it.copy(search = it.search.copy(kind = kind)) }
        val submitted = _state.value.search.submitted
        if (submitted.isNotBlank()) submitSearch(submitted)
    }

    override fun clearSearch() {
        _state.update { it.copy(search = it.search.copy(query = "", submitted = "", results = emptyList(), loading = false, error = "")) }
    }

    override fun retrySearch() {
        val submitted = _state.value.search.submitted
        if (submitted.isNotBlank()) submitSearch(submitted) else _state.update { it.copy(search = it.search.copy(error = "")) }
    }

    // ---------------------------------------------------------------- B2：歌手 / 专辑 / 评论 / 链接解析
    // 这些都是「点开才取」的按需数据：失败只写 state 的 error，不让页面崩。

    override fun openArtist(providerId: String, artistId: String, name: String) {
        _state.update {
            it.copy(
                detailKey = DetailKey.Artist(providerId, artistId, name),
                artist = ArtistUiState(loading = true),
            )
        }
        scope.launch {
            val payload = runCatching { catalog.artistDetail(providerId, artistId) }.getOrNull()
            _state.update { state ->
                state.copy(
                    artist = if (payload != null) {
                        ArtistUiState(payload = payload)
                    } else {
                        ArtistUiState(error = "歌手资料加载失败")
                    },
                )
            }
        }
    }

    override fun openAlbum(providerId: String, albumId: String, name: String) {
        _state.update {
            it.copy(
                detailKey = DetailKey.Album(providerId, albumId, name),
                album = null,
                albumLoading = true,
                albumError = "",
            )
        }
        scope.launch {
            val payload = runCatching { catalog.albumDetail(providerId, albumId) }.getOrNull()
            _state.update { state ->
                state.copy(
                    album = payload,
                    albumLoading = false,
                    albumError = if (payload == null) "专辑加载失败" else "",
                )
            }
        }
    }

    override fun closeDetail() {
        _state.update {
            it.copy(detailKey = null, artist = ArtistUiState(), album = null, albumError = "")
        }
    }

    override fun openComments(track: Track) {
        _state.update {
            it.copy(comments = CommentsUiState(open = true, track = track, loading = true))
        }
        scope.launch {
            val first = runCatching { catalog.comments(track, 1) }.getOrNull()
            _state.update { state ->
                state.copy(
                    comments = if (first == null) {
                        state.comments.copy(loading = false, error = "评论加载失败")
                    } else {
                        CommentsUiState(
                            open = true,
                            track = track,
                            page = first.page,
                            comments = first.comments,
                            hotComments = first.hotComments,
                            total = first.total,
                            hasMore = first.hasMore,
                        )
                    },
                )
            }
        }
    }

    override fun closeComments() {
        _state.update { it.copy(comments = CommentsUiState()) }
    }

    override fun loadMoreComments() {
        val current = _state.value.comments
        val track = current.track ?: return
        if (!current.hasMore || current.loading) return
        _state.update { it.copy(comments = it.comments.copy(loading = true, error = "")) }
        scope.launch {
            val next = runCatching { catalog.comments(track, current.page + 1) }.getOrNull()
            _state.update { state ->
                state.copy(
                    comments = if (next == null) {
                        state.comments.copy(loading = false, error = "下一页评论加载失败")
                    } else {
                        state.comments.copy(
                            loading = false,
                            page = next.page,
                            comments = state.comments.comments + next.comments,
                            hasMore = next.hasMore,
                            total = next.total,
                        )
                    },
                )
            }
        }
    }

    override fun parseLink(link: String) {
        scope.launch {
            val parsed = runCatching {
                catalog.parseLink(_state.value.providerId, link)
            }.getOrNull()
            if (parsed == null) {
                _state.update { it.copy(notice = "链接解析失败：未识别的平台链接或接口暂不可用") }
                return@launch
            }
            when (parsed.kind) {
                "song" -> {
                    val first = parsed.tracks.firstOrNull()
                    if (first != null) {
                        playback.play(first, parsed.tracks)
                        _state.update { it.copy(notice = "已解析并开始播放《${parsed.title}》") }
                    } else {
                        _state.update { it.copy(notice = "已解析《${parsed.title}》，但无曲目信息") }
                    }
                }
                else -> {
                    _state.update {
                        it.copy(
                            route = Route.Playlists,
                            collection = CollectionUiState(
                                title = parsed.title.ifBlank { "解析结果" },
                                subtitle = parsed.subtitle,
                                source = if (parsed.kind == "album") "专辑" else "歌单",
                                tracks = parsed.tracks,
                                loading = false,
                            ),
                            notice = "已解析：${parsed.title}（${parsed.tracks.size} 首）",
                        )
                    }
                }
            }
        }
    }

    override fun clearParsedLink() {
        _state.update { it.copy(notice = "") }
    }

    override fun openArtistFromSearch(summary: ArtistSummary) {
        openArtist(summary.sourceId.ifBlank { _state.value.providerId }, summary.id, summary.name)
    }

    // ---------------------------------------------------------------- 播放

    override fun playTrack(track: Track, queue: List<Track>) {
        playback.play(track, queue)
    }

    override fun playTracks(tracks: List<Track>, startIndex: Int) {
        playback.playQueue(tracks, startIndex)
    }

    override fun playNext(track: Track) = playback.insertNext(track)

    override fun togglePlay() {
        val current = _state.value
        if (current.current == null) {
            // 迷你播放条上先按播放、但队列还空着：拿首屏热门第一首起播，
            // 和 Android「点播放键就随机起播」的行为一致，避免按了没反应
            val fallback = current.charts.hotSongs.firstOrNull()
                ?: current.library.history.firstOrNull()
                ?: return
            playback.play(fallback, current.charts.hotSongs.ifEmpty { listOf(fallback) })
            return
        }
        playback.toggle()
    }

    override fun next() = playback.next()

    override fun previous() = playback.previous()

    override fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    override fun setVolume(volume: Float) = playback.setVolume(volume)

    override fun setPlayMode(mode: PlayMode) {
        playback.setPlayMode(mode)
        _state.update { it.copy(notice = "播放模式：${mode.label}") }
    }

    override fun cyclePlayMode() {
        val next = playback.cyclePlayMode()
        _state.update { it.copy(notice = "播放模式：${next.label}") }
    }

    override fun toggleFavorite(track: Track) {
        val already = _state.value.favorites.contains(track.id)
        // 先乐观更新红心态，再异步落 Room 并刷新「我的」列表
        _state.update {
            it.copy(
                favorites = if (already) it.favorites - track.id else it.favorites + track.id,
                notice = if (already) "已取消收藏《${track.title}》" else "已收藏《${track.title}》",
            )
        }
        scope.launch {
            val nowLiked = runCatching { catalog.toggleFavoritePersisted(track) }.getOrDefault(!already)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    favorites = if (nowLiked) it.favorites + track.id else it.favorites - track.id,
                )
            }
        }
    }

    override fun refreshLibrary() {
        _state.update { it.copy(library = it.library.copy(loading = true, error = "")) }
        scope.launch {
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    favorites = loaded.favorites.mapTo(linkedSetOf()) { track -> track.id },
                )
            }
        }
    }

    // ---------------------------------------------------------------- 自建歌单 CRUD

    override fun createUserPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            val created = catalog.createUserPlaylist(trimmed)
            if (created == null) {
                _state.update { it.copy(notice = "创建歌单失败") }
                return@launch
            }
            val loaded = libraryUseCase.load()
            _state.update { it.copy(library = loaded, notice = "已创建歌单「${created.name}」") }
        }
    }

    override fun renameUserPlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (playlistId.isBlank() || trimmed.isBlank()) return
        scope.launch {
            val ok = catalog.renameUserPlaylist(playlistId, trimmed)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (ok) "已重命名为「$trimmed」" else "重命名失败",
                )
            }
        }
    }

    override fun deleteUserPlaylist(playlistId: String) {
        if (playlistId.isBlank()) return
        scope.launch {
            val ok = catalog.deleteUserPlaylist(playlistId)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (ok) "已删除歌单" else "删除歌单失败",
                )
            }
        }
    }

    override fun addTrackToPlaylist(playlistId: String, track: Track) {
        scope.launch {
            val ok = catalog.addTrackToPlaylist(playlistId, track)
            val loaded = libraryUseCase.load()
            val targetName = loaded.playlists.firstOrNull { it.id == playlistId }?.name.orEmpty()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (ok) "已加入歌单「${targetName.ifBlank { "我的歌单" }}」" else "加入歌单失败",
                )
            }
        }
    }

    override fun addTrackToDefaultPlaylist(track: Track) {
        scope.launch {
            val existing = _state.value.library.playlists.firstOrNull()
                ?: catalog.createUserPlaylist("我建的歌单")
            if (existing == null) {
                _state.update { it.copy(notice = "加入歌单失败：无法创建歌单") }
                return@launch
            }
            val ok = catalog.addTrackToPlaylist(existing.id, track)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (ok) "已将《${track.title}》加入「${existing.name}」" else "加入歌单失败",
                )
            }
        }
    }

    override fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        scope.launch {
            val ok = catalog.removeTrackFromPlaylist(playlistId, trackId)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (ok) "已从歌单移除" else "移除失败",
                )
            }
        }
    }

    override fun scanLocalFolder(folder: java.io.File) {
        scope.launch {
            _state.update { it.copy(notice = "正在扫描本地目录：${folder.name}...") }
            val count = runCatching { catalog.scanLocalFolder(folder) }.getOrDefault(0)
            val loaded = libraryUseCase.load()
            _state.update {
                it.copy(
                    library = loaded,
                    notice = if (count > 0) "已导入 $count 首本地音乐" else "未在该目录找到支持的音频文件",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 平台凭据（DPAPI）

    override fun getProviderCookie(providerId: String): String = catalog.getCookie(providerId)

    override fun setProviderCookie(providerId: String, cookie: String) {
        catalog.setCookie(providerId, cookie)
        val name = providerName(providerId)
        _state.update {
            it.copy(
                notice = if (cookie.isBlank()) "已清除 $name 登录凭据" else "已保存 $name 登录凭据（DPAPI 加密）",
            )
        }
    }

    // ---------------------------------------------------------------- 覆盖层

    override fun openPlayer() {
        _state.update { it.copy(playerOpen = true, posterOpen = false, lyricsOpen = false) }
    }

    override fun closePlayer() {
        _state.update { it.copy(playerOpen = false, lyricsOpen = false) }
    }

    override fun openLyrics() {
        _state.update { it.copy(lyricsOpen = true, playerOpen = false, posterOpen = false) }
    }

    override fun closeLyrics() {
        _state.update { it.copy(lyricsOpen = false, playerOpen = true) }
    }

    override fun openPosterWall() {
        _state.update { it.copy(posterOpen = true, playerOpen = false, lyricsOpen = false) }
    }

    override fun closePosterWall() {
        _state.update { it.copy(posterOpen = false) }
    }

    // ---------------------------------------------------------------- 外观

    override fun setAppearance(appearance: Appearance) {
        _state.update { it.copy(appearance = appearance) }
        val coreCatalog = catalog as? CoreMusicCatalog ?: return
        scope.launch {
            runCatching {
                val settings = coreCatalog.settingsStore
                settings.theme(
                    when (appearance.mode) {
                        ThemeMode.Dark -> "dark"
                        ThemeMode.Light -> "light"
                        ThemeMode.System -> "system"
                    },
                )
                settings.browseStyle(appearance.palette.name)
                settings.customSeed(appearance.seed.toArgb())
                settings.amoled(appearance.amoled)
                settings.contrast(appearance.contrast)
                settings.reduceMotion(appearance.reduceMotion)
            }
        }
    }

    override fun clearNotice() {
        _state.update { it.copy(notice = "") }
    }

    override fun dismissError() {
        _state.update { it.copy(error = "") }
    }

    override fun shutdown() {
        playback.release()
        catalog.close()
        scope.cancel()
    }

    private fun providerName(id: String): String =
        catalog.providers.firstOrNull { it.id == id }?.name ?: "全部音源"
}
