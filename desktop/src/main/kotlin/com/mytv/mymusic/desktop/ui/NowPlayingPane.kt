package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.ui.components.CoverImage

/** 与 Android 平板 `NowPlayingPane` 同宽（380dp）。 */
private val PaneWidth = 380.dp

/**
 * 右侧常驻播放窗格。
 *
 * 对照 `ui/adaptive/LargeScreen.kt` 的 `NowPlayingPane`：≥1200dp 时右侧固定 380dp，
 * 内容区把这块让出去。没在播放时不是空白，而是给一句"还没有在播放"和一句说明——
 * 用户第一次打开就知道这里会变成什么。
 *
 * 桌面比安卓多两件事：进度用真实滑块拖（鼠标比手指精确），音量同理。
 */
@Composable
fun NowPlayingPane(state: MusicUiState, store: MusicStore) {
    Column(
        Modifier
            .width(PaneWidth)
            .fillMaxHeight()
            .background(PaneBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = state.current
        if (current == null) {
            // 空态：一个圆形音符 + 两行说明，垂直居中
            Spacer(Modifier.height(120.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(PaneAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconView(AppIcon.Music, PaneOnAccent, size = 40.dp)
                }
            }
            Text(
                text = "还没有在播放",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PaneOnBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "从榜单、歌单或搜索结果开始，播放器会一直留在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = PaneMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        // 封面：方形大图，占满窗格宽度
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(PaneSurfaceHigh),
        ) {
            CoverImage(imageUrl = current.coverUrl, seedKey = current.id, label = current.title, modifier = Modifier.fillMaxSize(), corner = 0.dp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = current.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PaneOnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(current.artist, current.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = PaneMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${current.providerName}${if (current.playable) "" else " · 不可播"}",
                style = MaterialTheme.typography.labelSmall,
                color = PaneMuted,
            )
        }

        // 进度：波浪点阵（与迷你播放条同一套视觉）
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.mytv.mymusic.desktop.ui.components.WavyProgressBar(progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f, playing = state.playing, onSeekFraction = { fraction -> store.seekTo((state.durationMs * fraction).toLong()) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall, color = PaneMuted)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = PaneMuted)
            }
        }

        // 走带
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaneButton(AppIcon.Previous, "上一首") { store.previous() }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                PaneIcon(
                    icon = if (state.playing) AppIcon.Pause else AppIcon.Play,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = 24.dp,
                    onClick = { store.togglePlay() },
                    label = if (state.playing) "暂停" else "播放",
                )
            }
            PaneButton(AppIcon.Next, "下一首") { store.next() }
        }

        // 入口：播放页 / 歌词 / 队列
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneAction("播放页", Modifier.weight(1f)) { store.openPlayer() }
            PaneAction("歌词", Modifier.weight(1f)) { store.openLyrics() }
            PaneAction("队列", Modifier.weight(1f)) { store.openPosterWall() }
        }

        // 接下来播放：只列前 5 首，更多在播放页
        if (state.queue.size > 1) {
            Text(
                text = "接下来播放",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PaneOnBackground,
            )
            val upcoming = state.queue.drop(state.queueIndex + 1).take(5)
            upcoming.forEach { track ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaneSurfaceHigh),
                    ) {
                        CoverImage(imageUrl = track.coverUrl, seedKey = track.id, label = track.title, modifier = Modifier.fillMaxSize(), corner = 8.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = PaneOnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = PaneMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneButton(icon: AppIcon, label: String, onClick: () -> Unit) {
    PaneIcon(icon, PaneOnBackground, 26.dp, onClick, label)
}

@Composable
private fun PaneIcon(
    icon: AppIcon,
    tint: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    label: String,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = label) { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(icon, tint, size = size)
    }
}

@Composable
private fun PaneAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PaneSurfaceHigh)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = PaneOnBackground,
        )
    }
}


// ---- 窗格专用深色配色 ----
// Android 的 NowPlayingPane 整块包在 PlayerTheme（播放页固定深色）里，
// 浏览页是浅色、窗格是深色，两者并置才不抢画面。这里照同样的思路给一组常量。
private val PaneBackground = androidx.compose.ui.graphics.Color(0xFF12161B)
private val PaneOnBackground = androidx.compose.ui.graphics.Color(0xFFE8EAED)
private val PaneMuted = androidx.compose.ui.graphics.Color(0xFF9AA3AD)
private val PaneSurfaceHigh = androidx.compose.ui.graphics.Color(0xFF232932)
private val PaneAccent = androidx.compose.ui.graphics.Color(0xFF2B4A46)
private val PaneOnAccent = androidx.compose.ui.graphics.Color(0xFFB7E4DC)