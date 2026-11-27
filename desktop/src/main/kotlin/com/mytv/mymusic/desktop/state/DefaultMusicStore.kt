package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.desktop.state.audio.AudioOutput
import com.mytv.mymusic.desktop.state.audio.AudioOutputs
import com.mytv.mymusic.desktop.state.audio.VlcAudioOutput
import com.mytv.mymusic.desktop.state.usecase.ChartsUseCase
import com.mytv.mymusic.desktop.state.usecase.LibraryUseCase
import com.mytv.mymusic.desktop.state.usecase.PlaybackUseCase
import com.mytv.mymusic.desktop.state.usecase.PlaylistsUseCase
import com.mytv.mymusic.desktop.state.usecase.SearchUseCase
import com.mytv.mymusic.desktop.state.usecase.readable
import com.mytv.mymusic.desktop.theme.Appearance
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
) : MusicStore {

    private val chartsUseCase = ChartsUseCase(catalog)
    private val playlistsUseCase = PlaylistsUseCase(catalog)
    private val searchUseCase = SearchUseCase(catalog)
    private val libraryUseCase = LibraryUseCase(catalog)

    /** 播放后端：有 libVLC 用 libVLC，否则模拟输出。UI 上会如实显示是哪一个。 */
    private val audio: AudioOutput = AudioOutputs.create(scope)
    private val playback = PlaybackUseCase(catalog, audio, scope)

    private val _state = MutableStateFlow(
        MusicUiState(
            providers = catalog.providers,
            providerId = catalog.defaultProviderId,
            providerName = providerName(catalog.defaultProviderId),
            engine = EngineUiState(
                backend = audio.label,
                ready = audio is VlcAudioOutput,
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
                        lyrics = playing.lyrics,
                        lyricsLoading = playing.lyricsLoading,
                        // 播放域的错误单独带出来，但不要冲掉页面自己的错误提示
                        notice = playing.error.ifBlank { it.notice },
                    )
                }
            }
        }
        scope.launch { bootstrap() }
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
                    search = it.search.copy(history = searchUseCase.historySnapshot()),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 导航

    override fun navigate(route: Route) {
        _state.update { it.copy(route = route) }
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

    override fun openChart(chart: ChartSummary) {
        _state.update {
            it.copy(
                route = Route.Charts,
                collection = CollectionUiState(
                    title = chart.name,
                    subtitle = chart.subtitle,
                    source = "榜单",
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
                _state.update { it.copy(notice = "链接解析失败") }
            } else {
                _state.update { it.copy(notice = "已解析：${parsed.title}") }
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

    override fun toggleFavorite(track: Track) {
        val already = _state.value.favorites.contains(track.id)
        _state.update {
            it.copy(
                favorites = if (already) it.favorites - track.id else it.favorites + track.id,
                notice = if (already) "已取消收藏《${track.title}》" else "已收藏《${track.title}》",
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
    }

    override fun clearNotice() {
        _state.update { it.copy(notice = "") }
    }

    override fun dismissError() {
        _state.update { it.copy(error = "") }
    }

    override fun shutdown() {
        playback.release()
        scope.cancel()
    }

    private fun providerName(id: String): String =
        catalog.providers.firstOrNull { it.id == id }?.name ?: "全部音源"
}
