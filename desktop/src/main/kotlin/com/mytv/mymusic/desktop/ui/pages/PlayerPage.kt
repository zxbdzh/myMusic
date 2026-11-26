package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.WavyProgressBar
import com.mytv.mymusic.desktop.ui.components.coverSeedColor
import kotlin.math.max

/**
 * 播放页（对应 Android `ui/player/PlayerScreen.kt` 的平板两栏布局）。
 *
 * 桌面铺满整窗：左边大封面 + 信息 + 控制，右边常驻歌词，不再需要手机上"上下滑切换"那套手势。
 * 配色固定深色 Vibrant（[PlayerTheme]），和 Android 播放页一致——大封面 + 歌词在深色底上才是主角。
 *
 * 顶部三个动作分别接：收起（回主界面）、海报墙、全屏歌词；
 * 队列、定时、音源切换这些面板 P2 再做（Android 上它们是独立的 modal sheet）。
 */
@Composable
fun PlayerPage(state: MusicUiState, store: MusicStore) {
    val track = state.current
    val seed = remember(track?.id) { coverSeedColor(track?.id ?: "mymusic") }
    PlayerTheme(seed) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        ) {
            if (track == null) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppIconView(AppIcon.Music, MaterialTheme.colorScheme.outline, size = 48.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有正在播放的歌曲", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "回到榜单挑一首，或按空格从热门里起播",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Box
            }

            Row(
                Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 76.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                PlayerStage(track = track, state = state, store = store, modifier = Modifier.weight(0.95f))
                LyricsPanel(
                    state = state,
                    onOpenFullscreen = store::openLyrics,
                    modifier = Modifier.weight(1.05f),
                )
            }

            // 顶部工具条压在渐变上，不占内容高度
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundIconButton(AppIcon.ChevronDown, "收起播放页", store::closePlayer, size = 40.dp)
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                RoundIconButton(AppIcon.Grid, "海报墙", store::openPosterWall, size = 40.dp)
                RoundIconButton(AppIcon.Fullscreen, "全屏歌词", store::openLyrics, size = 40.dp)
            }
        }
    }
}

/** 左栏：封面、曲目信息、进度、走带控制、音量。 */
@Composable
private fun PlayerStage(
    track: Track,
    state: MusicUiState,
    store: MusicStore,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        CoverArt(
            seedKey = track.id,
            label = track.title,
            modifier = Modifier.size(320.dp),
            corner = 28.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(track.providerName)
                if (!track.playable) {
                    Text(
                        "仅展示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Column {
            WavyProgressBar(
                progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                playing = state.playing,
                onSeekFraction = { fraction -> store.seekTo((fraction * state.durationMs).toLong()) },
                height = 28.dp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = formatTime(state.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val favorite = state.favorites.contains(track.id)
            RoundIconButton(
                icon = if (favorite) AppIcon.Heart else AppIcon.HeartOutline,
                label = if (favorite) "取消收藏" else "收藏",
                onClick = { store.toggleFavorite(track) },
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = favorite,
                size = 44.dp,
            )
            RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 52.dp, tint = MaterialTheme.colorScheme.onSurface)
            PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 64.dp)
            RoundIconButton(AppIcon.Next, "下一首", store::next, size = 52.dp, tint = MaterialTheme.colorScheme.onSurface)
            RoundIconButton(AppIcon.Queue, "播放队列", { store.openPosterWall() }, size = 44.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIconView(AppIcon.Volume, MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Slider(
                value = state.volume,
                onValueChange = store::setVolume,
                modifier = Modifier.width(180.dp),
            )
            Text(
                text = "${(state.volume * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "后端：${state.engine.backend}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UpNextSection(state = state, store = store)
    }
}

/**
 * 接下来播放：桌面播放页左侧下方原本是一大片空白，
 * 用它把"队列里后面几首"摆出来——比放一个纯装饰的波形更符合播放页的使用场景。
 */
@Composable
private fun UpNextSection(state: MusicUiState, store: MusicStore) {
    val upNext = state.queue.drop(state.queueIndex + 1).take(4)
    if (upNext.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "接下来播放",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        upNext.forEachIndexed { offset, track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { store.playTracks(state.queue, state.queueIndex + 1 + offset) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CoverArt(
                    seedKey = track.id,
                    label = track.title,
                    modifier = Modifier.size(32.dp),
                    corner = 8.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 右栏：歌词。默认显示在播放页里，点右上角进全屏。 */
@Composable
private fun LyricsPanel(
    state: MusicUiState,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lyrics = state.lyrics
    val currentIndex = lyrics?.indexAt(state.positionMs) ?: -1
    // 第一帧就定位到当前行：截图模式下没有时间跑 LaunchedEffect，初始 index 必须算对
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, currentIndex - 2))
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(max(0, currentIndex - 2))
    }
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "歌词",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                RoundIconButton(AppIcon.Fullscreen, "全屏歌词", onOpenFullscreen, size = 36.dp)
            }
        }
        when {
            state.lyricsLoading -> Text(
                "歌词加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lyrics == null || lyrics.lines.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIconView(AppIcon.Music, MaterialTheme.colorScheme.outline, size = 32.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "暂无歌词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "接 t2 后从 provider 取，走同一条 LyricsParser",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val active = index == currentIndex
                    val color by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        animationSpec = androidx.compose.animation.core.tween(220),
                        label = "lineColor",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (active) 1.04f else 1f,
                        animationSpec = ExpressiveMotion.spatialSpec,
                        label = "lineScale",
                    )
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = color,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsScale(scale)
                            .padding(vertical = 2.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

/** 音源角标：播放页只显示来源平台，登录状态在设置里管。 */
@Composable
private fun SourceBadge(name: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 歌词行轻微放大的自绘修饰（避免每行都写一遍 graphicsLayer 参数）。 */
private fun Modifier.graphicsScale(scale: Float): Modifier =
    this.graphicsLayer(scaleX = scale, scaleY = scale)
