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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.Metrics
import com.mytv.mymusic.desktop.theme.PcActionButton
import com.mytv.mymusic.desktop.theme.PcCaption
import com.mytv.mymusic.desktop.theme.PcIndex
import com.mytv.mymusic.desktop.theme.PcPageHeader
import com.mytv.mymusic.desktop.theme.PcRow
import com.mytv.mymusic.desktop.theme.PcTitle
import com.mytv.mymusic.desktop.theme.RowSelection
import com.mytv.mymusic.desktop.theme.trackMenuActions
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.PcVerticalScrollbar
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverImage
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/**
 * 我的页（PC 原生密度版，t15）。
 *
 * 平板版是「4 个统计大卡 + 卡片流」，PC 上改成**分组折叠列表**：
 * 每组一行标题（左标题、右数量），展开后是 32dp 的单行曲目——同一屏能看 20+ 条，
 * 而不是 3 个 96dp 的统计块。
 *
 * 「下载」和「自建歌单」现在没有数据源（本地库在 :core 的 Room，桌面装配是 t2b 之后），
 * 分组保留并显示 0 / 空态，不藏起来——用户要能看到"这里将来有什么"。
 */
@Composable
fun LibraryPage(state: MusicUiState, store: MusicStore) {
    val library = state.library
    // 折叠状态：默认展开"喜欢"和"最近播放"，这两个 BF3 就有数据
    var collapsed by remember { mutableStateOf(setOf("下载", "自建歌单")) }
    val selection = remember { RowSelection() }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = Metrics.pagePadding,
            verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
        ) {
        item {
            PcPageHeader(title = "我的", subtitle = "收藏、历史与自建歌单") {
                PcActionButton("海报墙", AppIcon.Grid, store::openPosterWall)
                PcActionButton("设置", AppIcon.Settings, { store.navigate(Route.Settings) })
            }
        }
        if (library.error.isNotBlank()) {
            item { ErrorBanner(library.error) }
        }
        if (library.loading && library.favorites.isEmpty() && library.history.isEmpty()) {
            items(6) { SkeletonRow(height = Metrics.row) }
            return@LazyColumn
        }

        // ---- 喜欢 ----
        item {
            GroupHeader(
                title = "喜欢",
                count = library.favorites.size,
                open = "喜欢" !in collapsed,
                onToggle = { collapsed = collapsed.toggle("喜欢") },
            )
        }
        if ("喜欢" !in collapsed) {
            if (library.favorites.isEmpty()) {
                item { EmptyGroupHint("还没有喜欢的歌。右键单曲 → 喜欢，或在播放条上点心。") }
            } else {
                items(library.favorites.size) { index ->
                    val track = library.favorites[index]
                    LibraryTrackRow(
                        index = index,
                        track = track,
                        allIds = library.favorites.map { it.id },
                        selection = selection,
                        state = state,
                        store = store,
                        queue = library.favorites,
                    )
                }
            }
        }

        // ---- 最近播放 ----
        item {
            GroupHeader(
                title = "最近播放",
                count = library.history.size,
                open = "最近播放" !in collapsed,
                onToggle = { collapsed = collapsed.toggle("最近播放") },
            )
        }
        if ("最近播放" !in collapsed) {
            if (library.history.isEmpty()) {
                item { EmptyGroupHint("还没有播放记录。双击列表里的任意一首开始。") }
            } else {
                items(library.history.size) { index ->
                    val track = library.history[index]
                    LibraryTrackRow(
                        index = index,
                        track = track,
                        allIds = library.history.map { it.id },
                        selection = selection,
                        state = state,
                        store = store,
                        queue = library.history,
                    )
                }
            }
        }

        // ---- 下载（本地库待接线）----
        item {
            GroupHeader(
                title = "下载",
                count = library.stats.localTracks,
                open = "下载" !in collapsed,
                onToggle = { collapsed = collapsed.toggle("下载") },
            )
        }
        if ("下载" !in collapsed) {
            item { EmptyGroupHint("本地音乐库（Room）在 t2b 之后接入；现在这一组只有数量。") }
        }

        // ---- 自建歌单 ----
        item {
            GroupHeader(
                title = "自建歌单",
                count = library.playlists.size,
                open = "自建歌单" !in collapsed,
                onToggle = { collapsed = collapsed.toggle("自建歌单") },
            )
        }
        if ("自建歌单" !in collapsed) {
            if (library.playlists.isEmpty()) {
                item { EmptyGroupHint("还没有自建歌单。歌单 CRUD 依赖本地库，排在 t2b 之后。") }
            } else {
                items(library.playlists.size) { index ->
                    LocalPlaylistRow(
                        playlist = library.playlists[index],
                        onOpen = { store.openPlaylist(library.playlists[index]) },
                    )
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

/** 分组标题：左侧折叠箭头 + 标题，右侧数量。整行可点。 */
@Composable
private fun GroupHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.row)
            .clip(RoundedCornerShape(Metrics.gapSmall))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onToggle)
            .padding(horizontal = Metrics.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
    ) {
        AppIconView(
            AppIcon.ChevronDown,
            MaterialTheme.colorScheme.onSurfaceVariant,
            size = Metrics.iconSmall,
            modifier = Modifier.rotate(if (open) 0f else -90f),
        )
        Text(
            text = title,
            fontSize = Metrics.sectionTitle,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            fontSize = Metrics.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyGroupHint(text: String) {
    Text(
        text = text,
        fontSize = Metrics.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Metrics.groupIndent, top = Metrics.gapTiny, bottom = Metrics.gap),
    )
}

@Composable
private fun LibraryTrackRow(
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
        )
    }
}

/** 自建歌单行：32dp，封面缩到 20dp，右侧曲目数。 */
@Composable
private fun LocalPlaylistRow(playlist: PlaylistSummary, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.row)
            .clip(RoundedCornerShape(Metrics.gapSmall))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onOpen)
            .padding(horizontal = Metrics.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
    ) {
        CoverImage(
            imageUrl = playlist.coverUrl,
            seedKey = playlist.id,
            label = playlist.name,
            modifier = Modifier.size(Metrics.coverMini),
            corner = Metrics.coverTinyRadius,
        )
        Text(
            text = playlist.name,
            fontSize = Metrics.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = playlist.curator,
            fontSize = Metrics.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(Metrics.gap))
        Text(
            text = "${playlist.trackCount} 首",
            fontSize = Metrics.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (key in this) this - key else this + key
