package com.mytv.mymusic.data.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** 音频准备失败的类别：地址被拒要重新取流，其余直接提示。 */
internal enum class SharePrepareFailure {
    /** 401/403/404/410：这条地址已经失效（签名过期、换账号、下架）。 */
    Rejected,

    /** 200 但一个字节都没有。 */
    Empty,

    /** 其它 HTTP 错误或写盘失败。 */
    Io,
}

internal class SharePrepareException(
    val failure: SharePrepareFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 把取流地址落成分享用的临时文件。
 *
 * 只负责"这一条地址能不能拿到完整音频"：地址失效（[SharePrepareFailure.Rejected]）由调用方
 * 重新取流后重试一次，其余错误直接提示。失败一定删掉半截文件，避免把空文件传上去。
 */
internal class ShareAudioPreparer(
    private val directory: File,
    private val http: OkHttpClient,
) {
    suspend fun prepare(
        url: String,
        extension: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = runInterruptible(Dispatchers.IO) {
        directory.mkdirs()
        val target = File.createTempFile("share-", ".${extension.ifBlank { "bin" }}", directory)
        try {
            val request = Request.Builder().url(url).build()
            val response = try {
                http.newCall(request).execute()
            } catch (error: IOException) {
                throw SharePrepareException(SharePrepareFailure.Io, "下载音频失败：${error.message}", error)
            }
            var written = 0L
            response.use { body ->
                if (body.code in REJECTED_CODES) {
                    throw SharePrepareException(SharePrepareFailure.Rejected, "音频地址已失效：HTTP ${body.code}")
                }
                if (!body.isSuccessful) {
                    throw SharePrepareException(SharePrepareFailure.Io, "下载音频失败：HTTP ${body.code}")
                }
                val total = body.body.contentLength().takeIf { it > 0 } ?: -1L
                body.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            // 没有 Content-Length 时进度按已读字节走，界面上仍能看到"在动"
                            onProgress(written, if (total > 0) total else written)
                        }
                    }
                }
            }
            if (written == 0L) throw SharePrepareException(SharePrepareFailure.Empty, "下载到的音频是空的")
            onProgress(written, written)
            target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private companion object {
        /** 地址本身失效：签名过期、被 CDN 拒、换账号、作品下架。 */
        val REJECTED_CODES = setOf(401, 403, 404, 410)
    }
}
