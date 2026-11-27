package com.mytv.mymusic.desktop.state

import com.mytv.mymusic.data.local.LocalTrackEntity
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * PC 本地音乐目录扫描器（替代 Android MediaStore，写入 `:core` Room `local_tracks` 表）。
 *
 * 规则：
 * - 递归扫描给定目录下所有常见音频格式（mp3 / flac / wav / m4a / ogg / aac / opus / ape / alac）；
 * - 以「文件大小 + 首尾各 64KB」的 SHA-256 生成 `localId`（与 Android `LocalIdentity` 同思路），
 *   搬家改目录不丢失唯一标识；
 * - 文件名自动拆分 `歌手 - 歌名`（无分隔符时整体当标题，父目录名作专辑兜底）；
 * - 自动绑定同目录下同名 `.lrc` 歌词文件。
 */
object LocalMusicScanner {

    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "mp3", "flac", "wav", "m4a", "ogg", "aac", "opus", "ape", "alac", "wma",
    )

    private const val SAMPLE_BYTES = 64 * 1024

    fun scanFolder(root: File, now: Long = System.currentTimeMillis()): List<LocalTrackEntity> {
        if (!root.isDirectory) return emptyList()
        val origin = root.absolutePath
        return root.walkTopDown()
            .filter { file -> file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .mapNotNull { file -> runCatching { toEntity(file, root, origin, now) }.getOrNull() }
            .toList()
    }

    internal fun parseTitleArtist(baseName: String): Pair<String, String> {
        val cleaned = baseName.trim()
        val dash = cleaned.indexOf(" - ")
        if (dash > 0 && dash + 3 < cleaned.length) {
            val artist = cleaned.substring(0, dash).trim()
            val title = cleaned.substring(dash + 3).trim()
            if (title.isNotEmpty() && artist.isNotEmpty()) return title to artist
        }
        return cleaned.ifBlank { "未知曲目" } to ""
    }

    internal fun computeLocalId(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = file.length()
        digest.update(length.toString().toByteArray(Charsets.UTF_8))
        RandomAccessFile(file, "r").use { raf ->
            val headLen = minOf(length, SAMPLE_BYTES.toLong()).toInt()
            if (headLen > 0) {
                val head = ByteArray(headLen)
                raf.seek(0L)
                raf.readFully(head)
                digest.update(head)
            }
            if (length > SAMPLE_BYTES) {
                val tailLen = minOf(length - SAMPLE_BYTES, SAMPLE_BYTES.toLong()).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(length - tailLen)
                raf.readFully(tail)
                digest.update(tail)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    private fun toEntity(file: File, root: File, origin: String, now: Long): LocalTrackEntity {
        val (title, artist) = parseTitleArtist(file.nameWithoutExtension)
        val relParent = file.parentFile?.relativeToOrNull(root)?.invariantSeparatorsPath.orEmpty()
        val album = file.parentFile?.name?.takeIf { it != root.name && it.isNotBlank() }.orEmpty()
        val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "alac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }
        return LocalTrackEntity(
            localId = computeLocalId(file),
            uri = file.toURI().toString(),
            origin = origin,
            displayName = file.name,
            relativePath = relParent,
            dataPath = file.absolutePath,
            title = title,
            artist = artist,
            album = album,
            durationSec = 0,
            size = file.length(),
            mimeType = mime,
            modifiedAt = file.lastModified(),
            addedAt = now,
            bitrate = 0,
            sampleRate = 0,
            bitsPerSample = 0,
            tagsRead = false,
            coverPath = "",
            lrcUri = if (lrcFile.isFile) lrcFile.absolutePath else "",
            missing = false,
            matchProvider = "",
            matchId = "",
            matchConfidence = 0f,
            matchMethod = "",
            matchedAt = 0L,
            matchPayload = "",
            remoteProvider = "",
            remoteId = "",
            remotePlayableId = "",
            remoteStatus = "",
            remoteUpdatedAt = 0L,
            updatedAt = now,
        )
    }
}
