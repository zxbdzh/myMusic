package com.mytv.mymusic.data.share

/** 分享任务所处阶段。[label] 是界面用词，与「我的分享」的状态词一致。 */
enum class SharePublishPhase(val label: String) {
    /** 还没发布。 */
    Idle("未发布"),

    /** 正在上传音频副本。 */
    Uploading("上传中"),

    /** 服务端在处理（裁剪、转码）。 */
    Processing("准备中"),

    /** 可收听，链接可用。 */
    Ready("可收听"),

    /** 失败，可重试。 */
    Failed("失败"),

    /** 已撤销。 */
    Revoked("已撤销"),

    /** 已过期。 */
    Expired("已过期"),
}

/** 一次分享任务的 UI 状态。 */
data class SharePublishState(
    val phase: SharePublishPhase = SharePublishPhase.Idle,
    val idempotencyKey: String = "",
    val progress: Float = 0f,
    val url: String = "",
    val error: String = "",
    val attempts: Int = 0,
) {
    /** 能发起一次发布：还没有分享，或者已经失败/撤销/过期需要重来。 */
    val canPublish: Boolean
        get() = phase == SharePublishPhase.Idle || phase == SharePublishPhase.Failed ||
            phase == SharePublishPhase.Revoked || phase == SharePublishPhase.Expired

    /** 只有就绪的链接能撤销；发布中和已失效的状态不该给这个动作。 */
    val canRevoke: Boolean get() = phase == SharePublishPhase.Ready
}

internal sealed interface SharePublishEvent {
    /** 用户点「生成听歌链接」。 */
    data object Start : SharePublishEvent

    /** 上传进度，0f..1f。 */
    data class Progress(val fraction: Float) : SharePublishEvent

    /** 音频已经交给服务端（202），后续进度只由服务端给。 */
    data object ServerAccepted : SharePublishEvent

    data class Ready(val url: String) : SharePublishEvent

    data class Failed(val reason: String) : SharePublishEvent

    /** 失败后重试：沿用同一个幂等键，避免超时后建出第二份分享。 */
    data object Retry : SharePublishEvent

    data object Revoke : SharePublishEvent

    data object Expire : SharePublishEvent

    /** 进入分享页时服务端已有同一份内容的分享：直接可收听，不需要再传。 */
    data class Restored(val url: String) : SharePublishEvent
}

/**
 * 分享任务的状态机。事件驱动、无副作用，WorkManager 那一层只负责把真实进度和结果翻译成事件。
 *
 * 几条来自设计的硬规则：
 * - 发布中（上传/处理）不响应新的发布请求，连点不会建出第二份分享；
 * - 重试沿用同一个幂等键；撤销后重新发布才换新键（那是另一份分享）；
 * - 撤销/过期会清空链接——已经失效的地址不能再显示给用户复制。
 */
internal fun SharePublishState.reduce(
    event: SharePublishEvent,
    newKey: () -> String = ::newShareIdempotencyKey,
): SharePublishState = when (event) {
    SharePublishEvent.Start -> when (phase) {
        SharePublishPhase.Idle, SharePublishPhase.Revoked, SharePublishPhase.Expired -> copy(
            phase = SharePublishPhase.Uploading,
            idempotencyKey = newKey(),
            progress = 0f,
            url = "",
            error = "",
            attempts = attempts + 1,
        )
        else -> this
    }

    is SharePublishEvent.Progress ->
        if (phase == SharePublishPhase.Uploading) copy(progress = event.fraction.coerceIn(0f, 1f)) else this

    SharePublishEvent.ServerAccepted ->
        if (phase == SharePublishPhase.Uploading) copy(phase = SharePublishPhase.Processing, progress = 1f) else this

    is SharePublishEvent.Ready ->
        if (phase == SharePublishPhase.Uploading || phase == SharePublishPhase.Processing) {
            copy(phase = SharePublishPhase.Ready, progress = 1f, url = event.url, error = "")
        } else {
            this
        }

    is SharePublishEvent.Failed ->
        if (phase == SharePublishPhase.Uploading || phase == SharePublishPhase.Processing) {
            copy(phase = SharePublishPhase.Failed, progress = 0f, error = event.reason)
        } else {
            this
        }

    SharePublishEvent.Retry ->
        if (phase == SharePublishPhase.Failed) {
            copy(phase = SharePublishPhase.Uploading, progress = 0f, error = "", attempts = attempts + 1)
        } else {
            this
        }

    SharePublishEvent.Revoke ->
        if (phase == SharePublishPhase.Ready) copy(phase = SharePublishPhase.Revoked, url = "") else this

    SharePublishEvent.Expire ->
        if (phase == SharePublishPhase.Ready) copy(phase = SharePublishPhase.Expired, url = "") else this

    is SharePublishEvent.Restored ->
        if (phase == SharePublishPhase.Idle) {
            copy(phase = SharePublishPhase.Ready, progress = 1f, url = event.url)
        } else {
            this
        }
}
