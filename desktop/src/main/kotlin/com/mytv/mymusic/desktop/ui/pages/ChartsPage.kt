package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SkeletonRow
import com.mytv.mymusic.desktop.ui.components.CoverImage

/** 轮播里最多放几张：Android 的 CAROUSEL_COUNT 也是 3，再多首屏就看不到歌了。 */
private const val CarouselCount = 3

/**
 * 榜单页。
 *
 * 结构照 `ui/charts/ChartsScreen.kt` 的 `PhoneCharts`（平板与手机共用这一套，只是 `large` 决定卡片尺寸）：
 *
 * ```
 * 标题「榜单」+ 副标题                      [刷新]
 * 平台胶囊（选中带勾）
 * 横向轮播大卡（400×248，圆角 28，底部渐变盖标题 + 播放键）
 * ┌ 左：主推榜的歌曲列表（NO.1 序号 + 播放全部）  ┬ 右：更多榜单（两列胶囊卡）┐
 * └ weight 1.15                                 ┴ weight 0.85                ┘
 * ```
 *
 * 之前那版是「大卡 + 双列热歌 + 方形榜单卡」，跟 Android 完全是两套东西，已按源码重画。
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader(
                title = "榜单",
                subtitle = "${state.providerName} · ${if (charts.loading) "正在更新" else "刚刚更新"}",
            ) {
                RoundIconButton(AppIcon.Refresh, "刷新榜单", store::refreshCharts, size = 36.dp)
            }
        }
        item { ProviderChips(state, store) }
        if (charts.error.isNotBlank()) {
            item { ErrorBanner(charts.error, onRetry = store::refreshCharts) }
        }
        if (charts.loading && charts.charts.isEmpty()) {
            item { SkeletonRow(height = 248.dp) }
            item { SkeletonRow(height = 300.dp) }
            return@LazyColumn
        }
        if (carousel.isNotEmpty()) {
            item { ChartCarousel(carousel, state.providerName, onOpen = store::openChart, onPlay = { store.openChart(it) }) }
        }
        if (featured != null || more.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    // 左：主推榜的歌曲列表，占大边
                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = featured?.name ?: "热门单曲",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (songs.isNotEmpty()) {
                                PlayAllButton(onClick = { store.playTrack(songs.first(), songs) })
                            }
                        }
                        songs.take(8).forEachIndexed { index, track ->
                            FeaturedSongRow(
                                rank = index + 1,
                                track = track,
                                playing = state.current?.id == track.id && state.playing,
                                onPlay = { store.playTrack(track, songs) },
                            )
                        }
                    }
                    // 右：更多榜单，两列胶囊卡
                    if (more.isNotEmpty()) {
                        Column(Modifier.weight(0.85f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "更多榜单",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            more.chunked(2).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { chart ->
                                        MoreChartCard(chart, Modifier.weight(1f)) { store.openChart(chart) }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横向轮播大卡：400×248、圆角 28，封面铺满，底部渐变压一层标题。
 * Android 用的是 `HorizontalMultiBrowseCarousel`（宽度会被容器宽度重新分配），
 * 这里用固定宽度的 LazyRow 近似——桌面上窗口宽度稳定，没必要做浏览式弹性布局。
 */
@Composable
private fun ChartCarousel(
    charts: List<ChartSummary>,
    provider: String,
    onOpen: (ChartSummary) -> Unit,
    onPlay: (ChartSummary) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(charts.size) { index ->
            val chart = charts[index]
            Box(
                Modifier
                    .width(400.dp)
                    .height(248.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onOpen(chart) },
            ) {
                CoverImage(
                    imageUrl = chart.coverUrl,
                    seedKey = chart.id,
                    label = chart.name,
                    modifier = Modifier.fillMaxSize(),
                    corner = 0.dp,
                )
                // 底部渐变：标题压在图上，没有这层字会跟封面撞色
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                                1f to MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                            ),
                        ),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chart.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = chart.subtitle.ifBlank { provider }
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClickLabel = "播放${chart.name}") { onPlay(chart) },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIconView(AppIcon.Play, MaterialTheme.colorScheme.onPrimary, size = 20.dp)
                    }
                }
            }
        }
    }
}

/**
 * 主推榜里的一行：大号 NO. 序号 + 封面 + 标题歌手 + 播放键。
 * 序号是 Android 榜单列表的标志性元素（截图里那个 "NO.1"），保留。
 */
@Composable
private fun FeaturedSongRow(
    rank: Int,
    track: Track,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "NO.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            CoverImage(track.coverUrl, track.id, track.title, Modifier.fillMaxSize(), corner = 12.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
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
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(
                if (playing) AppIcon.Pause else AppIcon.Play,
                if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                size = 16.dp,
            )
        }
    }
}

/**
 * 更多榜单的胶囊卡：Android 的 `DiscoverCard` 是圆角渐变块 + 居中大字。
 * 渐变底色由 chart.id 派生，同一张榜每次进页面颜色一致，不随刷新跳变。
 */
@Composable
private fun MoreChartCard(chart: ChartSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = rememberTint(chart.id)
    Box(
        modifier
            .height(150.dp)
            // Android 的「更多榜单」是胶囊（竖椭圆）而不是圆角方块：圆角取到半宽即胶囊
            .clip(RoundedCornerShape(percent = 50))
            .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.72f))))
            .clickable(onClickLabel = "打开${chart.name}") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = chart.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (chart.playCount > 0) {
                Text(
                    text = formatPlayCount(chart.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.86f),
                )
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

/**
 * 由 id 派生一个稳定的色调。
 *
 * 用 hash 直接取色相而不是随机：同一张榜刷新前后颜色不变，
 * 用户不会以为"榜单换了一批"。
 */
@Composable
private fun rememberTint(key: String): Color {
    val palette = listOf(
        Color(0xFF7C6BF5), // 紫
        Color(0xFF2FBF71), // 绿
        Color(0xFF3B82F6), // 蓝
        Color(0xFFEF6C6C), // 红
        Color(0xFFE0A458), // 橙
        Color(0xFF4EA8A0), // 青
    )
    val index = ((key.hashCode() % palette.size) + palette.size) % palette.size
    return palette[index]
}
