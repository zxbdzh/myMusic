package com.mytv.mymusic.desktop.state.usecase

import com.mytv.mymusic.desktop.state.ChartsUiState
import com.mytv.mymusic.desktop.state.LibraryUiState
import com.mytv.mymusic.desktop.state.MusicCatalog
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.PlaylistsPayload
import com.mytv.mymusic.desktop.state.PlaylistsUiState
import com.mytv.mymusic.desktop.state.SearchKind
import com.mytv.mymusic.desktop.state.SearchUiState

/**
 * 把 catalog 的异常翻译成界面能显示的一句话。
 * 不把堆栈丢给用户，也不吞掉原因——标题 + 简短原因足够定位。
 */
internal fun Throwable.readable(prefix: String): String {
    val reason = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    return "$prefix：$reason"
}

/**
 * 榜单取数。只做"取 → 状态"这一件事，方便单测直接构造。
 * 对照 Android `MusicViewModel.loadCharts`（含缓存与 provider 切换），桌面骨架不做缓存，P2 再补。
 */
class ChartsUseCase(private val catalog: MusicCatalog) {
    suspend fun load(providerId: String): ChartsUiState = runCatching {
        catalog.charts(providerId)
    }.fold(
        onSuccess = { payload ->
            ChartsUiState(
                charts = payload.charts,
                featured = payload.featured,
                hotSongs = payload.hotSongs,
                loadedAt = System.currentTimeMillis(),
            )
        },
        onFailure = { ChartsUiState(error = it.readable("榜单加载失败")) },
    )
}

/**
 * 歌单取数：推荐列表 + 分类切换。
 * 分类页按需取，避免首屏把全部分类都拉一遍（Android 上分类是分页的，桌面先一次取全）。
 */
class PlaylistsUseCase(private val catalog: MusicCatalog) {
    private var lastPayload: PlaylistsPayload = PlaylistsPayload()

    suspend fun load(providerId: String): PlaylistsUiState = runCatching {
        catalog.playlists(providerId)
    }.fold(
        onSuccess = { payload ->
            lastPayload = payload
            PlaylistsUiState(
                recommended = payload.recommended,
                categories = payload.categories,
                category = "全部",
                categoryPlaylists = payload.recommended,
            )
        },
        onFailure = { PlaylistsUiState(error = it.readable("歌单加载失败")) },
    )

    /** 切分类：优先用上次 payload，拿不到就去取一次。 */
    suspend fun select(providerId: String, category: String): List<PlaylistSummary> {
        if (lastPayload.recommended.isEmpty()) lastPayload = runCatching { catalog.playlists(providerId) }.getOrNull() ?: lastPayload
        return lastPayload.forCategory(category)
    }
}

/**
 * 搜索。历史记录暂存内存（Android 落 DataStore 的 `searchHistory`），
 * 接 :core 后换成 DataStore 的 JVM 实现，接口不变。
 */
class SearchUseCase(private val catalog: MusicCatalog) {
    private val history = ArrayDeque<String>()

    fun historySnapshot(): List<String> = history.toList()

    suspend fun search(providerId: String, query: String, kind: SearchKind): SearchUiState {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            return SearchUiState(query = query, history = historySnapshot())
        }
        remember(keyword)
        return runCatching {
            catalog.search(providerId, keyword, kind)
        }.fold(
            onSuccess = { results ->
                SearchUiState(
                    query = query,
                    submitted = keyword,
                    kind = kind,
                    results = results,
                    history = historySnapshot(),
                )
            },
            onFailure = {
                SearchUiState(
                    query = query,
                    submitted = keyword,
                    kind = kind,
                    error = it.readable("搜索失败"),
                    history = historySnapshot(),
                )
            },
        )
    }

    private fun remember(query: String) {
        history.remove(query)
        history.addFirst(query)
        while (history.size > MAX_HISTORY) history.removeLast()
    }

    private companion object {
        /** 和 Android 搜一页历史同量级：够用又不至于把顶栏撑爆。 */
        const val MAX_HISTORY = 8
    }
}

/** 我的页取数。收藏 / 历史 / 自建歌单在 :core 接上后来自 Room，桌面骨架先由 catalog 给。 */
class LibraryUseCase(private val catalog: MusicCatalog) {
    suspend fun load(): LibraryUiState = runCatching {
        catalog.library()
    }.fold(
        onSuccess = { payload ->
            LibraryUiState(
                favorites = payload.favorites,
                history = payload.history,
                localTracks = payload.localTracks,
                playlists = payload.playlists,
                stats = payload.stats,
            )
        },
        onFailure = { LibraryUiState(error = it.readable("曲库加载失败")) },
    )
}
