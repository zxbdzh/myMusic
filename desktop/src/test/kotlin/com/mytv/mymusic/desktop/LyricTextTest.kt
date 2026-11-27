package com.mytv.mymusic.desktop

import com.mytv.mymusic.desktop.state.lyric.LyricsParser
import com.mytv.mymusic.desktop.ui.components.cleanLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词显示前的清洗（t16 播放侧验收的自动化证据）。
 *
 * 背景：网易云返回的 yrc 是逐字歌词，一行的原文长这样：
 * `<00:30.560>曾</00:30.780><00:30.780>经</00:30.780>…`。
 * `LyricsParser` 只剥 `[mm:ss]` 方括号标签，尖括号标签会整串留在正文里，
 * 播放页与全屏歌词就会直接露出 LRC 原文（2026-11-27 截图复现）。
 * 解析器根因交给 state 层 owner 修；显示层用 [cleanLyricLine] 兜底，本测试锁住兜底行为。
 */
class LyricTextTest {

    @Test
    fun `逐字时间标签被剥掉只留正文`() {
        val raw = "<00:30.560>曾</00:30.780><00:30.780>经</00:30.780><00:31.210>的</00:31.550>"
        assertEquals("曾经的", cleanLyricLine(raw))
    }

    @Test
    fun `已经干净的歌词原样返回`() {
        assertEquals("晴天", cleanLyricLine("晴天"))
        // 空白行清洗后为空串，界面自己回落到 ♪（见 LyricsPage/LyricsPeek）
        assertEquals("", cleanLyricLine("   "))
    }

    @Test
    fun `方括号时间轴解析后仍带尖括号时清洗生效`() {
        // 常见混合格式：[00:30.56]<00:30.560>晴天</00:30.560>
        val doc = LyricsParser.parse("[00:30.56]<00:30.560>晴天</00:30.560>")
        assertEquals(1, doc.lines.size)
        assertEquals(30_560L, doc.lines.first().startMs)
        assertEquals("晴天", cleanLyricLine(doc.lines.first().text))
    }

    @Test
    fun `清洗不误伤正常的尖括号文本`() {
        // 歌词正文里偶尔有 <3 这类写法；按标签剥会把它们吃掉——这是已知取舍，
        // 宁可少显示半个字符，也不能让整行 LRC 标签铺满屏幕。
        val text = cleanLyricLine("I <3 you")
        assertTrue("清洗后不应包含时间标签", !text.contains("00:"))
    }
}
