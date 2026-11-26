package com.mytv.mymusic.data

/**
 * 匹配缓存的落库契约。
 *
 * `MatchCacheEntity` 是 Room 实体、`AggregateDao` 是 Room DAO，两者都留在 :app 的
 * AppStore（`@Database` 的实体表清单也在那边）。聚合匹配本身已经搬进 :core，
 * 只用到「按 source_key + target_provider 读一条 / 写一条」这两个动作，
 * 所以这里用最小的数据类 + 接口表达，:app 侧用 [RoomMatchCacheStore] 适配。
 */
data class MatchCacheRecord(
    val sourceKey: String,
    val targetProvider: String,
    val targetKey: String,
    val score: Float,
    /** 命中曲目的 JSON（`Song` 序列化结果）；空串表示“确认没有同一版本”。 */
    val payload: String,
    val checkedAt: Long,
)

/** 匹配缓存的读写入口；实现在 :app（Room），桌面端可以换成文件或内存实现。 */
interface MatchCacheStore {
    suspend fun match(sourceKey: String, targetProvider: String): MatchCacheRecord?

    suspend fun saveMatch(entry: MatchCacheRecord)
}
