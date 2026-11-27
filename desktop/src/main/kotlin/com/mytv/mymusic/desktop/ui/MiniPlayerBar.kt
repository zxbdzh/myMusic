package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.components.PcSeekBar
import com.mytv.mymusic.desktop.ui.components.PcVolumeBar
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.pcTooltip

/**
 * PC 常驻底部播放控制栏（64dp，全窗口宽度常驻，落实 ADR-0001 / Q1=1A）。
 *
 * 三段对称分布：
 * - 左：44dp 封面（点开播放页）+ 曲目/歌手/换源说明 + 红心收藏（落 Room）；
 * - 中：播放模式切换（列表循环/单曲/随机）+ 走带控制 + 悬停时间气泡进度条；
 * - 右：音量静音/滑块 + 全屏歌词 + 海报墙 + 右侧队列抽屉开关。
 */
@Composable
fun MiniPlayerBar(state: MusicUiState, store: MusicStore) {
    val track = state.current
    val liked = track != null && track.id in state.favorites

    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 左：封面 44dp + 曲目信息 + 收藏红心
        Row(
            Modifier.width(280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (track != null) {
                CoverArt(
                    seedKey = track.id,
                    label = track.title,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = store::openPlayer)
                        .pcTooltip("打开播放页"),
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
                    val subtitle = buildString {
                        append(track.artist.ifBlank { "未知歌手" })
                        if (state.streamNote.isNotBlank()) {
                            append(" · ").append(state.streamNote)
                        } else if (track.providerName.isNotBlank()) {
                            append(" · ").append(track.providerName)
                        }
                    }
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIconButton(
                    icon = if (liked) AppIcon.Heart else AppIcon.HeartOutline,
                    label = if (liked) "取消收藏" else "收藏到我喜欢的",
                    onClick = { store.toggleFavorite(track) },
                    size = 28.dp,
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    Modifier
                        .size(44.dp)
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
                        text = "在榜单、歌单或搜索里双击一首歌",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // 中：走带控制 + 进度条
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = store::cyclePlayMode)
                        .pcTooltip("切换播放模式（当前：${state.playMode.label}）")
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = state.playMode.label,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RoundIconButton(AppIcon.Previous, "上一首 (Ctrl+←)", store::previous, size = 28.dp)
                PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 34.dp)
                RoundIconButton(AppIcon.Next, "下一首 (Ctrl+→)", store::next, size = 28.dp)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatTime(state.positionMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
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
                    modifier = Modifier.width(36.dp),
                )
            }
        }

        // 右：音量 + 全屏歌词 + 海报墙 + 播放抽屉开关
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val muted = state.volume <= 0.001f
            RoundIconButton(
                AppIcon.Volume,
                if (muted) "取消静音" else "静音",
                { store.setVolume(if (muted) 0.8f else 0f) },
                size = 28.dp,
                tint = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = muted,
            )
            PcVolumeBar(value = state.volume, onValueChange = store::setVolume, width = 80.dp)
            RoundIconButton(AppIcon.Fullscreen, "全屏歌词 (Ctrl+L)", store::openLyrics, size = 28.dp)
            RoundIconButton(AppIcon.Grid, "海报墙 (Ctrl+P)", store::openPosterWall, size = 28.dp)
            RoundIconButton(
                icon = AppIcon.Queue,
                label = if (DockPrefs.collapsed) "展开队列抽屉 (Ctrl+Shift+P)" else "收起队列抽屉",
                onClick = { DockPrefs.toggleCollapsed() },
                size = 28.dp,
                selected = !DockPrefs.collapsed,
            )
        }
    }
}
