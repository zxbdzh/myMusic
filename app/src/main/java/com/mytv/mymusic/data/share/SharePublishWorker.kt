package com.mytv.mymusic.data.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mytv.mymusic.AppLog
import com.mytv.mymusic.BackgroundWork
import com.mytv.mymusic.MyMusicApp
import com.mytv.mymusic.R
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * 一次发布要交给后台的全部输入。音频已经准备好（临时文件），这里只描述"传什么、传到哪"。
 *
 * 令牌不放进来：它按 origin 从本机加密存储里取，任务输入不会落进 WorkManager 的数据库。
 */
@Serializable
internal data class SharePublishRequest(
    val serverUrl: String,
    val idempotencyKey: String,
    val metadata: ShareMetadata,
    val audioPath: String,
    val audioType: String,
    val coverPath: String? = null,
    val coverType: String? = null,
)

/**
 * 上传音频并让服务端处理的后台任务。
 *
 * 准备音频（要登录态与解析器）在应用进程里做完了，这里只负责把文件传上去；
 * 因此即使用户离开分享页、甚至杀掉界面，上传也能继续。临时文件在结束时删掉。
 */
internal class SharePublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = inputData.getString(KEY_REQUEST)
            ?.let { runCatching { shareJson.decodeFromString<SharePublishRequest>(it) }.getOrNull() }
            ?: return Result.failure()
        val origin = normalizeShareServerUrl(request.serverUrl) ?: return Result.failure()
        val token = (applicationContext as? MyMusicApp)?.secretStore
            ?.secret(shareTokenName(origin))
            .orEmpty()
        if (token.isBlank()) {
            AppLog.w(TAG, "还没有保存分享服务令牌，放弃发布")
            return Result.failure()
        }

        val audio = File(request.audioPath)
        val cover = request.coverPath?.let(::File)?.takeIf { it.isFile }
        return try {
            show("正在上传「${request.metadata.title}」")
            val client = ShareServiceClient(origin, token)
            val result = client.create(
                idempotencyKey = request.idempotencyKey,
                metadata = request.metadata,
                audio = audio,
                audioType = request.audioType,
                cover = cover?.readBytes(),
                coverType = request.coverType,
            )
            Result.success(
                workDataOf(
                    KEY_SHARE_ID to result.id,
                    KEY_STATUS to result.status,
                    KEY_URL to result.url,
                ),
            )
        } catch (error: ShareServiceException) {
            AppLog.w(TAG, "发布分享失败：${error.message}")
            // 网络抖动与服务端忙重试有意义；4xx 重试也只是再被拒一次
            when (error.kind) {
                ShareServiceErrorKind.Network, ShareServiceErrorKind.TooManyRequests -> Result.retry()
                else -> Result.failure(workDataOf(KEY_ERROR to (error.code.ifBlank { error.message })))
            }
        } catch (error: Exception) {
            AppLog.w(TAG, "发布分享出错：${error.message}")
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        } finally {
            // 临时工作文件只服务这一次发布
            audio.delete()
            cover?.delete()
        }
    }

    /** 前台通知：Android 12 起后台起前台服务可能被拒，这时按普通后台任务继续。 */
    private suspend fun show(text: String) {
        ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mymusic)
            .setContentTitle(applicationContext.getString(R.string.share_publish_channel))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(0, 0, true)
            .build()
        runCatching {
            setForeground(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notification)
                },
            )
        }.onFailure { error ->
            // 后台被拉起、系统不让起前台服务时，上传照常在普通后台任务里跑完
            AppLog.i(TAG, "前台服务起不来，按普通后台任务继续：${error.javaClass.simpleName}")
        }
    }

    internal companion object {
        private const val TAG = "SharePublishWorker"
        private const val CHANNEL = "share_publish"
        private const val NOTIFICATION_ID = 4123

        const val KEY_REQUEST = "request"
        const val KEY_SHARE_ID = "shareId"
        const val KEY_STATUS = "status"
        const val KEY_URL = "url"
        const val KEY_ERROR = "error"

        /** 每次发布一个唯一的 work 名：同一个幂等键重复入队时保留先到的那个。 */
        fun enqueue(context: Context, request: SharePublishRequest): UUID? {
            val manager = BackgroundWork.get(context) ?: return null
            // 用户主动点的一次发布：不挂网络约束，立刻开始传。
            // 部分连接（PARTIAL_CONNECTIVITY）的网络上 CONNECTED 约束会被判不满足，任务被取消后不再重排，界面就永远停在「上传中」。
            val work = OneTimeWorkRequestBuilder<SharePublishWorker>()
                .setInputData(workDataOf(KEY_REQUEST to shareJson.encodeToString(request)))
                .addTag(TAG)
                .build()
            manager.enqueueUniqueWork("$TAG-${request.idempotencyKey}", ExistingWorkPolicy.KEEP, work)
            // 返回任务 id：界面按 id 监听最终状态，比按名字查更直接
            return work.id
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.share_publish_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.share_publish_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}

