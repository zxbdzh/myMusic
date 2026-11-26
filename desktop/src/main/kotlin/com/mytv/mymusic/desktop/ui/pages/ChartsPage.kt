package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mytv.mymusic.desktop.state.formatCount
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.ChartCard
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SectionTitle
import com.mytv.mymusic.desktop.ui.components.SkeletonRow
import com.mytv.mymusic.desktop.ui.components.TrackRow

/**
 * 榜单页（对应 Android `ui/charts/ChartsScreen.kt` 的平板布局）：
 * 头部 + 音源 chip + 一张主推榜 + 热门单曲 + 全部榜单网格。
 *
 * 桌面和手机的信息密度差别最明显：手机上主推榜是整屏轮播，桌面上它只是首屏的一块，
 * 下面立刻要能看到"直接能点的歌"，所以热门单曲列表占了首屏下半屏。
 */
@Composable
fun ChartsPage(state: MusicUiState, store: MusicStore) {
    if (state.collection.open) {
        CollectionPage(state, store)
        return
    }
    val charts = state.charts
    val hotRows = charts.hotSongs.take(8).chunked(2)
    val chartRows = charts.charts.chunked(3)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(title = "榜单", subtitle = "${state.providerName} · 每天 06:00 更新") {
                RoundIconButton(AppIcon.Refresh, "刷新榜单", store::refreshCharts, size = 36.dp)
            }
        }
        item { ProviderChips(state, store) }
        if (charts.error.isNotBlank()) {
            item { ErrorBanner(charts.error, onRetry = store::refreshCharts) }
        }
        if (charts.loading && charts.charts.isEmpty()) {
            item { SkeletonRow(height = 184.dp) }
            item { SkeletonRow(height = 56.dp) }
            item { SkeletonRow(height = 56.dp) }
        } else {
            val featured = charts.featured
            if (featured != null) {
                item {
                    FeaturedChartCard(
                        title = featured.name,
                        subtitle = featured.subtitle,
                        playCount = featured.playCount,
                        seedKey = featured.id,
                        onOpen = { store.openChart(featured) },
                        onPlay = {
                            val queue = charts.hotSongs
                            queue.firstOrNull()?.let { store.playTrack(it, queue) }
                        },
                    )
                }
            }
            if (hotRows.isNotEmpty()) {
                item { SectionTitle("热门单曲") }
                items(hotRows.size) { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        hotRows[rowIndex].forEachIndexed { column, track ->
                            TrackRow(
                                track = track,
                                index = rowIndex * 2 + column,
                                isCurrent = state.current?.id == track.id,
                                playing = state.playing,
                                onPlay = { store.playTrack(track, charts.hotSongs) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (hotRows[rowIndex].size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (chartRows.isNotEmpty()) {
                item { SectionTitle("全部榜单") }
                items(chartRows.size) { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        chartRows[rowIndex].forEach { chart ->
                            ChartCard(
                                chart = chart,
                                onClick = { store.openChart(chart) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - chartRows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 主推榜：左封面右文案，两个动作。播放页之后也会用同一套"大图 + 双动作"结构。 */
@Composable
private fun FeaturedChartCard(
    title: String,
    subtitle: String,
    playCount: Long,
    seedKey: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(184.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(seedKey = seedKey, label = title, modifier = Modifier.size(160.dp), corner = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (playCount > 0) {
                Text(
                    text = formatCount(playCount) + " 次播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniAction(label = "播放", icon = AppIcon.Play, onClick = onPlay, primary = true)
                MiniAction(label = "查看曲目", icon = AppIcon.Queue, onClick = onOpen)
            }
        }
    }
}

/** 小一号的胶囊动作按钮：主推卡里放两个 FilledTonalButton 会显得笨重。 */
@Composable
private fun MiniAction(label: String, icon: AppIcon, onClick: () -> Unit, primary: Boolean = false) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIconView(icon, content, size = 14.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
