package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.ChartSummary
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.Metrics
import com.mytv.mymusic.desktop.theme.PcActionButton
import com.mytv.mymusic.desktop.theme.PcCaption
import com.mytv.mymusic.desktop.theme.PcIndex
import com.mytv.mymusic.desktop.theme.PcPageHeader
import com.mytv.mymusic.desktop.theme.PcProviderChips
import com.mytv.mymusic.desktop.theme.PcRow
import com.mytv.mymusic.desktop.theme.PcSectionTitle
import com.mytv.mymusic.desktop.theme.PcTitle
import com.mytv.mymusic.desktop.theme.RowSelection
import com.mytv.mymusic.desktop.theme.hoverLift
import com.mytv.mymusic.desktop.theme.trackMenuActions
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.PcVerticalScrollbar
import com.mytv.mymusic.desktop.ui.components.pcTooltip
import com.mytv.mymusic.desktop.ui.components.CoverImage
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/** 轮播最多几张：3 张 320dp 卡刚好铺满 1440 窗口的内容区。 */
private const val CarouselCount = 3

/**
 * 榜单页（PC 原生密度版，t15）。
 *
 * 与平板版的差异（详见 docs/pc/UI-METRICS.md）：
 *  - 轮播卡 400×248 → **320×180**，一屏能同时看到轮播和下面两栏；
 *  - 歌曲列表从「NO. 序号 + 52dp 封面双行」改为 **32dp 单行**：序号 / 标题 / 歌手 / 时长 / 播放键，
 *    同样高度里一屏从 8 行变成 18+ 行；
 *  - 更多榜单卡片 150dp 胶囊 → **100dp 方块**，两列改为三列。
 */
@Composable
fun ChartsPage(state: MusicUiState, store: MusicStore) {
    if (state.collection.open) {
        CollectionPage(state, store)
        return
    }
    val charts = state.charts
    val carousel = charts.charts.take(CarouselCount)
    val more = charts.charts.drop(CarouselCount)
    val featured = charts.featured ?: charts.charts.firstOrNull()
    val songs = charts.hotSongs
    val selection = remember { RowSelection() }
    val songIds = remember(songs) { songs.map { it.id } }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = Metrics.pagePadding,
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
        ) {
        item {
            PcPageHeader(
                title = "榜单",
                subtitle = "${state.providerName} · ${if (charts.loading) "正在更新" else "刚刚更新"}",
            ) {
                PcActionButton("刷新", AppIcon.Refresh, store::refreshCharts)
            }
        }
        item { PcProviderChips(state, store) }
        if (charts.error.isNotBlank()) {
            item { ErrorBanner(charts.error, onRetry = store::refreshCharts) }
        }
        if (charts.loading && charts.charts.isEmpty()) {
            item { SkeletonRow(height = Metrics.carouselH) }
            item { SkeletonRow(height = Metrics.row * 6) }
            return@LazyColumn
        }
        if (carousel.isNotEmpty()) {
            item {
                ChartCarousel(
                    charts = carousel,
                    provider = state.providerName,
                    onOpen = store::openChart,
                    onPlay = store::playChart,
                )
            }
        }
        if (featured != null || more.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.columnGap)) {
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        PcSectionTitle(featured?.name ?: "热门单曲") {
                            if (songs.isNotEmpty()) {
                                PcActionButton(
                                    label = "播放全部",
                                    icon = AppIcon.Play,
                                    onClick = { store.playTrack(songs.first(), songs) },
                                    primary = true,
                                )
                            }
                        }
                        // 一屏 18 行：32dp 行高，不再带封面
                        songs.take(24).forEachIndexed { index, track ->
                            ChartSongRow(
                                index = index,
                                track = track,
                                allIds = songIds,
                                selection = selection,
                                state = state,
                                store = store,
                                queue = songs,
                            )
                        }
                    }
                    if (more.isNotEmpty()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall)) {
                            PcSectionTitle("更多榜单")
                            more.chunked(3).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                                ) {
                                    row.forEach { chart ->
                                        MoreChartCard(chart, Modifier.weight(1f)) { store.openChart(chart) }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        if (listState.canScrollBackward || listState.canScrollForward) {
            PcVerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** 主推榜的一行：32dp，序号 / 标题 / 歌手 / 时长 / 播放键。 */
@Composable
private fun ChartSongRow(
    index: Int,
    track: Track,
    allIds: List<String>,
    selection: RowSelection,
    state: MusicUiState,
    store: MusicStore,
    queue: List<Track>,
) {
    PcRow(
        index = index,
        id = track.id,
        allIds = allIds,
        selection = selection,
        isCurrent = state.current?.id == track.id,
        onPlay = { store.playTrack(track, queue) },
        menuActions = trackMenuActions(state, store, track),
    ) {
        PcIndex("${index + 1}")
        PcTitle(track.title, weight = 1.6f, current = state.current?.id == track.id)
        PcCaption(track.artist, weight = 1f)
        Text(
            text = formatTime(track.durationMs),
            fontSize = Metrics.number,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            Modifier
                .size(Metrics.iconButton)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { store.playTrack(track, queue) }
                .pcTooltip(if (state.current?.id == track.id && state.playing) "暂停" else "播放"),
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(
                if (state.current?.id == track.id && state.playing) AppIcon.Pause else AppIcon.Play,
                MaterialTheme.colorScheme.onSurface,
                size = Metrics.iconSmall,
            )
        }
    }
}

/**
 * 横向轮播：320×180、圆角 10。封面铺满，底部只压一行标题 + 副标题。
 * 平板版是 400×248，这里按 PC 的内容区宽度收窄，保证首屏还能看到下面两栏。
 */
@Composable
private fun ChartCarousel(
    charts: List<ChartSummary>,
    provider: String,
    onOpen: (ChartSummary) -> Unit,
    onPlay: (ChartSummary) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
        contentPadding = PaddingValues(vertical = Metrics.gapTiny),
    ) {
        items(charts.size) { index ->
            val chart = charts[index]
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .width(Metrics.carouselW)
                    .height(Metrics.carouselH)
                    .hoverLift(hovered)
                    .clip(RoundedCornerShape(Metrics.cardRadius))
                    .hoverable(interaction)
                    .clickable { onOpen(chart) },
            ) {
                CoverImage(
                    imageUrl = chart.coverUrl,
                    seedKey = chart.id,
                    label = chart.name,
                    modifier = Modifier.fillMaxSize(),
                    corner = 0.dp,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                            ),
                        ),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.cardTextPad, vertical = Metrics.gap),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chart.name,
                            fontSize = Metrics.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = chart.subtitle.ifBlank { provider }
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
                                fontSize = Metrics.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (hovered) {
                        Box(
                            Modifier
                                .size(Metrics.playBadge)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClickLabel = "播放${chart.name}") { onPlay(chart) }
                                .pcTooltip("播放${chart.name}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIconView(AppIcon.Play, MaterialTheme.colorScheme.onPrimary, size = Metrics.iconSmall)
                        }
                    }
                }
            }
        }
    }
}

