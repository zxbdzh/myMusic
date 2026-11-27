package com.mytv.mymusic.desktop.state

/**
 * 桌面骨架期的示例数据源。
 *
 * 作用和 Android 的假数据面板一样：UI 骨架必须先"有东西可看、可点、可播（模拟）"，
 * 否则截图验收只能看到一堆空白。接 t2 之后把 `Main` 里的
 * `DemoMusicCatalog()` 换成 `CoreMusicCatalog(providers)` 即可，页面一行都不用改。
 *
 * 数据刻意保持"像真的"：真实的平台名、真实的歌名歌手（只用作文本展示，
 * 不附带任何音频或歌词内容，歌词全部是自写的示例句，避免版权文本进仓库）。
 */
class DemoMusicCatalog : MusicCatalog {

    override val providers: List<ProviderInfo> = listOf(
        ProviderInfo("netease", "网易云"),
        ProviderInfo("qq", "QQ音乐"),
        ProviderInfo("kugou", "酷狗"),
        ProviderInfo("bilibili", "Bilibili"),
        ProviderInfo("apple", "Apple Music"),
    )

    override val defaultProviderId: String = "netease"

    override suspend fun charts(providerId: String): ChartsPayload {
        val featured = DemoFixtures.charts.first()
        return ChartsPayload(
            charts = DemoFixtures.charts,
            featured = featured,
            hotSongs = DemoFixtures.chartTracks(featured.id),
        )
    }

    override suspend fun playlists(providerId: String): PlaylistsPayload = PlaylistsPayload(
        recommended = DemoFixtures.playlists,
        categories = DemoFixtures.categories,
        byCategory = DemoFixtures.playlists.groupBy { it.curator },
    )

    override suspend fun search(providerId: String, query: String, kind: SearchKind): List<Track> {
        val key = query.trim()
        if (key.isBlank()) return emptyList()
        return when (kind) {
            SearchKind.Song -> DemoFixtures.tracks.filter { it.matches(key) }
            // 专辑 / 歌单这两种在骨架里没有独立的实体，用同一批单曲做跨字段匹配，
            // 保证"切换 kind 能出不同结果"这条交互是通的（Android 里是分开的列表）。
            SearchKind.Album -> DemoFixtures.tracks.filter { it.album.contains(key, ignoreCase = true) }
            SearchKind.Playlist -> DemoFixtures.playlists
                .filter { it.name.contains(key, ignoreCase = true) || it.curator.contains(key) }
                .flatMap { playlist -> DemoFixtures.playlistTracks(playlist.id).take(6) }
                .distinctBy { it.id }
        }
    }

    override suspend fun chartTracks(chart: ChartSummary): List<Track> = DemoFixtures.chartTracks(chart.id)

    override suspend fun playlistTracks(playlist: PlaylistSummary): List<Track> =
        DemoFixtures.playlistTracks(playlist.id)

    // ---- B2 接缝：demo 数据源不做歌手/评论/链接解析，返回空值即可 ----
    // 页面对这些能力都有"平台不支持"的空态兜底，所以 demo 侧静默给空。

    override suspend fun searchArtists(providerId: String, keyword: String): List<ArtistSummary> = emptyList()

    override suspend fun artistDetail(providerId: String, artistId: String): ArtistPayload? = null

    override suspend fun albumDetail(providerId: String, albumId: String): AlbumPayload? = null

    override suspend fun comments(track: Track, page: Int, providerId: String): CommentPage? = null

    override suspend fun parseLink(providerId: String, link: String): ParsedLink? = null

    override suspend fun library(): LibraryPayload = LibraryPayload(
        favorites = DemoFixtures.tracks.take(5),
        history = DemoFixtures.tracks.drop(3).take(8),
        playlists = DemoFixtures.localPlaylists,
        stats = LibraryStats(
            favorites = 5,
            history = 8,
            playlists = DemoFixtures.localPlaylists.size,
            localTracks = 12,
        ),
    )

    override suspend fun lyrics(track: Track): String = DemoFixtures.lyrics[track.id].orEmpty()

    override suspend fun stream(track: Track): AudioStream? =
        if (!track.playable) null
        else AudioStream(
            url = "demo://track/${track.id}",
            ext = "mp3",
            note = "示例音源：桌面骨架还没有真实取流，播放进度由模拟输出推进",
        )

    private fun Track.matches(key: String): Boolean =
        title.contains(key, ignoreCase = true) ||
            artist.contains(key, ignoreCase = true) ||
            album.contains(key, ignoreCase = true)
}
