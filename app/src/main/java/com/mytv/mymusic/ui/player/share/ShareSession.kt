package com.mytv.mymusic.ui.player.share

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.mytv.mymusic.data.ShareCardPalette
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.share.ShareMode

/** 一次分享里现在是哪一页：选句层、分享页、片段编辑页。 */
internal enum class SharePage { Pick, Share, Clip }

/**
 * 一次分享：长按那一刻拍的快照，之后切歌、歌词重新解析、换动画都不影响这次分享（稿子「不影响播放」）。
 *
 * @param link 平台上这首歌的链接（[shareLink]），没有为空串。
 * @param lines 选句列表（[pickLines]，副行已按「歌词下面那一行」换好）。
 * @param seed 打开那一刻的种子色：选句层和分享页按它建 PlayerTheme，切歌后配色（含卡片的「播放页」配色）不跟着漂。
 * @param initial 先选上的那句（[initialPick]）。
 */
@Stable
internal class ShareSession(
    val song: Song,
    val link: String,
    val lines: List<PickLine>,
    val synced: Boolean,
    val seed: Color,
    val initial: Int,
) {
    var page by mutableStateOf(SharePage.Pick)

    /** 选中的句子（[PickLine.order]）。 */
    var selected by mutableStateOf(setOf(initial))

    /** 乱排卡的种子，「重新打乱」+1；只这一次分享。 */
    var messySeed by mutableLongStateOf(0L)

    /** 自选背景图（叠卡、封面卡），只这一次分享用，不保存。 */
    var picture by mutableStateOf<Uri?>(null)

    /** 换到粗糙卡片样式前切成自定义配色前的配色（离开粗糙卡时换回）；用户在粗糙卡上手动点了配色就清掉。 */
    var paletteBeforeBrat by mutableStateOf<ShareCardPalette?>(null)

    /** 在线听歌：整曲还是片段。片段范围在片段编辑页里改。 */
    var onlineMode by mutableStateOf(ShareMode.Full)

    /** 片段范围（毫秒）。首次进编辑页或换选句时由 [shareClipRange] 重算。 */
    var clipStartMs by mutableLongStateOf(0L)
    var clipEndMs by mutableLongStateOf(0L)

    /** 范围是否已经按选句算过：没算过时进编辑页先算一次。 */
    var clipInitialized by mutableStateOf(false)

    /** 有没有副行（翻译 / 罗马音）：没有就不显示「译文」开关。 */
    val hasSubline: Boolean = lines.any { it.sub.isNotBlank() }
}
