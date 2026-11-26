package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.PlayerTheme
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.WavyProgressBar
import com.mytv.mymusic.desktop.ui.components.coverSeedColor
import kotlin.math.max

/**
 * 全屏歌词（Android 上是播放页里"歌词视图"那一层，桌面上做成独立整窗）。
 *
 * 三件事必须做对：
 *  1. **当前行跟进度走**：`LyricsDoc.indexAt` 按时间轴二分（这里是顺序扫描，行数小，够用）；
 *  2. **自动居中**：第一帧用 `rememberLazyListState(initialFirstVisibleItemIndex=…)` 直接定到位，
 *     之后靠 LaunchedEffect 跟随（截图与实播两种场景都正确）；
 *  3. **当前行的一行内进度**：Android 是逐字染色，桌面这里用当前行下方的一根细线表示
 *     "这一句唱到哪了"——同样的信息量，不需要逐字时间轴。
 */
@Composable
fun LyricsPage(state: MusicUiState, store: MusicStore) {
    val track = state.current
    val seed = remember(track?.id) { coverSeedColor(track?.id ?: "mymusic") }
    PlayerTheme(seed) {
        // 两层：不透明底色打底，再叠一层带 alpha 的主色光晕。
        // 只画 radialGradient(alpha→surface) 会让第一段半透明，底下的主界面透上来——文字直接糊掉。
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton(AppIcon.Back, "返回播放页", store::closeLyrics, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "歌词",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track?.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RoundIconButton(AppIcon.Grid, "海报墙", store::openPosterWall, size = 40.dp)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val lyrics = state.lyrics
                    if (track == null || lyrics == null || lyrics.lines.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AppIconView(AppIcon.Music, MaterialTheme.colorScheme.outline, size = 44.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = if (state.lyricsLoading) "歌词加载中…" else "暂无歌词",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val currentIndex = lyrics.indexAt(state.positionMs)
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, currentIndex - 2))
                        LaunchedEffect(currentIndex) {
                            if (currentIndex >= 0) listState.animateScrollToItem(max(0, currentIndex - 2))
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 60.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                val active = index == currentIndex
                                val nextStart = lyrics.lines.getOrNull(index + 1)?.startMs ?: (line.startMs + 6_000L)
                                val fraction = if (active && nextStart > line.startMs) {
                                    ((state.positionMs - line.startMs).toFloat() / (nextStart - line.startMs)).coerceIn(0f, 1f)
                                } else if (active) 1f else 0f
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = line.text.ifBlank { "♪" },
                                        style = if (active) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        },
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (active) {
                                        Spacer(Modifier.height(10.dp))
                                        // 这一句的进度：底下一条细线，按句内时间填充
                                        Box(
                                            Modifier
                                                .fillMaxWidth(0.42f)
                                                .height(3.dp)
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
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WavyProgressBar(
                        progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                        playing = state.playing,
                        onSeekFraction = { fraction -> store.seekTo((fraction * state.durationMs).toLong()) },
                        height = 26.dp,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(state.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                            PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 52.dp)
                            RoundIconButton(AppIcon.Next, "下一首", store::next, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatTime(state.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
