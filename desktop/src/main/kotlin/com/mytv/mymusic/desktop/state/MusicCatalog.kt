package com.mytv.mymusic.desktop.state

/**
 * 桌面版的数据入口 = Android 里 `MusicRepository` + `NativeProviders` + Room 的那一大坨。
 *
 * 这个接口是 t2 / t3 落地后的唯一接缝：UI 与状态层只认它，
 * 换成 `CoreMusicCatalog`（把 :core 的 provider 注册表接进来）时，
 * 不需要动 `MusicStore` 或任何一个页面。方法全部是 suspend，
 * 实现内部自己切 IO，UI 侧只负责展示 loading / error。
 *
 * 约定：
 *  - 不抛异常给 UI：失败通过返回空列表 + [MusicCatalog.lastError] 或者页面的 error 字段暴露；
 *  - 结果必须已经按显示顺序排好，UI 不再排序（多音源聚合的排序规则属于业务层）；
 *  - [search] 的 [kind] 语义和 Android 一致：单曲 / 专辑 / 歌单。
 */
interface MusicCatalog {
    /** 当前可用的平台列表，顺序即界面里 chip 的顺序。 */
    val providers: List<ProviderInfo>

    /** 默认选中的平台；空字符串表示"全部音源"。 */
    val defaultProviderId: String

    suspend fun charts(providerId: String): ChartsPayload

    suspend fun playlists(providerId: String): PlaylistsPayload

    suspend fun search(providerId: String, query: String, kind: SearchKind): List<Track>

    suspend fun chartTracks(chart: ChartSummary): List<Track>

    suspend fun playlistTracks(playlist: PlaylistSummary): List<Track>

    suspend fun library(): LibraryPayload

    /** 返回原始 LRC / 纯文本；解析交给 `LyricsParser`，这样解析逻辑可以单测、也能搬去 :core。 */
    suspend fun lyrics(track: Track): String

    /** 取一路可播放的音源；拿不到时返回 null，由调用方给出"暂不可播"的提示。 */
    suspend fun stream(track: Track): AudioStream?
}

data class ChartsPayload(
    val charts: List<ChartSummary> = emptyList(),
    val featured: ChartSummary? = null,
    val hotSongs: List<Track> = emptyList(),
)

data class PlaylistsPayload(
    val recommended: List<PlaylistSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val byCategory: Map<String, List<PlaylistSummary>> = emptyMap(),
) {
    /** 分类 chips 点中之后要显示的那一页。 */
    fun forCategory(name: String): List<PlaylistSummary> =
        if (name.isBlank() || name == "全部") recommended else byCategory[name].orEmpty()
}
