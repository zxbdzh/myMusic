package com.mytv.mymusic

import com.mytv.mymusic.data.share.SharePublishEvent
import com.mytv.mymusic.data.share.SharePublishPhase
import com.mytv.mymusic.data.share.SharePublishState
import com.mytv.mymusic.data.share.newShareIdempotencyKey
import com.mytv.mymusic.data.share.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享任务的状态机（docs/online-sharing/DESIGN.md 的「发布与幂等」「失败与重试」「分享过期与撤销」）：
 * 发布中不重复建分享、重试沿用同一个幂等键、撤销/过期后不能再操作、撤销后重新发布会换新键。
 */
class SharePublishStateTest {

    private val idle = SharePublishState()

    private fun SharePublishState.start(key: String = "key-1") =
        reduce(SharePublishEvent.Start) { key }

    @Test
    fun startMovesToUploadingWithAFreshKey() {
        val state = idle.start()
        assertEquals(SharePublishPhase.Uploading, state.phase)
        assertEquals(0f, state.progress, 0f)
        assertEquals("key-1", state.idempotencyKey)
        assertEquals(1, state.attempts)
    }

    @Test
    fun startIsIgnoredWhilePublishing() {
        // 连点两次不能建出两个分享：键和阶段都不能变
        val uploading = idle.start()
        val again = uploading.reduce(SharePublishEvent.Start) { "key-2" }
        assertEquals("key-1", again.idempotencyKey)
        assertEquals(SharePublishPhase.Uploading, again.phase)

        val processing = uploading.reduce(SharePublishEvent.ServerAccepted)
        assertEquals("key-1", processing.reduce(SharePublishEvent.Start) { "key-3" }.idempotencyKey)
    }

    @Test
    fun progressIsClampedAndOnlyDuringUploading() {
        val uploading = idle.start()
        assertEquals(0.42f, uploading.reduce(SharePublishEvent.Progress(0.42f)).progress, 0.0001f)
        assertEquals(1f, uploading.reduce(SharePublishEvent.Progress(1.7f)).progress, 0.0001f)
        assertEquals(0f, uploading.reduce(SharePublishEvent.Progress(-0.3f)).progress, 0.0001f)

        // 服务端接管后进度不再由客户端上报
        val processing = uploading.reduce(SharePublishEvent.ServerAccepted)
        assertEquals(1f, processing.reduce(SharePublishEvent.Progress(0.1f)).progress, 0.0001f)
    }

    @Test
    fun serverAcceptanceMovesToProcessing() {
        val state = idle.start()
            .reduce(SharePublishEvent.Progress(0.8f))
            .reduce(SharePublishEvent.ServerAccepted)
        assertEquals(SharePublishPhase.Processing, state.phase)
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun readyCarriesTheLink() {
        val state = idle.start()
            .reduce(SharePublishEvent.ServerAccepted)
            .reduce(SharePublishEvent.Ready("https://share.example/s/token"))
        assertEquals(SharePublishPhase.Ready, state.phase)
        assertEquals("https://share.example/s/token", state.url)
        assertTrue(state.canRevoke)
        assertFalse(state.canPublish)
    }

    @Test
    fun retryFromTheFailureScreenReusesTheSameKey() {
        // 分享页的「重试」走的是新的发布请求：失败态必须能重新开始，而且沿用同一个幂等键
        val failed = idle.start("key-1")
            .reduce(SharePublishEvent.Failed("网络中断"))
        val again = failed.start("key-2")
        assertEquals(SharePublishPhase.Uploading, again.phase)
        assertEquals("key-1", again.idempotencyKey)
        assertEquals(2, again.attempts)
        assertEquals("", again.error)
    }

    @Test
    fun failureKeepsTheKeySoRetryDoesNotDuplicate() {
        val failed = idle.start()
            .reduce(SharePublishEvent.Progress(0.6f))
            .reduce(SharePublishEvent.Failed("网络中断"))
        assertEquals(SharePublishPhase.Failed, failed.phase)
        assertEquals("网络中断", failed.error)
        assertEquals("key-1", failed.idempotencyKey)

        val retried = failed.reduce(SharePublishEvent.Retry)
        assertEquals(SharePublishPhase.Uploading, retried.phase)
        assertEquals("key-1", retried.idempotencyKey)
        assertEquals(2, retried.attempts)
        assertEquals(0f, retried.progress, 0f)
        assertEquals("", retried.error)
    }

    @Test
    fun revokeClearsTheLinkAndBlocksFurtherRevoke() {
        val revoked = idle.start()
            .reduce(SharePublishEvent.ServerAccepted)
            .reduce(SharePublishEvent.Ready("https://share.example/s/token"))
            .reduce(SharePublishEvent.Revoke)
        assertEquals(SharePublishPhase.Revoked, revoked.phase)
        assertEquals("", revoked.url)
        assertFalse(revoked.canRevoke)
        // 撤销后还能重新生成一份分享（Start 会换新的幂等键）
        assertTrue(revoked.canPublish)

        // 再撤一次不改变状态，也不报错
        assertEquals(revoked, revoked.reduce(SharePublishEvent.Revoke))
    }

    @Test
    fun expireClearsTheLink() {
        val expired = idle.start()
            .reduce(SharePublishEvent.ServerAccepted)
            .reduce(SharePublishEvent.Ready("https://share.example/s/token"))
            .reduce(SharePublishEvent.Expire)
        assertEquals(SharePublishPhase.Expired, expired.phase)
        assertEquals("", expired.url)
        assertFalse(expired.canRevoke)
    }

    @Test
    fun startAfterRevokeCreatesANewShare() {
        // 撤销后重新发布是另一份分享，必须换新的幂等键
        val revoked = idle.start("key-1")
            .reduce(SharePublishEvent.ServerAccepted)
            .reduce(SharePublishEvent.Ready("https://share.example/s/old"))
            .reduce(SharePublishEvent.Revoke)
        val republished = revoked.reduce(SharePublishEvent.Start) { "key-2" }
        assertEquals(SharePublishPhase.Uploading, republished.phase)
        assertEquals("key-2", republished.idempotencyKey)
        assertEquals("", republished.url)
    }

    @Test
    fun restoredReuseSkipsUploading() {
        // 进入分享页时服务端已有同一份内容：直接可收听，不需要再传
        val restored = idle.reduce(SharePublishEvent.Restored("https://share.example/s/existing"))
        assertEquals(SharePublishPhase.Ready, restored.phase)
        assertEquals("https://share.example/s/existing", restored.url)
        assertEquals(0, restored.attempts)
    }

    @Test
    fun restoredIsIgnoredOncePublishingStarted() {
        val uploading = idle.start()
        assertEquals(uploading, uploading.reduce(SharePublishEvent.Restored("https://share.example/s/other")))
    }

    @Test
    fun generatedKeyMatchesTheServiceContract() {
        // 服务端要求 ^[\w-]{8,128}$
        val key = newShareIdempotencyKey()
        assertTrue(key, Regex("^[\\w-]{8,128}$").matches(key))
        assertTrue(key != newShareIdempotencyKey())
    }
}

