package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.PlayerTheme
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.PcSeekBar
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.cleanLyricLine
import com.mytv.mymusic.desktop.ui.components.coverSeedColor
import kotlin.math.max

/** 歌词正文 22sp、当前行 26sp；行距 1.6（PC 上大字号必须给足行距，否则中文挤成一团）。 */
private val LyricSize = 22.sp
private val LyricActiveSize = 26.sp
private const val LyricLineHeightFactor = 1.6f

/** 歌词区最宽 900dp，两侧各留 20%——长行在宽窗口里不会横跨整屏。 */
private val LyricMaxWidth = 900.dp

/**
 * 全屏歌词（PC 版）。
 *
 * 与 Android 版的差别：
 *  - 字号按 PC 阅读距离放大（22sp / 当前行 26sp），行距 1.6；
 *  - **点击任意一行直接跳播**（鼠标点哪句唱哪句，PC 上比"滚到某句"更直接）；
 *  - 滚轮由 LazyColumn 原生支持，自动跟随只在"用户没在手动滚动"时生效：
 *    拖动滚动条 / 滚轮后 2.5 秒内不抢回位置，否则一边看历史歌词一边被拽回来很难受；
 *  - 两侧 20% 留白 + 最宽 900dp；
 *  - 底部走带常驻，随时能暂停 / 切歌。
 */
@Composable
fun LyricsPage(state: MusicUiState, store: MusicStore) {
    val track = state.current
    val seed = remember(track?.id) { coverSeedColor(track?.id ?: "mymusic") }
    PlayerTheme(seed) {
        // 两层底色：不透明打底 + 主色光晕，避免底下的主界面透过半透明渐变把文字糊掉
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoundIconButton(AppIcon.Back, "返回播放页", store::closeLyrics, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "歌词",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track?.artist.orEmpty(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    RoundIconButton(AppIcon.Grid, "海报墙", store::openPosterWall, size = 36.dp)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val lyrics = state.lyrics
                    if (track == null || lyrics == null || lyrics.lines.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AppIconView(AppIcon.Music, MaterialTheme.colorScheme.outline, size = 36.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (state.lyricsLoading) "歌词加载中…" else "暂无歌词",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LyricBody(state = state, lyrics = lyrics, onSeek = store::seekTo)
                    }
                }

                // 底部走带常驻：36dp 的小走带 + 进度，和播放页同一档密度
                LyricsTransport(state = state, store = store)
            }
        }
    }
}

/** 歌词主体：居中列 + 最宽 900dp + 两侧 20%。 */
@Composable
private fun LyricBody(
    state: MusicUiState,
    lyrics: com.mytv.mymusic.desktop.state.LyricsDoc,
    onSeek: (Long) -> Unit,
) {
    val currentIndex = lyrics.indexAt(state.positionMs)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, currentIndex - 2))
    // 用户手动滚动后暂时不跟随：2.5 秒内不再自动抢回位置
    val userScrolling = listState.isScrollInProgress
    LaunchedEffect(currentIndex, userScrolling) {
        if (currentIndex >= 0 && !userScrolling) {
            listState.animateScrollToItem(max(0, currentIndex - 2))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePadding: Dp = maxWidth * 0.2f
        val contentWidth = minOf(maxWidth - sidePadding * 2, LyricMaxWidth).coerceAtLeast(320.dp)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val active = index == currentIndex
                    val nextStart = lyrics.lines.getOrNull(index + 1)?.startMs ?: (line.startMs + 6_000L)
                    val fraction = if (active && nextStart > line.startMs) {
                        ((state.positionMs - line.startMs).toFloat() / (nextStart - line.startMs)).coerceIn(0f, 1f)
                    } else if (active) {
                        1f
                    } else {
                        0f
                    }
                    LyricLine(
                        text = cleanLyricLine(line.text),
                        active = active,
                        fraction = fraction,
                        // 点哪句就从哪句唱；行距与字号按 PC 阅读距离放大
                        onSeek = { onSeek(line.startMs) },
                    )
                }
            }
        }
    }
}

/**
 * 一行歌词。
 *
 * [onSeek] 由外部注入（点击即跳播到这一句）——这里不直接拿 store，保证这一层是可预览的纯 UI。
 */
@Composable
private fun LyricLine(
    text: String,
    active: Boolean,
    fraction: Float,
    onSeek: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primary
            hovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        },
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "lyricColor",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text.ifBlank { "♪" },
            fontSize = if (active) LyricActiveSize else LyricSize,
            lineHeight = (if (active) LyricActiveSize else LyricSize) * LyricLineHeightFactor,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .hoverable(interaction)
                .clickable { onSeek() }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (active) {
            Spacer(Modifier.height(6.dp))
            // 这一句唱到哪：底下一条细线，按句内时间填充
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** 底部走带：进度 + 时间 + 上一首/播放/下一首。 */
@Composable
private fun LyricsTransport(state: MusicUiState, store: MusicStore) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(formatTime(state.positionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PcSeekBar(
            progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeekFraction = { fraction -> store.seekTo((state.durationMs * fraction).toLong()) },
            bubble = { fraction -> formatTime((state.durationMs * fraction).toLong()) },
            modifier = Modifier.weight(1f),
            barHeight = 5.dp,
        )
        Text(formatTime(state.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 32.dp, tint = MaterialTheme.colorScheme.onSurface)
        PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 36.dp)
        RoundIconButton(AppIcon.Next, "下一首", store::next, size = 32.dp, tint = MaterialTheme.colorScheme.onSurface)
    }
}
