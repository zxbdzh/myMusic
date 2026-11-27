package com.mytv.mymusic.aggregate

import com.mytv.mymusic.AppLog
import com.mytv.mymusic.data.MusicRepository
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.local.LOCAL_SOURCE
import com.mytv.mymusic.data.lx.LxPlatform
import com.mytv.mymusic.data.native.AudioQuality
import com.mytv.mymusic.data.native.PlayableStream
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.LinkedHashMap

class SourceResolver(
    private val repository: MusicRepository,
    private val cookies: () -> Map<String, String>,
    private val settings: () -> AggregationSettings,
    private val workDir: () -> File,
    /** 播放音质偏好；换源解析和播放取流都要按它取，选了无损就不能偷偷给 128k。 */
    private val quality: () -> AudioQuality = { AudioQuality.Auto },
    private val appleCard: () -> String = { "" },
) {
    private data class CachedUrl(
        val url: String,
        val preview: Boolean,
        val at: Long,
        val ext: String = "",
        val bitrate: Int = 0,
        val extra: Map<String, String> = emptyMap(),
        /** 取这条地址时的音质偏好；偏好一变整条缓存作废。 */
        val quality: String = AudioQuality.Auto.id,
        val generation: Long = 0,
        /** 洛雪实际命中的歌曲可能与列表展示用的锚点歌曲不同。 */
        val song: Song? = null,
    )

    private data class CachedFailure(val at: Long, val generation: Long, val reason: String)

    private val urlCache = object : LinkedHashMap<String, CachedUrl>(32, 0.75f, true) {}
    private val negativeCache = object : LinkedHashMap<String, CachedFailure>(32, 0.75f, true) {}
    private val lock = Any()

    fun buildPolicy(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        manualMethod: PlaybackMethod? = null,
        usePlatformPlayback: Boolean = true,
    ): SourcePolicy {
        val current = settings()
        val available = buildList {
            track.sources.mapTo(this) { it.providerId }
            if (lxCandidates(track).isNotEmpty()) add(LxPlatform.PROVIDER_ID)
        }.distinct()
        val pinned = pinnedProvider
            ?: current.pinnedProvider.takeIf { current.playbackMode == PlaybackMode.Pinned }
        val preferences = if (pinnedProvider == null && usePlatformPlayback) buildMap {
            // 旧卡密曲目升级后仍能播放，用户设置过 Apple Music 时以新设置为准。
            if ("apple" !in current.platformPlayback &&
                track.sources.any { it.providerId == "apple" && it.playbackMethod == PlaybackMethod.AppleCard }
            ) {
                put("apple", PlatformPlaybackPreference(PlaybackMode.Priority, PlaybackMethod.AppleCard))
            }
            putAll(current.platformPlayback.filter { (providerId, preference) ->
                preference.mode != PlaybackMode.Auto && preference.method in playbackMethodsFor(providerId)
            })
        } else emptyMap()
        val preferred = track.playbackPlatform?.takeIf { it in preferences }
        // 自动播放只允许已启用的音乐源；用户显式固定的平台和本地源永远允许
        val allowed = (available.filter { it in current.enabledProviders } + listOfNotNull(pinned, preferred) + LOCAL_SOURCE).toSet()
        val origin = if (track.playbackPlatform == null && current.playbackMode == PlaybackMode.Priority && preferences.isNotEmpty()) {
            null
        } else track.sources.firstOrNull()?.providerId
        return SourcePolicy(
            mode = current.playbackMode,
            priority = current.playbackOrder(available, origin, pinned),
            pinnedProvider = pinned,
            allowPreview = current.allowPreview,
            allowedProviders = allowed,
            // 只有真的定下了某个固定源，独占才有意义；否则保持原来的多源回退。
            pinnedOnly = pinned != null && (manualMethod != null || current.pinnedOnly),
            platformPlayback = preferences,
            preferredPlatform = preferred,
            manualMethod = manualMethod,
        )
    }

    fun orderCandidates(
        track: CanonicalTrack,
        pinnedProvider: String?,
        excludeProviders: Set<String>,
        policy: SourcePolicy,
    ): List<TrackSource> {
        val cookieMap = cookies()
        val pinned = pinnedProvider ?: policy.pinnedProvider
        val allowed = policy.allowedProviders
        val lxAllowed = allowed == null || LxPlatform.PROVIDER_ID in allowed || pinned == LxPlatform.PROVIDER_ID
        val nativeSources = track.sources
            .filterNot { it.providerId in excludeProviders }
            .filter { allowed == null || it.providerId in allowed || it.providerId == pinned }
            .flatMap { source ->
                val preference = policy.platformPlayback[source.providerId]
                val methods = when {
                    source.providerId == pinned && policy.manualMethod != null -> listOf(policy.manualMethod)
                    preference?.mode == PlaybackMode.Priority ->
                        (listOf(preference.method) + playbackMethodsFor(source.providerId)).distinct()
                    preference?.mode == PlaybackMode.Pinned -> listOf(preference.method)
                    else -> listOf(PlaybackMethod.Direct)
                }
                methods.map { method -> source.withPlaybackMethod(method) }
            }
        // 洛雪伪源只有被允许（或用户显式固定）时才合成：有候选不等于能用
        val unified = if (lxAllowed && LxPlatform.PROVIDER_ID !in excludeProviders) {
            val candidates = lxCandidates(track, policy.platformPlayback.keys)
            unifiedLxSource(track, candidates)
        } else null
        val sources = nativeSources + listOfNotNull(unified)
        val statuses = sources.associateWith { source -> playbackAvailability(source, cookieMap[source.providerId].orEmpty()) }
        // 手写策略没给顺序时沿用 sources 顺序；buildPolicy 生成的策略不这样回落，免得把未启用的平台带回来
        val priority = if (policy.priority.isEmpty() && allowed == null) {
            sources.map { it.providerId }.distinct()
        } else {
            policy.priority
        }
        fun priorityIndex(source: TrackSource): Int {
            val index = priority.indexOf(source.providerId)
            return if (index < 0) Int.MAX_VALUE else index
        }

        val pinnedSources = sources.filter { source ->
            source.providerId == pinned &&
                (statuses[source] != Availability.Unavailable || source.providerId in policy.platformPlayback)
        }
        val full = sources.filter { source ->
            val canPlayFull = when (statuses.getValue(source)) {
                Availability.Unavailable, Availability.PreviewOnly -> false
                Availability.VipOnly -> !cookieMap[source.providerId].isNullOrBlank()
                else -> true
            }
            source.providerId != pinned && (canPlayFull || source.providerId in policy.platformPlayback)
        }.sortedWith(
            if (policy.mode == PlaybackMode.Priority) {
                compareBy<TrackSource> { priorityIndex(it) }.thenByDescending { it.matchScore }
            } else {
                compareBy<TrackSource> { priorityIndex(it) }
                    .thenByDescending { it.song.bitrate }
                    .thenByDescending { it.matchScore }
            }
        )
        val preview = sources.filter { source ->
            source.providerId != pinned &&
                statuses.getValue(source) == Availability.PreviewOnly &&
                source.providerId !in policy.platformPlayback &&
                policy.allowPreview
        }.sortedWith(
            compareBy<TrackSource> { priorityIndex(it) }
                .thenByDescending { it.song.bitrate }
        )
        val ordered = (pinnedSources + full + preview).distinctBy { it.playbackKey }
        val preferred = policy.preferredPlatform ?: return ordered
        return ordered.filter { it.providerId == preferred } + ordered.filterNot { it.providerId == preferred }
    }

    private fun TrackSource.withPlaybackMethod(method: PlaybackMethod): TrackSource =
        if (method == PlaybackMethod.Direct) copy(playbackMethod = method, resolutionKey = "") else copy(
            playbackMethod = method,
            availability = Availability.Playable,
            isPreview = false,
            resolutionKey = "${method.name}:${song.key}",
        )

    private fun playbackAvailability(source: TrackSource, cookie: String): Availability =
        if (source.playbackMethod != PlaybackMethod.Direct || source.providerId == LxPlatform.PROVIDER_ID) {
            Availability.Playable
        } else availabilityOf(source, cookie)

    /** 一个洛雪候选代表整个脚本集合；锚点只用于列表展示，实际歌曲由脚本解析结果决定。 */
    private fun unifiedLxSource(track: CanonicalTrack, candidates: List<TrackSource>): TrackSource? {
        val anchor = candidates.firstOrNull() ?: return null
        return anchor.copy(
            providerId = LxPlatform.PROVIDER_ID,
            providerName = LxPlatform.PROVIDER_NAME,
            availability = Availability.Playable,
            isPreview = false,
            // 候选指纹进缓存键：expand 补配出新平台变体后要能重新尝试洛雪，别被旧负缓存挡住
            resolutionKey = "${LxPlatform.PROVIDER_ID}:${track.id}:" +
                candidates.map { it.song.key }.sorted().joinToString(","),
        )
    }

    /** 能交给洛雪脚本处理的平台变体；脚本能不能播与原生源当前的可用性无关，不在这里过滤。 */
    private fun lxCandidates(track: CanonicalTrack, exclude: Set<String> = emptySet()): List<TrackSource> =
        track.sources.asSequence()
            .filter { it.providerId !in exclude && repository.supportsCustomSource(it.providerId) }
            .sortedWith(compareByDescending<TrackSource> { it.matchScore }.thenByDescending { it.song.bitrate })
            .distinctBy { it.song.key }
            .toList()

    suspend fun resolve(
        track: CanonicalTrack,
        pinnedProvider: String? = null,
        excludeProviders: Set<String> = emptySet(),
        policy: SourcePolicy = buildPolicy(track, pinnedProvider),
        excludeMethods: Set<String> = emptySet(),
    ): Resolution {
        val ordered = orderCandidates(track, pinnedProvider, excludeProviders, policy)
        val attempts = mutableListOf<Attempt>()
        val cookieMap = cookies()
        val card = appleCard()
        val explicitProvider = pinnedProvider ?: policy.pinnedProvider
        val customGeneration = repository.customSourceGeneration()
        val customSongs by lazy { lxCandidates(track, policy.platformPlayback.keys).map { it.song } }
        val deferPreview = ordered.any { it.providerId in policy.platformPlayback }
        var firstPreview: Resolution.Ready? = null
        var fixedSource: TrackSource? = null

        for (source in ordered) {
            // 独占固定音源：只试用户指定的那个，其余平台记一笔就跳过。
            // 回退到别的平台常拿到试听或直接中断，比在这里停下更难排查。
            if (fixedSource != null && source.providerId != fixedSource.providerId) break
            if (policy.pinnedOnly && explicitProvider != null && source.providerId != explicitProvider &&
                source.providerId != policy.preferredPlatform
            ) {
                attempts += Attempt(source, "固定音源独占，不尝试其他来源")
                continue
            }
            val fixed = policy.platformPlayback[source.providerId]?.mode == PlaybackMode.Pinned
            if (fixed) fixedSource = source
            if (source.playbackKey in excludeMethods) {
                attempts += Attempt(source, "播放失败")
                continue
            }
            val cookie = cookieMap[source.providerId].orEmpty()
            val status = playbackAvailability(source, cookie)
            val custom = source.providerId == LxPlatform.PROVIDER_ID || source.playbackMethod == PlaybackMethod.Lx
            if (status == Availability.Unavailable) {
                attempts += Attempt(source, "不可用")
                continue
            }
            if (status == Availability.VipOnly && cookie.isBlank() && !custom) {
                attempts += Attempt(source, "需要登录或会员")
                continue
            }
            val explicit = source.providerId == explicitProvider
            val allowPreview = !fixed && (policy.allowPreview || (explicit && !deferPreview))
            if ((status == Availability.PreviewOnly || source.isPreview) && !allowPreview && !custom) {
                attempts += Attempt(source, "仅提供试听")
                continue
            }

            val now = System.currentTimeMillis()
            val cacheKey = source.playbackKey
            val cached = synchronized(lock) { urlCache[cacheKey] }
            // 换过音质偏好就不能复用旧地址：用户选了无损，缓存里那条 128k 必须作废
            val qualityId = quality().id
            if (cached != null && cached.quality == qualityId && cached.generation == customGeneration && now - cached.at < URL_TTL_MS) {
                // 汽水这类取流后才知道是试听的源，缓存里带着试听标记：同样受「允许试听」约束
                if (cached.preview && !allowPreview) {
                    attempts += Attempt(source, "仅提供试听")
                    continue
                }
                val cachedSong = if (custom) cached.song ?: source.song else source.song
                val cachedSource = source.copy(song = cachedSong).withQuality(cached.ext, cached.bitrate, cached.extra)
                val ready = Resolution.Ready(cachedSource, cached.url, cached.preview, cached.at, attempts.toList())
                if (!deferPreview || !ready.isPreview) return ready
                if (firstPreview == null) firstPreview = ready
                attempts += Attempt(source, "仅提供试听")
                continue
            }
            if (cached != null) synchronized(lock) { urlCache.remove(cacheKey) }

            val cachedFailure = synchronized(lock) { negativeCache[cacheKey] }
            // 洛雪脚本失败多半是临时性风控/上游抖动：负缓存短一些，兜底能尽快重试
            val negativeTtl = if (custom) CUSTOM_NEGATIVE_TTL_MS else NEGATIVE_TTL_MS
            if (cachedFailure != null && cachedFailure.generation == customGeneration && now - cachedFailure.at < negativeTtl) {
                attempts += Attempt(source, cachedFailure.reason)
                continue
            }

            var failure: Throwable? = null
            var resolvedSong = source.song
            suspend fun fetch(requestedQuality: AudioQuality): PlayableStream? = runCatching {
                when {
                    custom -> {
                        val songs = if (source.playbackMethod == PlaybackMethod.Lx) listOf(source.song) else customSongs
                        val result = repository.playableLxStream(songs, requestedQuality)
                        resolvedSong = result?.song ?: source.song
                        result?.stream
                    }
                    source.playbackMethod == PlaybackMethod.AppleCard -> repository.playableAppleCardStream(
                        source.song, card, workDir(), requestedQuality,
                    )
                    else -> repository.playableStream(source.song, cookie, workDir(), requestedQuality)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                failure = it
            }.getOrNull()
            var stream = fetch(quality())
            if (stream?.url.isNullOrBlank() && quality() != AudioQuality.Auto) {
                // 用户锁了音质而这个平台给不了（没上架无损、账号没会员）：退回自动档再取一次，
                // 宁可降档也要让这首歌响，不能因为一个音质偏好就整首播不出来。
                stream = fetch(AudioQuality.Auto)
            }
            val url = stream?.url.orEmpty()
            if (url.isBlank()) {
                // 洛雪是「换源点了却回退」的主要出处，失败原因要进应用内日志，用户导日志时能看见
                if (custom) {
                    val reason = repository.customSourceFailure().ifBlank { failure?.message.orEmpty() }
                    AppLog.w("SourceResolver", "洛雪取流失败《${track.title}》：${reason.take(300)}")
                }
                val reason = when (source.playbackMethod) {
                    PlaybackMethod.Lx -> repository.customSourceFailure().ifBlank { "没有可用的洛雪脚本或播放地址" }
                    PlaybackMethod.AppleCard -> if (card.isBlank()) {
                        "请先填写聆澜卡密"
                    } else {
                        "聆澜未返回可播放地址，请检查卡密和网络"
                    }
                    PlaybackMethod.Direct -> failure?.message
                        ?.takeIf { source.providerId == "soda" && it.contains("签名器") }
                        ?.substringBefore("\n")
                        ?: "没有返回播放地址"
                }
                synchronized(lock) {
                    negativeCache[cacheKey] = CachedFailure(System.currentTimeMillis(), customGeneration, reason)
                }
                attempts += Attempt(source, reason)
                continue
            }
            val fromCustom = custom || stream?.extra?.containsKey("lx_source") == true
            val preview = if (fromCustom) stream?.preview == true else {
                source.isPreview || stream?.preview == true || isPreviewSource(source.providerId, source.song)
            }
            val resolvedSource = source.copy(song = resolvedSong).withQuality(
                stream?.ext.orEmpty(),
                stream?.bitrate ?: 0,
                stream?.extra.orEmpty(),
            )
            synchronized(lock) {
                urlCache[cacheKey] = CachedUrl(
                    url = url,
                    preview = preview,
                    at = System.currentTimeMillis(),
                    ext = resolvedSource.song.ext,
                    bitrate = resolvedSource.song.bitrate,
                    extra = stream?.extra.orEmpty(),
                    quality = qualityId,
                    generation = customGeneration,
                    song = resolvedSong,
                )
            }
            // 地址拿到了但只是片段，而设置里不允许试听：当没有可用地址，继续试下一个平台；
            // 缓存里留着这份试听地址，用户打开「允许试听」后可以直接复用
            if (preview && !allowPreview) {
                attempts += Attempt(source, "仅提供试听")
                continue
            }
            if (source.providerId == LxPlatform.PROVIDER_ID) {
                AppLog.d("SourceResolver", "洛雪取流成功《${track.title}》：${stream?.extra?.get("lx_source").orEmpty()}")
            }
            val ready = Resolution.Ready(resolvedSource, url, preview, attempts = attempts.toList())
            if (!deferPreview || !preview) return ready
            if (firstPreview == null) firstPreview = ready
            attempts += Attempt(source, "仅提供试听")
        }

        val deepLink = track.sources.firstOrNull { it.song.link.isNotBlank() }?.song?.link.orEmpty()
        return if (fixedSource == null && firstPreview != null) firstPreview
        else Resolution.None(attempts, deepLink, fixedSource)
    }

    private fun TrackSource.withQuality(
        ext: String,
        bitrate: Int,
        extra: Map<String, String> = emptyMap(),
    ): TrackSource = copy(song = song.withQuality(ext, bitrate, extra))

    private fun Song.withQuality(ext: String, bitrate: Int, extra: Map<String, String> = emptyMap()): Song {
        val resolvedExt = ext.ifBlank { this.ext }
        val resolvedBitrate = bitrate.takeIf { it > 0 } ?: this.bitrate
        // 上次直连留下的授权信息不能被带到卡密或洛雪地址上。
        val resolvedExtra = this.extra.filterKeys { !it.startsWith("lx_") && !it.startsWith("drm_") } + extra
        return if (resolvedExt == this.ext && resolvedBitrate == this.bitrate && resolvedExtra == this.extra) this
        else copy(ext = resolvedExt, bitrate = resolvedBitrate, extra = resolvedExtra)
    }

    fun invalidate(source: TrackSource) {
        val cacheKey = source.playbackKey
        synchronized(lock) {
            urlCache.remove(cacheKey)
            negativeCache.remove(cacheKey)
        }
    }

    fun clearCache() {
        synchronized(lock) {
            urlCache.clear()
            negativeCache.clear()
        }
    }

    private companion object {
        const val URL_TTL_MS = 3 * 60_000L
        const val NEGATIVE_TTL_MS = 10 * 60_000L
        const val CUSTOM_NEGATIVE_TTL_MS = 60_000L
    }
}
