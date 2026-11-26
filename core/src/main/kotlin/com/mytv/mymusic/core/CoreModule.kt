package com.mytv.mymusic.core

/**
 * :core 模块的身份与契约版本。
 *
 * ADR: docs/pc/ARCHITECTURE.md「模块边界」。这里只放"模块事实"，不放业务状态——
 * 搬运期的 [com.mytv.mymusic.data.Song] 等模型会陆续落到 `com.mytv.mymusic.core.model`，
 * 包名保持 `com.mytv.mymusic.*` 前缀，搬运过程中 import 只需改前缀、不必改名，
 * 这样 :app 与 :desktop 可以长期共用同一份源码。
 */
object CoreModule {
    const val NAME: String = "core"

    /**
     * 契约版本：平台接口（登录 / 取流 / 歌词 / 歌单写入）签名发生不兼容变化时 +1，
     * 供 :app 与 :desktop 各自实现的适配层判断是否需要跟进。
     */
    const val CONTRACT_VERSION: Int = 1
}
