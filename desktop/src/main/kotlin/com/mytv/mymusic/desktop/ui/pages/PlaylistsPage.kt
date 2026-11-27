package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.formatCount
import com.mytv.mymusic.desktop.theme.Metrics
import com.mytv.mymusic.desktop.theme.PcActionButton
import com.mytv.mymusic.desktop.theme.PcChip
import com.mytv.mymusic.desktop.theme.PcPageHeader
import com.mytv.mymusic.desktop.theme.PcProviderChips
import com.mytv.mymusic.desktop.theme.PcSectionTitle
import com.mytv.mymusic.desktop.theme.hoverLift
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverImage
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/**
 * 歌单页（PC 原生密度版，t15）。
 *
 * 两处与平板版的实质差异：
 *  1. **分类胶囊改为 FlowRow 换行铺满**：平板上横向滚动藏起来一半分类，PC 的窗口足够宽，
 *     全部可见才能一眼挑；行高按 26dp 一行放得下 20+ 个分类；
 *  2. **网格 4 列 → 5-6 列**：封面从 148dp 收到 **168dp 方形**、卡片高度随宽度自适应，
 *     内容区 990dp 时一屏能看到 5 列 × 3 行（15 张），平板版是 4 列 × 1.5 行（6 张）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsPage(state: MusicUiState, store: MusicStore) {
    if (state.collection.open) {
        CollectionPage(state, store)
        return
    }
    val playlists = state.playlists
    val cards = if (playlists.category == "全部") playlists.recommended else playlists.categoryPlaylists

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = Metrics.pagePadding,
        verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
    ) {
        item {
            PcPageHeader(title = "歌单", subtitle = "${state.providerName} · 编辑推荐与分类") {
                PcActionButton("刷新", AppIcon.Refresh, store::refreshPlaylists)
            }
        }
        item { PcProviderChips(state, store) }
        if (playlists.error.isNotBlank()) {
            item { ErrorBanner(playlists.error, onRetry = store::refreshPlaylists) }
        }
        if (playlists.categories.isNotEmpty()) {
            item {
                // 一行内换行铺满：PC 上不藏内容，分类全可见
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                    verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                ) {
                    playlists.categories.forEach { category ->
                        PcChip(
                            label = category,
                            selected = playlists.category == category,
                            onClick = { store.selectPlaylistCategory(category) },
                        )
                    }
                }
            }
        }
        item { PcSectionTitle(if (playlists.category == "全部") "推荐歌单" else "分类 · ${playlists.category}") }
        if ((playlists.loading || playlists.categoryLoading) && cards.isEmpty()) {
            items(3) { SkeletonRow(height = Metrics.gridCover + Metrics.skeletonText) }
        } else if (cards.isEmpty()) {
            item {
                Text(
                    "这个分类还没有歌单",
                    fontSize = Metrics.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Metrics.pageV),
                )
            }
        } else {
            item {
                PlaylistGrid(cards = cards, onOpen = { store.openPlaylist(it) })
            }
        }
    }
}

/**
 * 自适应网格：按内容区宽度决定列数（168dp 封面 + 8dp 间距），5-8 列。
 *
 * 不用 LazyVerticalGrid：它嵌在 LazyColumn 里会跟外层滚动打架，
 * 而歌单数量有限（一次 20 张），直接 chunked 成行即可。
 */
@Composable
private fun PlaylistGrid(cards: List<PlaylistSummary>, onOpen: (PlaylistSummary) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth.value + Metrics.gap.value) / (Metrics.gridCover.value + Metrics.gap.value))
            .toInt()
            .coerceIn(3, 8)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            cards.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    row.forEach { playlist ->
                        PlaylistTile(playlist, Modifier.weight(1f), onOpen = { onOpen(playlist) })
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 歌单卡：168dp 方形封面 + 一行标题 + 一行数量；hover 抬升并浮出播放按钮。 */
@Composable
private fun PlaylistTile(playlist: PlaylistSummary, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .hoverLift(hovered)
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(Metrics.gapSmall),
    ) {
        Box {
            CoverImage(
                imageUrl = playlist.coverUrl,
                seedKey = playlist.id,
                label = playlist.name,
                modifier = Modifier.fillMaxWidth().height(Metrics.gridCover),
                corner = Metrics.coverRadius,
            )
            if (hovered) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Metrics.rowPadV)
                        .size(Metrics.playBadge)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconView(AppIcon.Play, MaterialTheme.colorScheme.onPrimary, size = Metrics.iconSmall)
                }
            }
        }
        Text(
            text = playlist.name,
            fontSize = Metrics.cardTitle,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Metrics.gapSmall),
        )
        Text(
            text = buildString {
                append(playlist.curator.ifBlank { "歌单" })
                if (playlist.trackCount > 0) append(" · ${playlist.trackCount} 首")
                if (playlist.playCount > 0) append(" · ${formatCount(playlist.playCount)}")
            },
            fontSize = Metrics.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
