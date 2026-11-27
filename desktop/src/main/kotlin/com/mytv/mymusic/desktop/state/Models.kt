package com.mytv.mymusic.desktop.state

import androidx.compose.runtime.Immutable

/**
 * 桌面 UI 层的数据模型。
 *
 * 为什么这里另建一套而不是直接用 `:core` 的 `data.Song` / `TopList`：
 *  - t2 正在把 Android 的 data 层搬进 :core，签名还在变，UI 骨架不能跟着抖；
 *  - UI 需要的字段比领域模型少得多（标题、歌手、时长、封面、来源），
 *    多出来的字段（extra、quality、canonicalId…）只会让 Compose 的重组判断变重。
 * 接入 :core 时由 `MusicCatalog` 的实现做一次映射（见 CoreMusicCatalog 计划），
 * 映射是单向的，不会出现两套模型互相污染。
 */
@Immutable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val providerId: String = "demo",
    val providerName: String = "示例音源",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    /** 没有版权 / 需要登录时置 false，列表里灰掉并给出提示，而不是点了才报错。 */
    val playable: Boolean = true,
)

@Immutable
data class ChartSummary(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val playCount: Long = 0L,
    val coverUrl: String = "",
    /** 平台 id：详情页回源取数要带它（UI 只认 MusicCatalog，不认 :core 的 provider）。 */
    val sourceId: String = "",
)

@Immutable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val curator: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val coverUrl: String = "",
    /** 平台 id：打开歌单时回源取数用。 */
    val sourceId: String = "",
)

@Immutable
data class ProviderInfo(
    val id: String,
    val name: String,
    val searchable: Boolean = true,
)

enum class SearchKind(val label: String) {
    Song("单曲"),
    Album("专辑"),
    Playlist("歌单"),
}

@Immutable
data class LyricLine(val startMs: Long, val text: String)

/**
 * 一首歌的歌词。
 *
 * [plain] 为 true 表示没有时间轴（纯文本或解析失败），界面只做等距轮播，不跟着进度走。
 * [offsetMs] 是 LRC 的 `[offset:]` 标签，AsyncLyrics 里很常见，解析时已经并入每一行的 startMs，
 * 这里留着只为调试显示。
 */
