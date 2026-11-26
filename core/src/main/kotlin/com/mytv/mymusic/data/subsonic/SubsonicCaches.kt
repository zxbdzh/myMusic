package com.mytv.mymusic.data.subsonic

import com.mytv.mymusic.data.Song

/**
 * Subsonic 封面缓存的契约。
 *
 * 实现要 `Context.cacheDir` 与 Android 的 `ContentProvider`（`SubsonicCoverProvider`
 * 用 content:// 给播放器取图），只能在 :app；:core 的 [SubsonicProvider] 只负责拼地址。
 * 没注入时封面留空，其余能力不受影响。
 */
interface SubsonicCoverSource {
    /** 封面地址：Android 侧是 `content://.../cover/<account>/<id>?size=…`。 */
    fun uri(accountId: String, coverArt: String, size: Int = 300): String
}

/**
 * Subsonic 曲目离线缓存的契约。
 *
 * 实现是 :app 的 `SubsonicTrackCache`（Room 表 `subsonic_tracks`）。接口只为让
 * [SubsonicProvider] 能在离线时回落到上次搜到的结果；没注入就不回落。
 */
interface SubsonicTrackStore {
    /** 按关键字查缓存，语义与搜索结果一致（含 [offset] 分页）。 */
    suspend fun search(query: String, limit: Int, offset: Int): List<Song>

    /** 把刚搜到的曲目写进缓存；实现自己决定去重与保留策略。 */
    suspend fun putAll(songs: List<Song>)
}
