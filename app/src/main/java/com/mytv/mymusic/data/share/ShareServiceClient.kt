package com.mytv.mymusic.data.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 分享服务失败的类型。界面按它决定提示语与能否重试，不直接显示服务端英文错误码。 */
internal enum class ShareServiceErrorKind {
    /** 令牌不对或没填。 */
    Unauthorized,
    Forbidden,
    NotFound,
    /** 409：状态不允许这次操作（例如已经撤销、已经上传过）。 */
    Conflict,
    /** 410：分享已撤销或已过期。 */
    Gone,
    PayloadTooLarge,
    UnsupportedMediaType,
    /** 429：服务端同时在处理的音频太多，稍后重试。 */
    TooManyRequests,
    /** 507：服务端存储配额不够。 */
    InsufficientStorage,
    Http,
    /** 连不上：网络、证书、DNS。 */
    Network,
    /** 200 但内容读不懂。 */
    InvalidResponse,
    /** 本机配置不完整（地址或令牌）。 */
    InvalidConfig;

    companion object {
        fun of(status: Int): ShareServiceErrorKind = when (status) {
            401 -> Unauthorized
            403 -> Forbidden
            404 -> NotFound
            409 -> Conflict
            410 -> Gone
            413 -> PayloadTooLarge
            415 -> UnsupportedMediaType
            429 -> TooManyRequests
            507 -> InsufficientStorage
            else -> Http
        }
    }
}

