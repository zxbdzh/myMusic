package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.ExpressiveMotion
import com.mytv.mymusic.desktop.theme.PlayerTheme
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.coverSeedColor

/**
 * 海报墙最小版（对应 Android `ui/poster/PosterWallScreen.kt`）。
 *
 * Android 的完整版是一整面可拖拽、可缩放的 3D 墙，还能按听歌模式排成带子；
 * 桌面骨架先给「等宽网格 + 悬停放大 + 点击切歌 + 当前曲高亮」——
 * 交互模型和完整版一致（墙上的封面就是队列），只是省掉了相机与物理。
 * 真正的 3D 相机（WallCamera.kt 那一套）按 ARCHITECTURE 的 B4 批次再做。
 */
@Composable
fun PosterWallPage(state: MusicUiState, store: MusicStore) {
    val queue = state.queue.ifEmpty { listOfNotNull(state.current) }
    val seed = remember(state.current?.id) { coverSeedColor(state.current?.id ?: "wall") }
    PlayerTheme(seed) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton(AppIcon.Back, "返回", store::closePosterWall, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "播放队列",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${queue.size} 首 · 点封面切歌 · Esc 返回",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.current?.let { current ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppIconView(AppIcon.Music, MaterialTheme.colorScheme.primary, size = 16.dp)
                            Text(
                                text = "${current.title} · ${current.artist}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    RoundIconButton(AppIcon.Grid, "收起海报墙", store::closePosterWall, size = 40.dp)
                }
                Spacer(Modifier.height(18.dp))
                if (queue.isEmpty()) {
                    EmptyState(
                        icon = AppIcon.Grid,
                        title = "队列还是空的",
                        hint = "先在榜单或搜索里起播一首，墙上就会排满封面",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 176.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(queue) { index, track ->
                            WallTile(
                                track = track,
                                index = index,
                                active = track.id == state.current?.id,
                                playing = state.playing,
                                onClick = { store.playTracks(queue, index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 墙上一张封面：悬停放大 4%，当前曲加一圈主色描边。 */
@Composable
private fun WallTile(
    track: Track,
    index: Int,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.04f else 1f,
        animationSpec = ExpressiveMotion.spatialSpec,
        label = "wallScale",
    )
    Column(
        Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(18.dp))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (active) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            CoverArt(
                seedKey = track.id,
                label = track.title,
                modifier = Modifier.fillMaxSize(),
                corner = 18.dp,
            )
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(6.dp),
                ) {
                    AppIconView(
                        icon = if (playing) AppIcon.Music else AppIcon.Pause,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = 14.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${track.artist} · ${formatTime(track.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
