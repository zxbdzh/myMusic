package com.mytv.mymusic.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.mytv.mymusic.data.local.LocalRefDao
import com.mytv.mymusic.data.local.LocalRefEntity
import com.mytv.mymusic.data.local.LocalRemoteEntity
import com.mytv.mymusic.data.local.LocalTrackDao
import com.mytv.mymusic.data.local.LocalTrackEntity
import com.mytv.mymusic.data.local.LocalUploadDao
import com.mytv.mymusic.data.local.LocalUploadEntity
import com.mytv.mymusic.data.subsonic.SubsonicTrackDao
import com.mytv.mymusic.data.subsonic.SubsonicTrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// Room 的库声明：18 个实体 + 8 个 DAO + 12 个迁移。
// 搬进 :core 后 Android 与桌面共用同一套 schema 与迁移，打开方式各用各的：
//   Android: Room.databaseBuilder(context, AppDatabase::class.java, "mymusic.db")（见 app/AppDatabaseAndroid.kt）
//   JVM/桌面: openAppDatabase(path)（本文件底部，BundledSQLiteDriver）

// 同一首歌可以同时在“喜欢”和“最近播放”里，所以主键是 (key, bucket)
@Entity(tableName = "saved_songs", primaryKeys = ["key", "bucket"])
data class SavedSong(val key: String, val bucket: String, val payload: String, val updatedAt: Long)

/** 取消收藏的墓碑，用来把删除同步到其他设备。 */
@Entity(tableName = "saved_song_tombstones", primaryKeys = ["key", "bucket"])
data class SavedSongTombstone(
    val key: String,
    val bucket: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

/** 下载记录状态。进行中的记录只活在当前进程里，进程重启后由 ViewModel 清理。 */
const val DOWNLOAD_ACTIVE = "下载中"
const val DOWNLOAD_FAILED = "下载失败"
const val DOWNLOAD_DONE = "已完成"

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val key: String,
    val payload: String,
    val path: String,
    val state: String,
    val updatedAt: Long,
    /** 失败原因，只对 [DOWNLOAD_FAILED] 有意义。 */
    val error: String = "",
)

/** 切歌过渡的离线分析结果：节拍网格、响度、调性、首尾和段落，按曲目 key 存。 */
@Entity(tableName = "automix_analysis")
data class AutomixAnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "song_key") val songKey: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val bpm: Double,
    @ColumnInfo(name = "beat_period_s") val beatPeriodS: Double,
    @ColumnInfo(name = "beat_t0_s") val beatT0S: Double,
    @ColumnInfo(name = "beat_k0") val beatK0: Long,
    @ColumnInfo(name = "beat_confidence") val beatConfidence: Float,
    val downbeat: Int,
    @ColumnInfo(name = "downbeat_clear") val downbeatClear: Boolean,
    @ColumnInfo(name = "key_index") val keyIndex: Int,
    @ColumnInfo(name = "key_minor") val keyMinor: Boolean,
    @ColumnInfo(name = "key_confidence") val keyConfidence: Float,
    @ColumnInfo(name = "loudness_db") val loudnessDb: Float,
    @ColumnInfo(name = "intro_ms") val introMs: Long,
    @ColumnInfo(name = "outro_ms") val outroMs: Long,
    /** `起点毫秒:能量,起点毫秒:能量`，能量 0..1；空表示没分段。 */
    val sections: String,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val dirty: Boolean = true,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** 绑定的平台歌单：provider + 平台歌单 id。非空表示这个歌单跟随平台更新。 */
    @ColumnInfo(name = "link_provider", defaultValue = "") val linkProvider: String = "",
    @ColumnInfo(name = "link_id", defaultValue = "") val linkId: String = "",
    /** 平台歌单封面；绑定时封面拼贴优先用它。 */
    @ColumnInfo(name = "link_cover", defaultValue = "") val linkCover: String = "",
    /** 上次成功从平台刷新的时间，用于节流。 */
    @ColumnInfo(name = "link_synced_at", defaultValue = "0") val linkSyncedAt: Long = 0L,
) {
    val bound get() = linkProvider.isNotBlank() && linkId.isNotBlank()
}

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlist_id"], name = "idx_entries_playlist")],
)
data class PlaylistEntryEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "song_key") val songKey: String,
    val payload: String,
)

