package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.SearchKind
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.Metrics
import com.mytv.mymusic.desktop.theme.PcCaption
import com.mytv.mymusic.desktop.theme.PcChip
import com.mytv.mymusic.desktop.theme.PcColumn
import com.mytv.mymusic.desktop.theme.PcColumnHeader
import com.mytv.mymusic.desktop.theme.PcIndex
import com.mytv.mymusic.desktop.theme.PcPageHeader
import com.mytv.mymusic.desktop.theme.PcRow
import com.mytv.mymusic.desktop.theme.PcTitle
import com.mytv.mymusic.desktop.theme.RowSelection
import com.mytv.mymusic.desktop.theme.SourceTag
import com.mytv.mymusic.desktop.theme.trackMenuActions
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/** 可排序的列；key 与 Track 字段一一对应。 */
private enum class SortKey(val label: String) {
    Title("标题"),
    Artist("歌手"),
    Album("专辑"),
    Provider("来源"),
    Duration("时长"),
}

/**
 * 搜索页（PC 原生密度版，t15）。
 *
 * 相对平板版加了三样 PC 才有的东西：
 *  1. **列头**（标题 / 歌手 / 专辑 / 来源 / 时长），点列头排序——PC 用户习惯"表格"而不是"卡片流"；
 *  2. **32dp 单行**：一屏 20+ 条，平板版是 56dp 双行（一屏 9 条）；
 *  3. **来源用小标签（12sp）**而不是大胶囊，行内空间全留给标题和专辑。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPage(state: MusicUiState, store: MusicStore) {
    val search = state.search
    var sortKey by remember { mutableStateOf<SortKey?>(null) }
    var ascending by remember { mutableStateOf(true) }
    val selection = remember { RowSelection() }

    // 排序在内存里做：结果是这一屏的临时视图，切关键词就重新取
    val rows = remember(search.results, sortKey, ascending) {
        val base = search.results
        val key = sortKey ?: return@remember base
        val sorted = when (key) {
            SortKey.Title -> base.sortedBy { it.title }
            SortKey.Artist -> base.sortedBy { it.artist }
            SortKey.Album -> base.sortedBy { it.album }
            SortKey.Provider -> base.sortedBy { it.providerName }
            SortKey.Duration -> base.sortedBy { it.durationMs }
        }
        if (ascending) sorted else sorted.reversed()
    }
    val rowIds = remember(rows) { rows.map { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = Metrics.pagePadding,
        verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
    ) {
        item { PcPageHeader(title = "搜索", subtitle = "跨平台聚合 · 当前 ${state.providerName}") }
        item {
            OutlinedTextField(
                value = search.query,
                onValueChange = store::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.inputHeight)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            store.submitSearch()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                shape = RoundedCornerShape(Metrics.cardRadius),
                // 不继承 typography.bodyMedium：离屏渲染下那条样式链拿不到中文字形回退，
                // 输入的中文会渲染成豆腐块。显式构造 TextStyle 走系统默认字体（见 UI-METRICS.md 已知问题）
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = Metrics.body,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                placeholder = {
                    Text("搜索歌曲、歌手、专辑", fontSize = Metrics.body)
                },
                leadingIcon = {
                    AppIconView(AppIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant, size = Metrics.iconInline)
                },
                trailingIcon = {
                    if (search.query.isNotBlank()) {
                        Box(
                            Modifier
                                .size(Metrics.iconButton)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable(onClick = store::clearSearch),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIconView(AppIcon.Close, MaterialTheme.colorScheme.onSurfaceVariant, size = Metrics.iconSmall)
                        }
                    }
                },
            )
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
            ) {
                SearchKind.entries.forEach { kind ->
                    PcChip(
                        label = kind.label,
                        selected = search.kind == kind,
                        onClick = { store.selectSearchKind(kind) },
                    )
                }
            }
        }
        if (search.error.isNotBlank()) {
            item { ErrorBanner(search.error, onRetry = store::retrySearch) }
        }

        when {
            search.loading -> items(8) { SkeletonRow(height = Metrics.row) }

            search.landing -> {
                if (search.history.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall)) {
                            Text("最近搜索", fontSize = Metrics.sectionTitle, color = MaterialTheme.colorScheme.onSurface)
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                                verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                            ) {
                                search.history.forEach { keyword ->
                                    PcChip(
                                        label = keyword,
                                        selected = false,
                                        onClick = { store.submitSearchFromHistory(keyword) },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall)) {
                        Text("试试这些", fontSize = Metrics.sectionTitle, color = MaterialTheme.colorScheme.onSurface)
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                            verticalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
                        ) {
                            (state.charts.charts.map { it.name } + "晴天" + "孤勇者" + "City Pop")
                                .distinct()
                                .forEach { keyword ->
                                    PcChip(
                                        label = keyword,
                                        selected = false,
                                        onClick = { store.submitSearchFromHistory(keyword) },
                                    )
                                }
                        }
                        Text(
                            "回车搜索；点列头排序；右键单曲可播放 / 喜欢 / 查看歌手。",
                            fontSize = Metrics.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            search.results.isEmpty() -> {
                item {
                    EmptyState(
                        icon = AppIcon.Search,
                        title = "没有找到「${search.submitted}」",
                        hint = "换个关键词，或切换上面的类型再试一次",
                    )
                }
            }

            else -> {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "「${search.submitted}」· ${search.results.size} 条结果",
                            fontSize = Metrics.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        val selected = selection.size
                        if (selected > 0) {
                            Text(
                                "已选 $selected 首",
                                fontSize = Metrics.caption,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item {
                    PcColumnHeader(
                        columns = listOf(
                            PcColumn("", 0.3f),
                            PcColumn(SortKey.Title.label, 1.6f, "title"),
                            PcColumn(SortKey.Artist.label, 1f, "artist"),
                            PcColumn(SortKey.Album.label, 1.2f, "album"),
                            PcColumn(SortKey.Provider.label, 0.6f, "provider"),
                            PcColumn(SortKey.Duration.label, 0.5f, "duration"),
                        ),
                        sortKey = sortKey?.name?.lowercase(),
                        ascending = ascending,
                        onSort = { key ->
                            val next = SortKey.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                            if (next == null) {
                                sortKey = null
                            } else if (sortKey == next) {
                                if (ascending) ascending = false else {
                                    // 第三次点击回到默认顺序：三态更符合表格习惯
                                    sortKey = null
                                    ascending = true
                                }
                            } else {
                                sortKey = next
                                ascending = true
                            }
                        },
                    )
                }
                items(rows.size) { index ->
                    val track = rows[index]
                    SearchResultRow(
                        index = index,
                        track = track,
                        allIds = rowIds,
                        selection = selection,
                        state = state,
                        store = store,
                        queue = rows,
                    )
                }
            }
        }
    }
}

/** 搜索结果的一行：序号 / 标题 / 歌手 / 专辑 / 来源标签 / 时长。 */
@Composable
private fun SearchResultRow(
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
        Box(Modifier.weight(0.6f)) {
            SourceTag(track.providerName)
        }
        Text(
            text = formatTime(track.durationMs),
            fontSize = Metrics.number,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(0.5f),
        )
    }
}
