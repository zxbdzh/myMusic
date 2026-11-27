package com.mytv.mymusic.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// MirrorKind / MirrorRoute / TIER_* / RemoteMirror(List) 这些纯数据形状已搬到
// :core 的 data/MirrorRoutes.kt（SettingsStore 在 :core 引用它们）；
// 本文件只保留带网络与 Context 的合并 / 测速 / 刷新逻辑（object GithubMirrors）。
object GithubMirrors {
    /**
     * 最新镜像清单。gitee 国内直连，本身就是给国内用户准备的。
     */
    const val REMOTE_LIST_URL = "https://gitee.com/mytv-android/my-mirror/raw/master/mirrors.json"
    private const val PREFS_NAME = "mymusic_mirrors"
    private const val SP_LIST = "mirror_list"
    private const val SP_LIST_AT = "mirror_list_at"
    private const val LIST_TTL_MS = 6 * 60 * 60 * 1000L
    private const val LIST_SCHEMA = "mytv.mirror/v1"
    /** 远程清单上限：挡住写飘了的列表把探测成本放大。 */
    private const val MAX_REMOTE_ENTRIES = 40
    private const val USER_AGENT = "myMusic-Android"

    /**
     * 一条镜像线路的地址是否可接受。与 myHotpot / myBV 同一套标准：
     * 只接受 https 且 443、不带用户信息/查询/片段，主机不能是本机或内网。
     *
     * 这里**没有**「主机必须在白名单里」这道闸门——远程清单要能加新镜像，
     * 六个应用一视同仁。拦住危险主机的是下面这几条，与主机在不在表里无关。
     */
    private fun isSafeMirrorBase(base: String): Boolean {
        val url = base.trim().toHttpUrlOrNull() ?: return false
        if (url.scheme != "https" || url.port != 443) return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.query != null || url.fragment != null) return false
        return !isLocalMirrorHost(url.host)
    }

    /**
     * 主机是否只能指向本机或内网。按字面判断，不做 DNS 解析——
     * 解析会让结论依赖运行环境，而且这一层只是 URL 预检。
     */
    private fun isLocalMirrorHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") || normalized.endsWith(".lan") ||
            normalized.endsWith(".internal") || normalized.endsWith(".home.arpa")
        ) {
            return true
        }
        // 数字字面量（含 127.1、2130706433、0x7f000001 这类简写）一律按本地处理：
        // 公网镜像不会用 IP 字面量，用简写绕过的只有内网地址。
        return isNumericHost(normalized)
    }

    private fun isNumericHost(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare.isEmpty()) return false
        if (bare.all { it in '0'..'9' || it == '.' }) return true
        if (bare.contains(':')) return true
        if (bare.startsWith("0x", ignoreCase = true)) return true
        return bare.all { it in '0'..'9' } && bare.length in 8..10
    }

    /**
     * 应用内用镜像时的速度门槛：前 [SPEED_GATE_MS] 毫秒拉不到 [SPEED_GATE_MIN_BYTES]
     * 就踢掉换下一站。小于门槛的小文件要求 5 秒内下完，由调用方按总长度取 min。
     */
    const val SPEED_GATE_MS = 5_000L
    const val SPEED_GATE_MIN_BYTES = 1L shl 20

    private val remoteJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val listClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内置线路顺序：tier 升序，同 tier 保持声明顺序。 */
    private val builtInRoutes: List<MirrorRoute> =
        GithubMirror.entries.filter { it != GithubMirror.Auto }.sortedBy { it.tier }.map { it.toRoute() }

    /**
     * 内置清单与远程清单合并后的线路顺序。远程拉不到就用内置——
     * 远程只是让新镜像不用发版本就能生效，不是必需项。
     */
    fun routes(context: Context): List<MirrorRoute> {
        val cached = runCatching { prefs(context).getString(SP_LIST, "").orEmpty() }
            .getOrNull().orEmpty()
        return mergeRoutes(builtInRoutes, parseRemote(cached).orEmpty())
    }

    /**
     * 候选线路：用户钉住的排最前，其余按铁律顺序兜底。
     * [forApi] 时剔掉不转发 `api.github.com` 的线路，[target] 不适用的线路也不生成。
     */
    fun candidates(
        context: Context,
        target: String,
        selected: GithubMirror,
        forApi: Boolean = false,
    ): List<MirrorRoute> {
        val applicable = routes(context)
            .filter { !forApi || it.proxiesApi }
            .filter { it.urlFor(target) != null }
        if (selected == GithubMirror.Auto) return applicable
        val pinned = applicable.firstOrNull { it.id == selected.id } ?: return applicable
        return listOf(pinned) + applicable.filterNot { it == pinned }
    }

    /** 拉取 gitee 上的最新清单并写进缓存；成功返回 true，任何失败都只当没这回事。 */
    suspend fun refreshList(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = listClient.newCall(
                Request.Builder().url(REMOTE_LIST_URL).header("User-Agent", USER_AGENT).build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.string().orEmpty()
            }
            if (parseRemote(body) == null) return@withContext false
            prefs(context).edit()
                .putString(SP_LIST, body)
                .putLong(SP_LIST_AT, System.currentTimeMillis())
                .apply()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** 缓存没过期就不拉；关键路径上可以无条件调它。 */
    suspend fun refreshListIfStale(context: Context): Boolean {
        val cachedAt = runCatching { prefs(context).getLong(SP_LIST_AT, 0L) }.getOrDefault(0L)
        if (System.currentTimeMillis() - cachedAt < LIST_TTL_MS) return false
        return refreshList(context)
    }

    private fun parseRemote(body: String): List<MirrorRoute>? {
        if (body.isBlank()) return null
        val parsed = runCatching { remoteJson.decodeFromString<RemoteMirrorList>(body) }
            .getOrNull() ?: return null
        if (parsed.schema != LIST_SCHEMA) return null
            return parsed.mirrors.take(MAX_REMOTE_ENTRIES).mapNotNull { entry ->
                if (entry.id.isBlank() || entry.base.isBlank()) return@mapNotNull null
                val kind = when (entry.kind.lowercase()) {
                    "raw_host" -> MirrorKind.RAW_HOST
                    else -> MirrorKind.PREFIX
                }
                val prefix = when (kind) {
                    MirrorKind.PREFIX -> entry.base.trim().trimEnd('/') + "/"
                    // raw_host 只存主机名
                    MirrorKind.RAW_HOST -> entry.base.trim().trimEnd('/')
                }
                // raw_host 补成完整 URL 才能过校验；过不了的就地丢掉——
                // 远程清单写飘了不该把请求指向本机或内网。
                val checkUrl = if (kind == MirrorKind.RAW_HOST) "https://$prefix/" else prefix
                if (!isSafeMirrorBase(checkUrl)) return@mapNotNull null
                MirrorRoute(
                    id = entry.id,
                    label = entry.label.ifBlank { entry.id },
                    prefix = prefix,
                    kind = kind,
                    proxiesApi = entry.api,
                    tier = entry.tier.coerceIn(TIER_FAST, TIER_RAW_ONLY),
                    enabled = entry.enabled,
                )
            }
    }

    /**
     * 远程覆盖内置：同 id 整体替换（改地址、改档位、下线都行），新 id 追加。
     * 档位夹在 0..3，直连永远保留——远程清单再坏也换不掉「国内直连」这条兜底。
     */
    private fun mergeRoutes(
        builtIn: List<MirrorRoute>,
        remote: List<MirrorRoute>,
    ): List<MirrorRoute> {
        if (remote.isEmpty()) return builtIn
        val merged = LinkedHashMap<String, MirrorRoute>()
        builtIn.forEach { merged[it.id] = it }
        remote.forEach { entry ->
            val clamped = entry.copy(
                tier = entry.tier.coerceIn(TIER_FAST, TIER_RAW_ONLY),
                label = entry.label.ifBlank { entry.id },
            )
            merged[entry.id] = if (entry.id == GithubMirror.Direct.id) {
                clamped.copy(tier = TIER_DIRECT, enabled = true)
            } else {
                clamped
            }
        }
        return merged.values.filter { it.enabled }.sortedBy { it.tier }
    }
}
