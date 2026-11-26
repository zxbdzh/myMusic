package com.mytv.mymusic.playback.core

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// 停在离结尾不到这么多的位置就算放完了
private const val END_SLACK_MS = 1_000L

/**
 * 队列来自哪张歌单：`kind` 为 collection（平台详情）或 local（自建歌单），`key` 是对应页面 key / 歌单 id。
 * 和 Android 侧同名同字段，续播文件两边通用。
 */
@Serializable
data class PlaybackOrigin(
    val kind: String = "",
    val key: String = "",
    val title: String = "",
)

/** 落盘的播放队列。在线地址会过期，存盘前清掉，续播时再解析；本地文件（file:）原样保留。 */
@Serializable
data class SavedQueue(
    val songs: List<Song> = emptyList(),
    val savedAt: Long = 0L,
    /** 队列来自哪张歌单；空表示搜索、单首播放或旧版本快照。 */
    val origin: PlaybackOrigin? = null,
)

/**
 * 落盘的播放位置：第几首、听到哪儿、那首的封面形状，以及随机 / 循环。
 * 和队列分开存，播放中每隔一阵写一次也只有几十个字节。
 */
@Serializable
data class SavedCursor(
    val key: String = "",
    val index: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shape: String = "",
    val savedAt: Long = 0L,
    // 用 String 存，旧文件的缺省值由 kotlinx 补齐（false / Off），不给枚举加序列化依赖
    val shuffle: Boolean = false,
    val repeat: String = RepeatMode.Off.name,
) {
    val repeatMode: RepeatMode
        get() = RepeatMode.entries.firstOrNull { it.name == repeat } ?: RepeatMode.Off
}

/** 一次能续播的快照：队列和位置对得上才算数。 */
data class SavedPlayback(
    val songs: List<Song>,
    val cursor: SavedCursor,
    val origin: PlaybackOrigin? = null,
) {
    val index: Int get() = cursor.index.coerceIn(0, songs.lastIndex.coerceAtLeast(0))

    val current: Song? get() = songs.getOrNull(index)
}

/**
 * 最后一次的播放队列和位置，存在 `dir` 下（桌面侧是 `%APPDATA%/myMusic/playback`，
 * Android 侧是 `filesDir/playback`，文件格式完全一致，可以直接搬）。
 *
 * 进程被杀后播放页的"继续播放"、系统媒体控件的继续播放都从这里接上：
 * Android 侧是 `MediaSession.Callback.onPlaybackResumption`，桌面侧是启动时读一次。
 */
class QueueStore(
    private val dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val queueFile = File(dir, "queue.json")
    private val cursorFile = File(dir, "cursor.json")

    // 位置每 10 秒写一次、切歌和暂停也写：两次写同一个临时文件不能交错
    private val lock = Mutex()

    /** 主线程也能调：只看文件在不在，给媒体按键 / 托盘菜单判断要不要显示"继续播放"。 */
    fun hasSaved(): Boolean = queueFile.isFile && cursorFile.isFile

    suspend fun load(): SavedPlayback? = withContext(Dispatchers.IO) {
        val queue = read<SavedQueue>(queueFile) ?: return@withContext null
        val cursor = read<SavedCursor>(cursorFile) ?: SavedCursor()
        if (queue.songs.isEmpty()) return@withContext null
        // 队列和位置分两次写，中间被杀可能对不上：按歌找回序号，找不到就从头放
        val index = when {
            queue.songs.getOrNull(cursor.index)?.key == cursor.key -> cursor.index
            else -> queue.songs.indexOfFirst { it.key == cursor.key }
        }
        val fixed = if (index >= 0) cursor.copy(index = index) else SavedCursor(key = queue.songs.first().key)
        // 上次整个队列放完停在最后一首的末尾：从这首开头接上，和播放器放完后再按播放一样
        val ended = fixed.durationMs > 0 && fixed.positionMs >= fixed.durationMs - END_SLACK_MS
        SavedPlayback(queue.songs, if (ended) fixed.copy(positionMs = 0L) else fixed, queue.origin)
    }

    suspend fun saveQueue(songs: List<Song>, origin: PlaybackOrigin? = null) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) {
            clear()
            return@withContext
        }
        val text = json.encodeToString(SavedQueue(songs.map(::forDisk), System.currentTimeMillis(), origin))
        lock.withLock { write(queueFile, text) }
    }

    suspend fun saveCursor(cursor: SavedCursor) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(cursor.copy(savedAt = System.currentTimeMillis()))
        lock.withLock { write(cursorFile, text) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            queueFile.delete()
            cursorFile.delete()
        }
    }

    private inline fun <reified T> read(file: File): T? =
        runCatching { if (file.isFile) json.decodeFromString<T>(file.readText()) else null }.getOrNull()

    /** 先写临时文件再改名，写到一半被杀也不会留下半截 JSON。 */
    private fun write(file: File, text: String) {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    /** 在线地址会过期：只有本地文件原样保留，其它一律清空。 */
    private fun forDisk(song: Song): Song = if (song.url.startsWith("file:")) song else song.copy(url = "")
}