@Entity(tableName = "sync_accounts")
data class SyncAccountEntity(
    @PrimaryKey val provider: String,
    @ColumnInfo(name = "remote_id") val remoteId: String = "",
    @ColumnInfo(name = "file_name") val fileName: String = "mymusic-library.json",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0L,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String = "",
    @ColumnInfo(name = "auto_sync") val autoSync: Boolean = true,
    @ColumnInfo(name = "wifi_only") val wifiOnly: Boolean = false,
    @ColumnInfo(name = "remote_url") val remoteUrl: String = "",
    @ColumnInfo(name = "account_label") val accountLabel: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

@Entity(tableName = "sync_base")
data class SyncBaseEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    val snapshot: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "local_snapshot") val localSnapshot: String,
    @ColumnInfo(name = "remote_snapshot") val remoteSnapshot: String,
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
)

@Entity(tableName = "canonical_tracks")
data class CanonicalTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: String,
    val album: String,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    val version: String,
    val cover: String,
    val confidence: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "canonical_sources",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["canonical_id"], name = "idx_sources_canonical"),
        Index(value = ["provider_id"], name = "idx_sources_provider"),
    ],
)
data class CanonicalSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    val payload: String,
    @ColumnInfo(name = "match_score")
    val matchScore: Float,
    @ColumnInfo(name = "match_method")
    val matchMethod: String,
    val availability: String,
    @ColumnInfo(name = "is_preview")
    val isPreview: Boolean,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "match_cache", primaryKeys = ["source_key", "target_provider"])
data class MatchCacheEntity(
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "target_provider")
    val targetProvider: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    val score: Float,
    val payload: String,
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)