/** 更多榜单卡：100dp 方块，渐变色由 id 派生（刷新前后不跳色）。 */
@Composable
private fun MoreChartCard(chart: ChartSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = rememberTint(chart.id)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .height(Metrics.moreChartH)
            .hoverLift(hovered)
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.72f))))
            .hoverable(interaction)
            .clickable(onClickLabel = "打开${chart.name}") { onClick() }
            .padding(horizontal = Metrics.cardTextPad, vertical = Metrics.gap),
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Text(
                text = chart.name,
                fontSize = Metrics.cardTitle,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chart.playCount > 0) {
                Text(
                    text = formatPlayCount(chart.playCount),
                    fontSize = Metrics.caption,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
        }
        if (hovered) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(Metrics.iconButton)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                AppIconView(AppIcon.Play, Color.White, size = Metrics.badgeIcon)
            }
        }
    }
}

/** 播放次数：1.3 亿 / 7630 万 / 1.2 万，与 Android 的 `formatPlayCount` 同一套进位。 */
internal fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000 -> String.format("%.1f 亿", count / 100_000_000.0)
    count >= 10_000 -> String.format("%.0f 万", count / 10_000.0)
    count > 0 -> "$count"
    else -> ""
}

/** 由 id 派生一个稳定的色调：同一张榜刷新前后颜色不变。 */
@Composable
private fun rememberTint(key: String): Color {
    val palette = listOf(
        Color(0xFF7C6BF5),
        Color(0xFF2FBF71),
        Color(0xFF3B82F6),
        Color(0xFFEF6C6C),
        Color(0xFFE0A458),
        Color(0xFF4EA8A0),
    )
    val index = ((key.hashCode() % palette.size) + palette.size) % palette.size
    return palette[index]
}