@Immutable
data class LyricsDoc(
    val lines: List<LyricLine>,
    val plain: Boolean = false,
    val offsetMs: Long = 0L,
) {
    /** 当前该高亮的那一行；没有时间轴或进度在开头之前返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (plain || lines.isEmpty()) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= positionMs) index = i else break
        }
        return index
    }
}

@Immutable
data class LibraryStats(
    val favorites: Int = 0,
    val history: Int = 0,
    val playlists: Int = 0,
    val localTracks: Int = 0,
)

@Immutable
data class LibraryPayload(
    val favorites: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val stats: LibraryStats = LibraryStats(),
)

/**
 * 一路可播放音频。
 *
 * [headers] 交给播放引擎当请求头（防盗链的 UA / Referer 在取流时已经算好）；
 * [note] 给界面显示"为什么是试听 / 为什么降级"这类说明，不参与播放。
 */
@Immutable
data class AudioStream(
    val url: String,
    val ext: String = "",
    val headers: Map<String, String> = emptyMap(),
    val note: String = "",
)

/** 榜单页状态（对照 Android `ChartsUiState`）。 */
@Immutable
data class ChartsUiState(
    val loading: Boolean = false,
    val charts: List<ChartSummary> = emptyList(),
    val featured: ChartSummary? = null,
    val hotSongs: List<Track> = emptyList(),
    val error: String = "",
    val loadedAt: Long = 0L,
)

/** 歌单页状态：推荐 + 分类（对照 Android `PlaylistsUiState`，去掉听歌模式那三档，桌面 P2 再做）。 */
@Immutable
data class PlaylistsUiState(
    val loading: Boolean = false,
    val recommended: List<PlaylistSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val category: String = "全部",
    val categoryPlaylists: List<PlaylistSummary> = emptyList(),
    val categoryLoading: Boolean = false,
    val error: String = "",
)

/** 搜索页状态。 */
@Immutable
data class SearchUiState(
    val query: String = "",
    val submitted: String = "",
    val kind: SearchKind = SearchKind.Song,
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val history: List<String> = emptyList(),
) {
    val landing: Boolean get() = submitted.isBlank()
}

/** 我的页状态。 */
@Immutable
data class LibraryUiState(
    val loading: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val stats: LibraryStats = LibraryStats(),
    val error: String = "",
)

/**
 * 打开着的榜单 / 歌单详情（Android 里是独立的 collection 路由，桌面骨架先做成页内子视图）。
 * 字段名对齐 Android `CollectionUiState` 的语义，方便以后换成真正的路由。
 */
@Immutable
data class CollectionUiState(
    val title: String = "",
    val subtitle: String = "",
    val source: String = "",
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
) {
    val open: Boolean get() = title.isNotBlank()
}

/** 播放后端的可观测状态：libVLC 没装 / 未接 t3 时界面要如实说明，而不是假装在播。 */
@Immutable
data class EngineUiState(
    val backend: String = "",
    val ready: Boolean = false,
    val note: String = "",
)

/** 三个数字，按 1.2 万 / 3.4 亿 这样显示，和 Android 的 formatPlayCount 同一规则。 */
fun formatCount(count: Long): String = when {
    count <= 0 -> ""
    count < 10_000 -> count.toString()
    count < 100_000_000 -> trimZero(count / 10_000.0) + "万"
    else -> trimZero(count / 100_000_000.0) + "亿"
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun trimZero(value: Double): String {
    val text = if (value >= 100) value.toInt().toString() else String.format("%.1f", value)
    return text.removeSuffix(".0")
}

// ---------------------------------------------------------------------------
// B2 列表页新增领域模型（歌手 / 专辑 / 评论 / 链接解析）。
// 与 :core 的同名概念一一对应，但只带 UI 用得到的字段——映射在 CoreMusicCatalog 一次完成。
// ---------------------------------------------------------------------------

/** 歌手摘要：搜索结果行与歌手页头部共用。 */
@Immutable
data class ArtistSummary(
    val id: String,
    val name: String,
    /** 平台 id：打开歌手页时回源取数用（UI 不认 :core 的 provider）。 */
    val sourceId: String = "",
    val avatarUrl: String = "",
    val alias: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val fans: Long = 0,
)

/** 歌手页一屏数据。albums 取第一页（约 24 张），翻页是 B3 的事。 */
@Immutable
data class ArtistPayload(
    val artist: ArtistSummary,
    val description: String = "",
    val hotSongs: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val hasMoreAlbums: Boolean = false,
)

/** 专辑摘要：歌手页专辑网格与专辑详情头部共用。 */
@Immutable
data class AlbumSummary(
    val id: String,
    val name: String,
    val sourceId: String = "",
    val coverUrl: String = "",
    val artist: String = "",
    /** 主歌手的平台 id：专辑页"更多作品"回歌手入口用。 */
    val artistId: String = "",
    /** 统一成 yyyy-MM-dd 或 yyyy；平台没给就空。 */
    val publishTime: String = "",
    /** 平台给的类型：专辑 / EP / Single / 现场…… */
    val type: String = "",
    val trackCount: Int = 0,
    val company: String = "",
    val description: String = "",
)

/** 专辑详情：头部资料 + 曲目。 */
@Immutable
data class AlbumPayload(
    val album: AlbumSummary,
    val tracks: List<Track> = emptyList(),
)

/** 一条歌曲评论。timeText 优先用平台给的文案，否则由实现换算相对时间。 */
@Immutable
data class CommentItem(
    val id: String,
    val userName: String,
    val avatarUrl: String = "",
    val content: String,
    val likedCount: Long = 0,
    val timeText: String = "",
    val location: String = "",
)

/** 评论分页。[hotComments] 只在第一页返回。 */
@Immutable
data class CommentPage(
    val page: Int,
    val comments: List<CommentItem> = emptyList(),
    val hotComments: List<CommentItem> = emptyList(),
    val total: Long = 0,
    val hasMore: Boolean = false,
)

/**
 * 链接解析结果：把一条平台分享链接变成可展示的内容。
 * [collectionId] 非空表示是歌单 / 专辑这类集合，详情页拿它回源取曲目。
 */
@Immutable
data class ParsedLink(
    /** song / playlist / album。 */
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val sourceId: String = "",
    val collectionId: String = "",
    val tracks: List<Track> = emptyList(),
)

/** 歌手页状态：进入 loading，取回 payload 或给出 error。 */
@Immutable
data class ArtistUiState(
    val loading: Boolean = false,
    val payload: ArtistPayload? = null,
    val error: String = "",
)

/** 评论侧栏状态。open=false 时 AppShell 不渲染。 */
@Immutable
data class CommentsUiState(
    val open: Boolean = false,
    val track: Track? = null,
    val loading: Boolean = false,
    /** 已经加载到的最大页码；追加下一页用。 */
    val page: Int = 0,
    val comments: List<CommentItem> = emptyList(),
    val hotComments: List<CommentItem> = emptyList(),
    val total: Long = 0,
    val hasMore: Boolean = false,
    val error: String = "",
)
