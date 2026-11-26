package com.mytv.mymusic.desktop.state

import androidx.compose.runtime.Immutable
import com.mytv.mymusic.desktop.theme.Appearance
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
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val volume: Float = 0.8f,
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
    fun closeCollection()

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

    // ---- 播放 ----
    fun playTrack(track: Track, queue: List<Track> = emptyList())
    fun playTracks(tracks: List<Track>, startIndex: Int)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun toggleFavorite(track: Track)

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

    /** 退出时释放播放后端；窗口关闭时调用一次。 */
    fun shutdown()
}
