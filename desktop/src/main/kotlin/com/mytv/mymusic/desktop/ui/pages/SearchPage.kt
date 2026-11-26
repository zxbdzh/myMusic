package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.ExpressiveChip
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SkeletonRow
import com.mytv.mymusic.desktop.ui.components.TrackRow

/**
 * 搜索页（对应 Android `ui/search/SearchScreen.kt` 的搜索框 + 结果列表）。
 *
 * 桌面上"回车即搜"是主路径，所以搜索框自己接 Key.Enter，不依赖软键盘的 IME action；
 * 落地页保留历史与热门词，和 Android 的空查询页同构。
 * 语音搜索、识曲、链接解析是 P2（Android 用 WebView / 麦克风，桌面要另选载体）。
 */
@Composable
fun SearchPage(state: MusicUiState, store: MusicStore) {
    val search = state.search
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader(title = "搜索", subtitle = "跨平台聚合 · 当前 ${state.providerName}") }
        item {
            OutlinedTextField(
                value = search.query,
                onValueChange = store::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            store.submitSearch()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                shape = RoundedCornerShape(percent = 50),
                placeholder = {
                    Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyLarge)
                },
                leadingIcon = {
                    AppIconView(AppIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
                },
                trailingIcon = {
                    if (search.query.isNotBlank()) {
                        RoundIconButton(AppIcon.Close, "清空", store::clearSearch, size = 32.dp)
                    }
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchKind.entries.forEach { kind ->
                    ExpressiveChip(
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
            search.loading -> items(6) { SkeletonRow() }

            search.landing -> {
                if (search.history.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "最近搜索",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                search.history.forEach { keyword ->
                                    ExpressiveChip(
                                        label = keyword,
                                        selected = false,
                                        onClick = { store.submitSearchFromHistory(keyword) },
                                        leading = AppIcon.Search,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "试试这些",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            (state.charts.charts.map { it.name } + "晴天" + "孤勇者" + "City Pop")
                                .distinct()
                                .forEach { keyword ->
                                    ExpressiveChip(
                                        label = keyword,
                                        selected = false,
                                        onClick = { store.submitSearchFromHistory(keyword) },
                                    )
                                }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "回车搜索。接 t2 之后这里换成 :core 的聚合搜索（多平台并发 + 聚合去重）。",
                            style = MaterialTheme.typography.bodySmall,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "「${search.submitted}」· ${search.results.size} 条结果",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                items(search.results.size) { index ->
                    val track = search.results[index]
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = state.current?.id == track.id,
                        playing = state.playing,
                        onPlay = { store.playTrack(track, search.results) },
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