/** 继承 IOException：能沿着 IO 异常的路径往上走，调用方按 [kind] 分支。 */
internal class ShareServiceException(
    val kind: ShareServiceErrorKind,
    message: String,
    val status: Int = 0,
    val code: String = "",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 分享服务的管理接口客户端（`oh-mygo-share`）。只封装需要令牌的 `/api/shares*`，
 * 公开听歌页由浏览器自己访问，不经过这里。
 *
 * 接口契约见 `docs/online-sharing/DESIGN.md`，改动前先看服务端的 `parseMetadata`。
 */
internal class ShareServiceClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = defaultShareHttpClient(),
) {
    private val origin: String = normalizeShareServerUrl(baseUrl)
        ?: throw ShareServiceException(
            ShareServiceErrorKind.InvalidConfig,
            "分享服务地址必须是 HTTPS 的 origin（本地调试可以用 http://localhost）",
        )

    init {
        if (token.isBlank()) {
            throw ShareServiceException(ShareServiceErrorKind.InvalidConfig, "还没有填写分享服务令牌")
        }
    }

    /**
     * 创建一次分享。服务端先建记录，状态是 `awaiting_upload` 时才由客户端上传封面与音频；
     * 如果这份内容已经发布过（同一快照），服务端会直接返回 `ready` 和原链接，不需要再传媒体。
     */
    suspend fun create(
        idempotencyKey: String,
        metadata: ShareMetadata,
        audio: File,
        audioType: String,
        cover: ByteArray? = null,
        coverType: String? = null,
    ): ShareResult {
        val created = readResponse<ShareResult>(
            execute(
                requestBuilder(url("api", "shares"), "POST", encode(metadata))
                    .header("Idempotency-Key", idempotencyKey)
                    .build()
            )
        )
        when (val state = created.state ?: throw invalidStatus(created.status)) {
            ShareState.Ready, ShareState.Processing, ShareState.Uploading -> return created
            ShareState.Revoked, ShareState.Expired ->
                throw ShareServiceException(ShareServiceErrorKind.Gone, "这个分享${state.label}了，不能再上传", 410, state.wire)
            ShareState.AwaitingUpload, ShareState.Failed -> Unit
        }

        if (cover != null) {
            val type = coverType?.toMediaTypeOrNull()
                ?: throw ShareServiceException(ShareServiceErrorKind.InvalidConfig, "封面的 Content-Type 不合法：$coverType")
            execute(requestBuilder(url("api", "shares", created.id, "cover"), "PUT", cover.toRequestBody(type)).build())
                .use { ensureSuccess(it) }
        }
        val type = audioType.toMediaTypeOrNull()
            ?: throw ShareServiceException(ShareServiceErrorKind.InvalidConfig, "音频的 Content-Type 不合法：$audioType")
        // 202 表示服务端已接管处理，进度从这里开始只在服务端
        return readResponse(
            execute(requestBuilder(url("api", "shares", created.id, "audio"), "PUT", audio.asRequestBody(type)).build())
        )
    }

    suspend fun get(id: String): ShareResult =
        readResponse(execute(requestBuilder(url("api", "shares", id), "GET").build()))

    /** 查同一份快照是否已经发布过。服务端没有这个接口（旧版本）或没有相同快照时返回 null。 */
    suspend fun reuse(metadata: ShareMetadata): ShareResult? {
        val response = execute(requestBuilder(url("api", "shares", "reuse"), "POST", encode(metadata)).build())
        if (response.code == 204 || response.code == 404) {
            response.close()
            return null
        }
        return readResponse(response)
    }

    suspend fun list(): List<ShareListItem> =
        readResponse<ShareListResponse>(execute(requestBuilder(url("api", "shares"), "GET").build())).shares

    suspend fun revoke(id: String) {
        execute(requestBuilder(url("api", "shares", id), "DELETE").build()).use { ensureSuccess(it) }
    }

    private fun url(vararg segments: String): HttpUrl =
        origin.toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun requestBuilder(url: HttpUrl, method: String, body: RequestBody? = null): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            // 服务端按 Origin 做跨站校验，桌面版也是这么发的
            .header("Origin", origin)
            .method(method, body)

    private fun encode(metadata: ShareMetadata): RequestBody =
        shareJson.encodeToString(metadata).toRequestBody(JSON_MEDIA_TYPE)

    private suspend fun execute(request: Request): Response = runInterruptible(Dispatchers.IO) {
        try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw ShareServiceException(
                ShareServiceErrorKind.Network,
                "连不上分享服务：${error.message ?: error.javaClass.simpleName}",
                cause = error,
            )
        }
    }

    private inline fun <reified T> readResponse(response: Response): T = response.use {
        ensureSuccess(it)
        val text = it.body.string()
        if (text.isBlank()) {
            throw ShareServiceException(ShareServiceErrorKind.InvalidResponse, "分享服务返回了空内容（HTTP ${it.code}）", it.code)
        }
        try {
            shareJson.decodeFromString<T>(text)
        } catch (error: Exception) {
            throw ShareServiceException(ShareServiceErrorKind.InvalidResponse, "分享服务返回的内容无法解析", it.code, cause = error)
        }
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val text = runCatching { response.body.string() }.getOrDefault("")
        val code = runCatching { shareJson.decodeFromString<ShareErrorBody>(text).error }.getOrNull().orEmpty()
        throw ShareServiceException(
            kind = ShareServiceErrorKind.of(response.code),
            message = "分享服务返回 ${response.code}" + if (code.isBlank()) "" else "（$code）",
            status = response.code,
            code = code,
        )
    }

    private fun invalidStatus(status: String) =
        ShareServiceException(ShareServiceErrorKind.InvalidResponse, "分享服务返回了不认识的状态：$status")
}

/** 列表接口的外层包装。 */
@Serializable
private data class ShareListResponse(val shares: List<ShareListItem> = emptyList())

/** 失败响应体固定是 `{"error": "..."}`。 */
@Serializable
private data class ShareErrorBody(val error: String = "")

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

private val shareJson = Json {
    ignoreUnknownKeys = true
    // 默认值（含 null）不写进请求体：整曲模式不能携带片段字段，否则服务端判 invalid_metadata
    encodeDefaults = false
    explicitNulls = false
}

/** 大文件上传按整段写完算超时，读、写都给足时间；连接与首字节失败要尽快暴露。 */
internal fun defaultShareHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.MINUTES)
    .writeTimeout(15, TimeUnit.MINUTES)
    .build()
