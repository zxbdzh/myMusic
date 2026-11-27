package com.mytv.mymusic.desktop.state

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.desktop.state.usecase.PlayMode
import com.mytv.mymusic.desktop.theme.Appearance
import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.SleepTimer
import kotlinx.coroutines.flow.StateFlow

/**
 * 主导航。四个入口与 Android `TopTab` 同名同序；
 * 设置不占入口位（Android 也是从"我的"右上角进），桌面放在导航栏底部。
 */
enum class Route(val label: String) {
    Charts("榜单"),
    Playlists("歌单"),
    Search("搜索"),
    Library("我的"),
    Settings("设置"),
}

/**
 * 详情页在页内子视图之上的一层"路由 key"。
 *
 * Android 端是 navigation-compose 的 `collection/{key}` / `artist/{id}` / `album/{id}`，
 * 桌面用这一个 sealed class 顶替：AppShell 按它决定渲染哪一页，返回键就是弹栈。
 * key 里的字符串全部是"回源取数要的最小信息"（平台 id + 平台侧 id），
 * 不持有整包 payload——payload 由 store 按需拉取后放进对应 UiState。
 */
sealed class DetailKey {
    /** 榜单 / 歌单详情（沿用旧 collection 子视图的数据流）。 */
    data class Collection(val key: String) : DetailKey()

    /** 歌手主页。 */
    data class Artist(val providerId: String, val artistId: String, val name: String) : DetailKey()

    /** 专辑详情。 */
    data class Album(val providerId: String, val albumId: String, val name: String) : DetailKey()
}

/**
 * 整个主窗口的可渲染状态。
 *
 * 它是 Android `MusicViewModel`（7101 行）里"界面要读的那一份"的桌面版：
 * Android 侧把状态摊成了十几个 StateFlow（state / favorites / searchState / position …），
 * 桌面窗口只有一块屏幕，合成一个不可变快照更好推理，也方便截图模式直接渲染。
 * 拆分关系：
 *  - 榜单 / 歌单 / 搜索 / 我的 → 各自的 UseCase 只负责取数，结果落在这里；
 *  - 播放（队列、进度、歌词）→ `PlaybackUseCase` 持有，store 把它并进来；
 *  - 播放器页 / 歌词页 / 海报墙是覆盖层，用布尔量表示，和 Android 的 `playerOpen/posterOpen` 对应。
 */
@Immutable
data class MusicUiState(
    val ready: Boolean = false,
    val route: Route = Route.Charts,
    val providers: List<ProviderInfo> = emptyList(),
    val providerId: String = "",
    val providerName: String = "",
    val charts: ChartsUiState = ChartsUiState(),
    val playlists: PlaylistsUiState = PlaylistsUiState(),
    val search: SearchUiState = SearchUiState(),
    val library: LibraryUiState = LibraryUiState(),
    val collection: CollectionUiState = CollectionUiState(),
    /** 歌手页 / 专辑详情（B2）。detailKey 决定渲染哪一页，null 表示没有详情打开。 */
    val artist: ArtistUiState = ArtistUiState(),
    val album: AlbumPayload? = null,
    val albumLoading: Boolean = false,
    val albumError: String = "",
    /** 评论侧栏。open=false 时 AppShell 不渲染。 */
    val comments: CommentsUiState = CommentsUiState(),
    /** 详情路由栈：非空时 RouteBody 渲染详情页而不是入口页。 */
    val detailKey: DetailKey? = null,
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val volume: Float = 0.8f,
    val playMode: PlayMode = PlayMode.Sequence,
    val streamNote: String = "",
    val playerOpen: Boolean = false,
    val lyricsOpen: Boolean = false,
    val posterOpen: Boolean = false,
    val lyrics: LyricsDoc? = null,
    val lyricsLoading: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val engine: EngineUiState = EngineUiState(),
    val appearance: Appearance = Appearance(),
    /** 一次性提示（播放失败、切歌说明），界面显示后调 [MusicStore.clearNotice]。 */
    val notice: String = "",
    val error: String = "",
) {
    val hasCover: Boolean get() = current != null
    val overlayOpen: Boolean get() = playerOpen || lyricsOpen || posterOpen
}

