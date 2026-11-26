package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.native.PlayableStream

/**
 * 洛雪自定义音源实现的注入点。
 *
 * 真正的实现 [LxSources] 要 Context（脚本落盘、assets 里的 host 脚本）和 QuickJS，
 * 只能留在 :app；而 `MusicRepository`（:core）在构造时需要一个默认实现。
 * 所以把「当前实现」放在这里：Application.onCreate 里 `LxSources.attach(this)` 之后
 * 立刻 `LxPlaybackHost.current = LxSources`，行为与搬迁前“默认就是 LxSources”一致。
 *
 * 没装（桌面端早期、只跑聚合单测）时退化成 [None]：不支持任何平台、也解析不出流。
 */
object LxPlaybackHost {
    /** 空实现：所有查询都回答“没有”。 */
    object None : LxPlayback {
        override fun supports(providerId: String): Boolean = false

        override suspend fun resolve(song: Song, quality: String): PlayableStream? = null
    }

    @Volatile
    var current: LxPlayback = None
}
