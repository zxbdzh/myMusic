package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.Metrics
import com.mytv.mymusic.desktop.theme.PcActionButton
import com.mytv.mymusic.desktop.theme.PcCaption
import com.mytv.mymusic.desktop.theme.PcColumn
import com.mytv.mymusic.desktop.theme.PcColumnHeader
import com.mytv.mymusic.desktop.theme.PcIndex
import com.mytv.mymusic.desktop.theme.PcRow
import com.mytv.mymusic.desktop.theme.PcTitle
import com.mytv.mymusic.desktop.theme.RowSelection
import com.mytv.mymusic.desktop.theme.trackMenuActions
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.PcVerticalScrollbar
import com.mytv.mymusic.desktop.ui.components.pcTooltip
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverImage
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/**
 * 榜单 / 歌单的详情页（PC 原生密度版）。
 *
 * 与浏览页同一套尺寸：头部 64dp 封面 + 20sp 标题 + 32dp 单行曲目（带列头），
 * 一屏能看 18+ 首；hover / 双击播放 / 右键菜单与四个浏览页一致（同走 `PcRow`）。
 *
 * t18 核查（qa2）指出详情页缺 1440×900 证据，本文件同时把尺寸从平板版换过来：
 * 旧版是 88dp 封面 + 56dp 的 `TrackRow`，与浏览页密度不一致。
 */
@Composable
fun CollectionPage(state: MusicUiState, store: MusicStore) {
    val collection = state.collection
    val selection = remember { RowSelection() }
    val ids = remember(collection.tracks) { collection.tracks.map { it.id } }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = Metrics.pagePadding,
            // 曲目行之间不留 LazyColumn 间距：32dp 紧贴才能一屏 18+ 行（间距只加在头部与列头之间）
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Metrics.sectionGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                // 返回：40dp 仍偏大，收到 28dp 与行高对齐
                val backInteraction = remember { MutableInteractionSource() }
                val backHovered by backInteraction.collectIsHoveredAsState()
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (backHovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                        )
                        .hoverable(backInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = store::closeCollection)
                        .pcTooltip("返回"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconView(AppIcon.Back, MaterialTheme.colorScheme.onSurface, size = Metrics.iconInline)
                }
                CoverImage(
                    imageUrl = collection.coverUrl,
                    seedKey = collection.title,
                    label = collection.title,
                    modifier = Modifier.size(64.dp),
                    corner = Metrics.coverRadius,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = collection.title,
                        fontSize = Metrics.pageTitle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = collection.subtitle.ifBlank { collection.source },
                        fontSize = Metrics.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${collection.tracks.size} 首",
                    fontSize = Metrics.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PcActionButton(
                    label = "播放全部",
                    icon = AppIcon.Play,
                    primary = true,
                    onClick = {
                        collection.tracks.firstOrNull()?.let { store.playTrack(it, collection.tracks) }
                    },
                )
            }
        }
        if (collection.error.isNotBlank()) {
            item { ErrorBanner(collection.error, onRetry = null) }
        }
        if (collection.loading && collection.tracks.isEmpty()) {
            items(8) { SkeletonRow(height = Metrics.row) }
        } else if (collection.tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcon.Music,
                    title = "这个歌单还是空的",
                    hint = "平台暂时没有返回曲目，或需要登录后再试",
                )
            }
        } else {
            item {
                PcColumnHeader(
                    columns = listOf(
                        PcColumn("", 0.3f),
                        PcColumn("标题", 1.6f),
                        PcColumn("歌手", 1f),
                        PcColumn("专辑", 1.2f),
                        PcColumn("时长", 0.5f),
                    ),
                    sortKey = null,
                    ascending = true,
                    onSort = {},
                )
            }
            items(collection.tracks.size) { index ->
                CollectionTrackRow(
                    index = index,
                    track = collection.tracks[index],
                    allIds = ids,
                    selection = selection,
                    state = state,
                    store = store,
                    queue = collection.tracks,
                )
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

/** 详情页的一行：与搜索/榜单同构的 32dp 单行。 */
@Composable
private fun CollectionTrackRow(
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
        PcCaption(track.album, weight = 1.2f)
        Text(
            text = formatTime(track.durationMs),
            fontSize = Metrics.number,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = Metrics.gap),
        )
    }
}