/**
 * 状态层契约。
 *
 * 只暴露"用户能做的那几件事"，页面拿不到协程作用域、也拿不到 catalog——
 * 这是把 7101 行 ViewModel 拆开的核心约束：页面变纯函数，状态迁移只有这一处。
 * 所有方法都可以从任意线程调（内部切 main），实现见 [DefaultMusicStore]。
 */
interface MusicStore {
    val state: StateFlow<MusicUiState>

    // ---- 导航 ----
    fun navigate(route: Route)

    // ---- 音源 ----
    fun selectProvider(providerId: String)

    // ---- 榜单 ----
    fun refreshCharts()
    fun openChart(chart: ChartSummary)
    fun playChart(chart: ChartSummary)
    fun closeCollection()

    // ---- 歌手 / 专辑（B2）----
    fun openArtist(providerId: String, artistId: String, name: String)
    fun openAlbum(providerId: String, albumId: String, name: String)
    fun closeDetail()

    // ---- 评论（B2）----
    fun openComments(track: Track)
    fun closeComments()
    fun loadMoreComments()

    // ---- 链接解析（B2）----
    fun parseLink(link: String)
    fun clearParsedLink()

    // ---- 歌单 ----
    fun refreshPlaylists()
    fun selectPlaylistCategory(name: String)
    fun openPlaylist(playlist: PlaylistSummary)

    // ---- 搜索 ----
    fun updateQuery(query: String)
    fun submitSearch(query: String = "")
    fun submitSearchFromHistory(query: String)
    fun selectSearchKind(kind: SearchKind)
    fun clearSearch()
    fun retrySearch()

    /** 搜索页里点开一个歌手结果行（B2：搜索的 kind 集合里也放歌手）。 */
    fun openArtistFromSearch(summary: ArtistSummary)

    // ---- 播放 ----
    fun playTrack(track: Track, queue: List<Track> = emptyList())
    fun playTracks(tracks: List<Track>, startIndex: Int)
    /** 右键「下一首播放」：把曲目插到当前曲目之后，不动正在播的那首。 */
    fun playNext(track: Track)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setPlayMode(mode: PlayMode) = Unit
    fun cyclePlayMode() = Unit
    fun toggleFavorite(track: Track)

    /** 重新拉「我的」页数据（收藏/历史/自建歌单）；进入「我的」时会自动调，操作后也用它刷新。 */
    fun refreshLibrary()

    // ---- 自建歌单 CRUD（Room 持久化） ----
    fun createUserPlaylist(name: String) = Unit
    fun renameUserPlaylist(playlistId: String, newName: String) = Unit
    fun deleteUserPlaylist(playlistId: String) = Unit
    fun addTrackToPlaylist(playlistId: String, track: Track) = Unit
    fun addTrackToDefaultPlaylist(track: Track) = Unit
    fun removeTrackFromPlaylist(playlistId: String, trackId: String) = Unit

    // ---- 平台凭据（DPAPI 加密落盘） ----
    fun getProviderCookie(providerId: String): String = ""
    fun setProviderCookie(providerId: String, cookie: String) = Unit

    // ---- 覆盖层 ----
    fun openPlayer()
    fun closePlayer()
    fun openLyrics()
    fun closeLyrics()
    fun openPosterWall()
    fun closePosterWall()

    // ---- 外观与杂项 ----
    fun setAppearance(appearance: Appearance)
    fun clearNotice()
    fun dismissError()

    // ---- 音频引擎高阶控制（透出自 :playback-vlc） ----
    /** 10 段均衡器控制；原生后端未就绪或测试模拟态为 null。 */
    val equalizer: EqualizerControl? get() = null

    /** 自动接歌 / 交叉淡化控制；原生后端未就绪或测试模拟态为 null。 */
    val crossfade: CrossfadeControl? get() = null

    /** 定时关闭控制；原生后端未就绪或测试模拟态为 null。 */
    val sleepTimer: SleepTimer? get() = null

    /** 退出时释放播放后端；窗口关闭时调用一次。 */
    fun shutdown()
}
