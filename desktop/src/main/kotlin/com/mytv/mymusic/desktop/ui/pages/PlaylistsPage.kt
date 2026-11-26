package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.ExpressiveChip
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.PlaylistCard
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SectionTitle
import com.mytv.mymusic.desktop.ui.components.SkeletonRow

/**
 * 歌单页（对应 Android `ui/playlists/PlaylistsScreen.kt` 的推荐 / 分类两档）。
 *
 * 桌面不需要"听歌模式"那一档的卡片流（那是手机竖屏的消费场景），
 * 先在 P2 留位；这里保证「分类切换 + 卡片网格 + 点开曲目」这条主干是通的。
 */
@Composable
fun PlaylistsPage(state: MusicUiState, store: MusicStore) {
    if (state.collection.open) {
        CollectionPage(state, store)
        return
    }
    val playlists = state.playlists
    val cards = if (playlists.category == "全部") playlists.recommended else playlists.categoryPlaylists
    val rows = cards.chunked(4)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(title = "歌单", subtitle = "${state.providerName} · 编辑推荐与分类") {
                RoundIconButton(AppIcon.Refresh, "刷新歌单", store::refreshPlaylists, size = 36.dp)
            }
        }
        item { ProviderChips(state, store) }
        if (playlists.error.isNotBlank()) {
            item { ErrorBanner(playlists.error, onRetry = store::refreshPlaylists) }
        }
        if (playlists.categories.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playlists.categories.forEach { category ->
                        ExpressiveChip(
                            label = category,
                            selected = playlists.category == category,
                            onClick = { store.selectPlaylistCategory(category) },
                        )
                    }
                }
            }
        }
        item { SectionTitle(if (playlists.category == "全部") "推荐歌单" else "分类 · ${playlists.category}") }
        if ((playlists.loading || playlists.categoryLoading) && rows.isEmpty()) {
            items(2) { SkeletonRow(height = 198.dp) }
        } else if (rows.isEmpty()) {
            item {
                Text(
                    "这个分类还没有歌单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(rows.size) { rowIndex ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[rowIndex].forEach { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { store.openPlaylist(playlist) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - rows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
