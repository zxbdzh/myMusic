package com.mytv.mymusic.desktop.state.lyric

import com.mytv.mymusic.desktop.state.LyricLine
import com.mytv.mymusic.desktop.state.LyricsDoc

/**
 * LRC 解析（纯函数，无 IO）。
 *
 * 从 Android 的 `ui/LyricsParser.kt` 抽出来只保留桌面上用得着的部分：时间轴、`[offset:]`、
 * 一行多时间戳（副歌重复段常用 `[00:12.00][01:20.00]同一句`）。翻译行、逐字时间轴、
 * 逐字注音这些 P2 再做，别在骨架期把 900 行的解析器照搬过来。
 *
 * 之所以放在 `state.lyric` 而不是 UI 包：它是状态层的一部分（当前行由进度算出来），
 * 之后要搬进 :core 时整个文件一起走，不用拆界面代码。
 */
object LyricsParser {

    /** `[mm:ss]` / `[mm:ss.SS]` / `[mm:ss.SSS]`，宽容地接受 1~3 位小数。 */
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 元数据标签：`[ti:]`、`[ar:]`、`[al:]`、`[by:]`、`[length:]` 这些不参与滚动。 */
    private val metaTag = Regex("""^\[[a-zA-Z#]+:.*]$""")

    private val offsetTag = Regex("""\[offset:\s*(-?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): LyricsDoc {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return LyricsDoc(emptyList(), plain = true)

        // 全局偏移：有些 LRC 会写 [offset:-200] 表示整体提前 200ms
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()

        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // 元数据行整行跳过
            if (metaTag.matches(trimmed) && timeTag.find(trimmed) == null) return@forEach

            val tags = timeTag.findAll(trimmed).toList()
            val content = timeTag.replace(trimmed, "").trim()
            if (tags.isEmpty()) {
                plain += trimmed
            } else {
                tags.forEach { match ->
                    timed += LyricLine(startMs = match.toMillis() + offset, text = content)
                }
            }
        }

        if (timed.isEmpty()) {
            // 纯文本歌词：只做等距轮播，indexAt 会返回 -1，界面自己按行数走
            return LyricsDoc(plain = true, lines = plain.mapIndexed { index, line -> LyricLine(index * 4000L, line) })
        }
        return LyricsDoc(lines = timed.sortedBy { it.startMs }, plain = false, offsetMs = offset)
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fractionRaw = groupValues.getOrNull(3).orEmpty()
        // 小数位按位数换算：.5 = 500ms，.50 = 500ms，.500 = 500ms
        val fraction = when (fractionRaw.length) {
            0 -> 0L
            1 -> fractionRaw.toLong() * 100
            2 -> fractionRaw.toLong() * 10
            else -> fractionRaw.take(3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fraction
    }
}
