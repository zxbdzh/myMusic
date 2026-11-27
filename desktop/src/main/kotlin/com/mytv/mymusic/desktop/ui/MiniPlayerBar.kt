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
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.PcSeekBar
import com.mytv.mymusic.desktop.ui.components.PcVolumeBar
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton

/**
 * 迷你播放条（窄窗口 <1200dp 时出现）。
 *
 * PC 版的密度：整条 56dp（原来照抄平板的 86dp，占了太多竖向空间）、封面 40dp、
 * 标题 13sp / 歌手 11sp。中间只留走带 + 一条细进度（PC 上进度条本来就该细），
 * 右侧音量 / 队列 / 全屏三件套。
 *
 * 没在播放时也不隐藏——给一句"挑一首开始"，否则窗口底部会莫名空出一块。
 */
@Composable
fun MiniPlayerBar(state: MusicUiState, store: MusicStore) {
    val track = state.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 左：封面 40dp + 两行文字（11sp / 13sp）
        Row(
            Modifier.width(240.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (track != null) {
                CoverArt(
                    seedKey = track.id,
                    label = track.title,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = store::openPlayer),
                    corner = 8.dp,
                    imageUrl = track.coverUrl,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${track.artist} · ${track.providerName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "没有正在播放",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "在榜单或搜索里挑一首",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // 中：走带 + 进度
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 28.dp)
            PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 32.dp)
            RoundIconButton(AppIcon.Next, "下一首", store::next, size = 28.dp)
        }
        Text(
            text = formatTime(state.positionMs),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
        PcSeekBar(
            progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeekFraction = { fraction -> store.seekTo((fraction * state.durationMs).toLong()) },
            bubble = { fraction -> formatTime((fraction * state.durationMs).toLong()) },
            modifier = Modifier.weight(1f),
            barHeight = 4.dp,
        )
        Text(
            text = formatTime(state.durationMs),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )

        // 右：音量 + 队列 + 全屏
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val muted = state.volume <= 0.001f
            RoundIconButton(
                AppIcon.Volume,
                if (muted) "取消静音" else "静音",
                { store.setVolume(if (muted) 0.8f else 0f) },
                size = 28.dp,
                tint = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = muted,
            )
            PcVolumeBar(value = state.volume, onValueChange = store::setVolume, width = 72.dp)
            RoundIconButton(AppIcon.Grid, "海报墙（队列）", store::openPosterWall, size = 28.dp)
            RoundIconButton(AppIcon.Fullscreen, "全屏歌词", store::openLyrics, size = 28.dp)
        }
    }
}
