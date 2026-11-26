package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.WavyProgressBar

/**
 * 迷你播放条（对应 Android 平板的 `BottomPlayerBar` + 手机 `MiniPlayer`）。
 *
 * 桌面常驻在内容区下方、导航栏右侧：左封面与曲目、中间走带与进度、右音量与入口。
 * 没在播放时也不隐藏——给一句"挑一首开始"，否则窗口底部会莫名空出一块。
 * 进度用同一套波浪样式，和播放页保持连续感。
 */
@Composable
fun MiniPlayerBar(state: MusicUiState, store: MusicStore) {
    val track = state.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.width(300.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (track != null) {
                CoverArt(
                    seedKey = track.id,
                    label = track.title,
                    modifier = Modifier
                        .size(52.dp)
                        .clickable(onClick = store::openPlayer),
                    corner = 12.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${track.artist} · ${track.providerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "没有正在播放",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "在榜单或搜索里挑一首",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 34.dp)
                Spacer(Modifier.width(6.dp))
                PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 38.dp)
                Spacer(Modifier.width(6.dp))
                RoundIconButton(AppIcon.Next, "下一首", store::next, size = 34.dp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(42.dp),
                )
                WavyProgressBar(
                    progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                    playing = state.playing,
                    onSeekFraction = { fraction -> store.seekTo((fraction * state.durationMs).toLong()) },
                    modifier = Modifier.weight(1f),
                    height = 16.dp,
                )
                Text(
                    text = formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(42.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundIconButton(AppIcon.Volume, "音量", { store.setVolume(if (state.volume > 0f) 0f else 0.8f) }, size = 36.dp)
            RoundIconButton(AppIcon.Grid, "海报墙", store::openPosterWall, size = 36.dp)
            RoundIconButton(AppIcon.Fullscreen, "全屏歌词", store::openLyrics, size = 36.dp)
        }
    }
}
