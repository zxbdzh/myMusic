package com.mytv.mymusic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志环形缓冲。只存内存，不落盘——目的是让用户能把现场导出来给我们看，
 * 尤其是播放器里那些只有真机才复现的问题（DRM 授权、取流降级之类）。
 *
 * 用法：`AppLog.d("TAG", "消息")`，导出走 `设置 › 关于 › 导出日志`。
 * 别往里放 Cookie、token 这类凭证，[redact] 会把常见的几种抹掉，但新的敏感字段要自己注意。
 *
 * 这里住在 :core，所以不能出现 android.util.Log：平台日志由 [platformLogger] 注入，
 * Android 侧在 `AppLogAndroid.install()` 里接上 logcat，桌面侧接标准错误。
 */
object AppLog {
    /** 这些 tag 同时写到平台日志，方便 `adb logcat -s` 直接盯自定义音源的整条链路。 */
    private val PLATFORM_TAGS = setOf("LxSources", "SourceResolver", "Player")

    /**
     * 平台日志出口。参数是（级别 D/I/W/E，tag，已脱敏的消息）。
     * 为空时只进环形缓冲——单元测试就是这种状态。
     */
    @Volatile
    var platformLogger: ((level: String, tag: String, message: String) -> Unit)? = null

    private const val MAX_ENTRIES = 800
    private const val MAX_MESSAGE = 2_000

    data class Entry(val at: Long, val level: String, val tag: String, val message: String) {
        fun format(): String =
            "${timeFormat.format(Date(at))} $level/$tag: $message"
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** 打开导出页时填一次就够：设备、系统、应用版本，省得每回问。 */
    @Volatile
    var header: String = ""

    fun d(tag: String, message: String) = add("D", tag, message)
    fun i(tag: String, message: String) = add("I", tag, message)
    fun w(tag: String, message: String) = add("W", tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        add("E", tag, if (error == null) message else "$message — ${error.javaClass.simpleName}: ${error.message}")

    private fun add(level: String, tag: String, raw: String) {
        val message = redact(raw.take(MAX_MESSAGE))
        // 自定义音源排查常常没法连 adb：同时打一份到平台日志（单元测试里没有平台出口，走空）
        if (tag in PLATFORM_TAGS) runCatching { platformLogger?.invoke(level, "AppLog/$tag", message) }
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** 给诊断信息标"最早一条是什么时候"用。 */
    fun timeOf(entry: Entry): String = timeFormat.format(Date(entry.at))

    fun clear() = synchronized(lock) { entries.clear() }

    /** 拼成可直接分享的纯文本，带头部设备信息。 */
    fun exportText(): String = buildString {
        if (header.isNotBlank()) {
            append(header).append('\n')
            append("-".repeat(header.length.coerceAtMost(80))).append('\n')
        }
        snapshot().forEach { append(it.format()).append('\n') }
    }

    /**
     * 凭证类字段一律抹掉。Cookie / token 是用户粘进来的，日志里出现就等于泄露，
     * 而导出是要发到仓库 issue 里的，所以宁可少信息也不能带出去。
     */
    private val REDACT_MEDIA_TOKEN = Regex("""(media-user-token\s*[=:]\s*)([^;\s"',]+)""", RegexOption.IGNORE_CASE)
    private val REDACT_BEARER = Regex("""("Authorization"\s*:\s*"Bearer\s+)([^"]+)(")""")
    private val REDACT_JWT = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
    private val REDACT_SUBSONIC_QUERY = Regex("""([?&](?:p|t|s|apiKey|jwt)=)([^&\s"']+)""", RegexOption.IGNORE_CASE)

    fun redact(text: String): String = text
        .replace(REDACT_MEDIA_TOKEN, "$1<redacted>")
        .replace(REDACT_BEARER, "$1<redacted>$3")
        .replace(REDACT_JWT, "<redacted-jwt>")
        .replace(REDACT_SUBSONIC_QUERY, "$1<redacted>")
}