@Entity(tableName = "track_overrides")
data class TrackOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    val action: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface MusicDao {
    @Query("SELECT * FROM saved_songs WHERE bucket = :bucket ORDER BY updatedAt DESC")
    fun songs(bucket: String): Flow<List<SavedSong>>
    @Query("SELECT * FROM saved_songs WHERE bucket = :bucket ORDER BY updatedAt DESC")
    suspend fun songsOnce(bucket: String): List<SavedSong>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(song: SavedSong)
    @Query("DELETE FROM saved_songs WHERE key = :key AND bucket = :bucket") suspend fun remove(bucket: String, key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveTombstone(tombstone: SavedSongTombstone)
    @Query("DELETE FROM saved_song_tombstones WHERE key = :key AND bucket = :bucket")
    suspend fun clearTombstone(bucket: String, key: String)
    @Query("SELECT * FROM saved_song_tombstones WHERE bucket = :bucket")
    suspend fun tombstones(bucket: String): List<SavedSongTombstone>
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun downloads(): Flow<List<DownloadRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDownload(record: DownloadRecord)
    @Query("DELETE FROM downloads WHERE key = :key") suspend fun deleteDownload(key: String)
    @Query("SELECT * FROM automix_analysis WHERE song_key = :key LIMIT 1")
    suspend fun automixAnalysis(key: String): AutomixAnalysisEntity?
    @Upsert suspend fun saveAutomixAnalysis(entry: AutomixAnalysisEntity)
    @Query("DELETE FROM automix_analysis WHERE updated_at < :cutoff")
    suspend fun pruneAutomixAnalysis(cutoff: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun playlistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists")
    suspend fun allPlaylistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlistOnce(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    fun entries(id: String): Flow<List<PlaylistEntryEntity>>
    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :id ORDER BY position")
    suspend fun entriesOnce(id: String): List<PlaylistEntryEntity>
    @Query("SELECT * FROM playlist_entries ORDER BY playlist_id, position")
    suspend fun allEntriesOnce(): List<PlaylistEntryEntity>
    @Query("SELECT playlist_id, COUNT(*) AS total FROM playlist_entries GROUP BY playlist_id")
    fun entryCounts(): Flow<List<PlaylistEntryCount>>
    @Query("SELECT COUNT(*) FROM playlist_entries")
    suspend fun entryCount(): Int
    /** 哪些（没删的）歌单里有这几个 key 之一：播放页「加到歌单」按钮和面板的勾选态。 */
    @Query(
        "SELECT DISTINCT e.playlist_id FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id " +
            "WHERE p.deleted_at IS NULL AND e.song_key IN (:keys)"
    )
    fun playlistsWithSongs(keys: List<String>): Flow<List<String>>
    // 必须 upsert 不能 INSERT OR REPLACE：REPLACE 先删旧行再插入，playlist_entries 的
    // ON DELETE CASCADE 会把整份曲目连带删掉（刷新绑定歌单、解除绑定都会把歌曲清空）
    @Upsert suspend fun putPlaylist(playlist: PlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntries(entries: List<PlaylistEntryEntity>)
    @Query("DELETE FROM playlist_entries WHERE playlist_id = :id") suspend fun clearEntries(id: String)
    @Query("UPDATE playlists SET name = :name, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, time: Long)
    @Query("UPDATE playlists SET dirty = 1, updated_at = :time WHERE id = :id")
    suspend fun markDirty(id: String, time: Long)
    @Query("UPDATE playlists SET dirty = :dirty, updated_at = :time WHERE id = :id")
    suspend fun markClean(id: String, time: Long, dirty: Boolean = false)
    @Query("UPDATE playlists SET deleted_at = :time, updated_at = :time, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlists WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun pruneDeleted(cutoff: Long)
    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replaceEntries(id: String, entries: List<PlaylistEntryEntity>) {
        clearEntries(id)
        if (entries.isNotEmpty()) putEntries(entries)
    }
}

data class PlaylistEntryCount(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val total: Int,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_accounts ORDER BY updated_at DESC")
    fun accounts(): Flow<List<SyncAccountEntity>>
    @Query("SELECT * FROM sync_accounts LIMIT 1")
    suspend fun accountOnce(): SyncAccountEntity?
    @Query("SELECT * FROM sync_accounts WHERE provider = :provider")
    suspend fun account(provider: String): SyncAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(account: SyncAccountEntity)
    @Query("DELETE FROM sync_accounts") suspend fun clearAccounts()
    @Query("SELECT * FROM sync_base WHERE account_id = :id")
    suspend fun base(id: String): SyncBaseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBase(base: SyncBaseEntity)
    @Query("DELETE FROM sync_base") suspend fun clearBases()
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    fun conflicts(): Flow<List<SyncConflictEntity>>
    @Query("SELECT * FROM sync_conflicts ORDER BY detected_at DESC")
    suspend fun conflictsOnce(): List<SyncConflictEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(conflict: SyncConflictEntity)
    @Query("DELETE FROM sync_conflicts WHERE playlist_id = :id") suspend fun deleteConflict(id: String)
    @Query("DELETE FROM sync_conflicts") suspend fun clearConflicts()
}

@Dao
interface AggregateDao {
    // 同 putPlaylist：REPLACE 会级联删掉这个曲目下的 canonical_sources
    @Upsert suspend fun saveTrack(track: CanonicalTrackEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSources(sources: List<CanonicalSourceEntity>)
    @Query("SELECT * FROM canonical_sources WHERE canonical_id = :canonicalId ORDER BY provider_id")
    suspend fun sources(canonicalId: String): List<CanonicalSourceEntity>
    @Query("SELECT * FROM canonical_sources WHERE source_key = :sourceKey LIMIT 1")
    suspend fun source(sourceKey: String): CanonicalSourceEntity?
    @Query("SELECT * FROM match_cache WHERE source_key = :sourceKey AND target_provider = :targetProvider LIMIT 1")
    suspend fun match(sourceKey: String, targetProvider: String): MatchCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMatch(entry: MatchCacheEntity)
    @Query("DELETE FROM match_cache") suspend fun clearMatches()
    @Query("DELETE FROM match_cache WHERE checked_at < :cutoff") suspend fun pruneMatches(cutoff: Long)
    @Query("DELETE FROM canonical_tracks WHERE updated_at < :cutoff") suspend fun pruneTracks(cutoff: Long)
    @Query("SELECT * FROM track_overrides") suspend fun overrides(): List<TrackOverrideEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveOverride(entry: TrackOverrideEntity)
    @Query("DELETE FROM track_overrides") suspend fun clearOverrides()
}

@Database(
    entities = [
        SavedSong::class,
        SavedSongTombstone::class,
        DownloadRecord::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SyncAccountEntity::class,
        SyncBaseEntity::class,
        SyncConflictEntity::class,
        CanonicalTrackEntity::class,
        CanonicalSourceEntity::class,
        MatchCacheEntity::class,
        TrackOverrideEntity::class,
        AutomixAnalysisEntity::class,
        LocalTrackEntity::class,
        LocalRefEntity::class,
        LocalRemoteEntity::class,
        LocalUploadEntity::class,
        SubsonicTrackEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
    abstract fun aggregate(): AggregateDao
    abstract fun localTracks(): LocalTrackDao
    abstract fun localRefs(): LocalRefDao
    abstract fun localUploads(): LocalUploadDao
    abstract fun subsonicTracks(): SubsonicTrackDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_tracks` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artists` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_sec` INTEGER NOT NULL,
                        `version` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `canonical_sources` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `match_score` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `availability` TEXT NOT NULL,
                        `is_preview` INTEGER NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`),
                        FOREIGN KEY(`canonical_id`) REFERENCES `canonical_tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.exec("CREATE INDEX IF NOT EXISTS `idx_sources_canonical` ON `canonical_sources` (`canonical_id`)")
                connection.exec("CREATE INDEX IF NOT EXISTS `idx_sources_provider` ON `canonical_sources` (`provider_id`)")
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `match_cache` (
                        `source_key` TEXT NOT NULL,
                        `target_provider` TEXT NOT NULL,
                        `target_key` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `payload` TEXT NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`, `target_provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `track_overrides` (
                        `source_key` TEXT NOT NULL,
                        `canonical_id` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`source_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `dirty` INTEGER NOT NULL,
                        `deleted_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_entries` (
                        `playlist_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `song_key` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`playlist_id`, `position`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                connection.exec("CREATE INDEX IF NOT EXISTS `idx_entries_playlist` ON `playlist_entries` (`playlist_id`)")
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_accounts` (
                        `provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `auto_sync` INTEGER NOT NULL,
                        `wifi_only` INTEGER NOT NULL,
                        `remote_url` TEXT NOT NULL,
                        `account_label` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`provider`)
                    )
                    """.trimIndent()
                )
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_base` (
                        `account_id` TEXT NOT NULL,
                        `snapshot` TEXT NOT NULL,
                        `remote_revision` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent()
                )
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                        `playlist_id` TEXT NOT NULL,
                        `local_snapshot` TEXT NOT NULL,
                        `remote_snapshot` TEXT NOT NULL,
                        `base_snapshot` TEXT NOT NULL,
                        `detected_at` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`)
                    )
                    """.trimIndent()
                )

                val json = Json { ignoreUnknownKeys = true }
                val now = System.currentTimeMillis()
                // KMP 驱动没有 Cursor：prepare() 出 statement，step() 推进一行，getText(index) 取列
                connection.prepare("SELECT id, name, songs FROM local_playlists ORDER BY id").use { sel ->
                    while (sel.step()) {
                        val name = sel.getText(1).ifBlank { "未命名歌单" }
                        val songs = runCatching {
                            json.decodeFromString<List<Song>>(if (sel.isNull(2)) "[]" else sel.getText(2))
                        }.getOrDefault(emptyList())
                        val id = UUID.randomUUID().toString()
                        connection.prepare(
                            "INSERT INTO playlists (id, name, created_at, updated_at, dirty, deleted_at) VALUES (?, ?, ?, ?, 1, NULL)",
                        ).use { ins ->
                            ins.bindText(1, id)
                            ins.bindText(2, name)
                            ins.bindLong(3, now)
                            ins.bindLong(4, now)
                            ins.step()
                        }
                        songs.forEachIndexed { index, song ->
                            connection.prepare(
                                "INSERT INTO playlist_entries (playlist_id, position, song_key, payload) VALUES (?, ?, ?, ?)",
                            ).use { ins ->
                                ins.bindText(1, id)
                                ins.bindLong(2, index.toLong())
                                ins.bindText(3, song.key)
                                ins.bindText(4, json.encodeToString(song))
                                ins.step()
                            }
                        }
                    }
                }
            }
        }

        /** saved_songs 原来只用 key 做主键，写最近播放会把同一首歌的“喜欢”记录替换掉。 */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_songs_new` (
                        `key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`, `bucket`)
                    )
                    """.trimIndent()
                )
                connection.exec("INSERT OR REPLACE INTO `saved_songs_new` (`key`, `bucket`, `payload`, `updatedAt`) SELECT `key`, `bucket`, `payload`, `updatedAt` FROM `saved_songs`")
                connection.exec("DROP TABLE `saved_songs`")
                connection.exec("ALTER TABLE `saved_songs_new` RENAME TO `saved_songs`")
            }
        }

        /** 收藏同步需要墓碑表来传播取消收藏。 */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_song_tombstones` (
                        `key` TEXT NOT NULL,
                        `bucket` TEXT NOT NULL,
                        `deleted_at` INTEGER NOT NULL,
                        PRIMARY KEY(`key`, `bucket`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 平台歌单绑定：link_* 四列记录绑定的平台歌单和刷新时间。 */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec("ALTER TABLE `playlists` ADD COLUMN `link_provider` TEXT NOT NULL DEFAULT ''")
                connection.exec("ALTER TABLE `playlists` ADD COLUMN `link_id` TEXT NOT NULL DEFAULT ''")
                connection.exec("ALTER TABLE `playlists` ADD COLUMN `link_cover` TEXT NOT NULL DEFAULT ''")
                connection.exec("ALTER TABLE `playlists` ADD COLUMN `link_synced_at` INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `automix_analysis` (
                        `song_key` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `bpm` REAL NOT NULL,
                        `beat_period_s` REAL NOT NULL,
                        `beat_t0_s` REAL NOT NULL,
                        `beat_k0` INTEGER NOT NULL,
                        `beat_confidence` REAL NOT NULL,
                        `downbeat` INTEGER NOT NULL,
                        `downbeat_clear` INTEGER NOT NULL,
                        `key_index` INTEGER NOT NULL,
                        `key_minor` INTEGER NOT NULL,
                        `key_confidence` REAL NOT NULL,
                        `loudness_db` REAL NOT NULL,
                        `intro_ms` INTEGER NOT NULL,
                        `outro_ms` INTEGER NOT NULL,
                        `sections` TEXT NOT NULL,
                        PRIMARY KEY(`song_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 下载失败要把原因留在记录里，下载页才能提示为什么失败。 */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec("ALTER TABLE `downloads` ADD COLUMN `error` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 本地音乐表。列全是 NOT NULL、不带 SQL 默认值：Room 打开库时按实体校验表结构，
         * 实体没写默认值这里也不能写，否则校验对不上。云端（remote_*）几列先建好，上传落地时不必再迁移。
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `local_tracks` (
                        `local_id` TEXT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `data_path` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_sec` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `modified_at` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `sample_rate` INTEGER NOT NULL,
                        `bits_per_sample` INTEGER NOT NULL,
                        `tags_read` INTEGER NOT NULL,
                        `cover_path` TEXT NOT NULL,
                        `lrc_uri` TEXT NOT NULL,
                        `missing` INTEGER NOT NULL,
                        `match_provider` TEXT NOT NULL,
                        `match_id` TEXT NOT NULL,
                        `match_confidence` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `matched_at` INTEGER NOT NULL,
                        `match_payload` TEXT NOT NULL,
                        `remote_provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `remote_playable_id` TEXT NOT NULL,
                        `remote_status` TEXT NOT NULL,
                        `remote_updated_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`)
                    )
                    """.trimIndent()
                )
                connection.exec("CREATE INDEX IF NOT EXISTS `idx_local_tracks_uri` ON `local_tracks` (`uri`)")
            }
        }

        /**
         * 本地歌的跨设备线索（同步 v3）：local_refs 记文件名、大小、文件所在设备、匹配；
         * local_remotes 记云端副本，一首歌在几个云端各一行。只建新表，不碰已有数据。
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `local_refs` (
                        `local_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `device` TEXT NOT NULL,
                        `match_provider` TEXT NOT NULL,
                        `match_id` TEXT NOT NULL,
                        `match_confidence` REAL NOT NULL,
                        `match_method` TEXT NOT NULL,
                        `matched_at` INTEGER NOT NULL,
                        `match_payload` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`)
                    )
                    """.trimIndent()
                )
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `local_remotes` (
                        `local_id` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `remote_id` TEXT NOT NULL,
                        `playable_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `uploaded_at` TEXT NOT NULL,
                        `ext` TEXT NOT NULL,
                        `md5` TEXT NOT NULL,
                        `cloud_song_id` TEXT NOT NULL,
                        `check_state` TEXT NOT NULL,
                        `checked_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`, `provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 本地歌的上传队列（cloud_uploads）：进程被杀、重启后按它把还没传完的放回来。
         * 只记身份、位置、状态和失败原因，不放音频；只建新表。
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_uploads` (
                        `local_id` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `error_kind` TEXT NOT NULL,
                        `error_message` TEXT NOT NULL,
                        `error_code` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        PRIMARY KEY(`local_id`, `provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    """
                    CREATE TABLE IF NOT EXISTS `subsonic_tracks` (
                        `account_id` TEXT NOT NULL,
                        `song_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `album_id` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `suffix` TEXT NOT NULL,
                        `cover_art` TEXT NOT NULL,
                        `extra_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`, `song_id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
    }
}

/**
 * 迁移里执行一条 DDL。
 *
 * Room KMP 的 [Migration.migrate] 只拿到 [SQLiteConnection]，没有 Android 的 `execSQL`；
 * 用 `prepare(...).use { step() }` 逐条跑，SQL 文本保持原样，一行都没改。
 */
internal fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

/**
 * 纯 JVM / 桌面的打开方式：库文件路径由调用方给（桌面走 `PlatformPaths.dataDir`，
 * 落点 `%APPDATA%\myMusic\mymusic.db`），驱动用自带的 SQLite，不依赖系统库。
 *
 * Android 侧不走这里——那边保留 `Room.databaseBuilder(context, …)` 与系统 SQLite，
 * 见 `app/src/main/java/com/mytv/mymusic/data/AppDatabaseAndroid.kt`。
 */
fun openAppDatabase(dbFile: File): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .build()

