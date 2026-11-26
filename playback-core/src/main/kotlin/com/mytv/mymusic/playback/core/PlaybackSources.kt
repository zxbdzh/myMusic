package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.Song

/** 领域模型 → 引擎音源。桌面侧各 Provider 解析完 URL 后统一走这里，别在调用点各拼一遍。 */
fun Song.toPlaybackSource(headers: Map<String, String> = emptyMap()): PlaybackSource = PlaybackSource(
    url = url,
    ext = ext,
    headers = headers,
    trackId = key,
    title = title,
    // 直播流（云听电台）没有时长，不参与"播完切下一首"
    live = isLive,
)
