package com.mytv.mymusic.data

/**
 * 本地音乐在 `Song.source` 里的固定标识。
 *
 * 与 :app 的 `data/local/LocalModels.kt` 里 `LOCAL_SOURCE` 是同一个值（都是 `"local"`）。
 * 之所以在 :core 再声明一份：`SourceResolver`（:core）要判断“来源是不是本地音乐”，
 * 而 `LocalModels.kt` 里全是 Room 实体、只能留在 :app。两个常量名字不同，避免同包顶层
 * 声明在两个模块里重名导致解析歧义；值是协议的一部分，改动必须两边一致。
 */
const val LOCAL_SOURCE_ID = "local"
