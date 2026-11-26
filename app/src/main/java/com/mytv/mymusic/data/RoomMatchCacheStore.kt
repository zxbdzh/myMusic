package com.mytv.mymusic.data

/**
 * `MatchCacheStore` 的 Room 实现：把 :core 的匹配缓存契约接到 AppStore 的 [AggregateDao] 上。
 *
 * 实体表 `match_cache` 与它所在的 `@Database` 都留在 :app，:core 只认 [MatchCacheRecord]；
 * 这里做一次字段搬运，行为与以前 MatchJob 直接操作 DAO 完全一致（包括返回 null 表示没缓存）。
 */
class RoomMatchCacheStore(private val dao: AggregateDao) : MatchCacheStore {
    override suspend fun match(sourceKey: String, targetProvider: String): MatchCacheRecord? =
        dao.match(sourceKey, targetProvider)?.let {
            MatchCacheRecord(
                sourceKey = it.sourceKey,
                targetProvider = it.targetProvider,
                targetKey = it.targetKey,
                score = it.score,
                payload = it.payload,
                checkedAt = it.checkedAt,
            )
        }

    override suspend fun saveMatch(entry: MatchCacheRecord) {
        dao.saveMatch(
            MatchCacheEntity(
                sourceKey = entry.sourceKey,
                targetProvider = entry.targetProvider,
                targetKey = entry.targetKey,
                score = entry.score,
                payload = entry.payload,
                checkedAt = entry.checkedAt,
            )
        )
    }
}
