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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.PageHeader
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SectionTitle
import com.mytv.mymusic.desktop.ui.components.SkeletonRow
import com.mytv.mymusic.desktop.ui.components.StatTile
import com.mytv.mymusic.desktop.ui.components.TrackRow

/**
 * 我的页（对应 Android `ui/library/LibraryScreen.kt` 的平板布局）：
 * 统计 bento + 自建歌单 + 最近播放，右上角是设置与海报墙入口。
 *
 * Android 上"我的"还挂着同步、本地音乐、导入导出、WebDAV 等一整排功能；
 * 桌面骨架先只保留三块最能验证布局的内容，其余按 docs/pc/ARCHITECTURE.md 的 B 批次推进。
 */
@Composable
fun LibraryPage(state: MusicUiState, store: MusicStore) {
    val library = state.library
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(title = "我的", subtitle = "收藏、历史与自建歌单") {
                RoundIconButton(AppIcon.Grid, "打开海报墙", store::openPosterWall, size = 36.dp)
                RoundIconButton(AppIcon.Settings, "设置", { store.navigate(Route.Settings) }, size = 36.dp)
            }
        }
        if (library.error.isNotBlank()) {
            item { ErrorBanner(library.error) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    value = library.stats.favorites.toString(),
                    label = "收藏",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = library.stats.history.toString(),
                    label = "最近播放",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = library.stats.playlists.toString(),
                    label = "自建歌单",
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = library.stats.localTracks.toString(),
                    label = "本地曲目",
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (library.loading && library.playlists.isEmpty() && library.history.isEmpty()) {
            items(3) { SkeletonRow(height = 64.dp) }
        } else {
            item { SectionTitle("自建歌单") }
            if (library.playlists.isEmpty()) {
                item {
                    Text(
                        "还没有歌单。接 t2 后会从 Room 里读出来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(library.playlists.size) { index ->
                    LocalPlaylistRow(
                        playlist = library.playlists[index],
                        onOpen = { store.openPlaylist(library.playlists[index]) },
                    )
                }
            }
            if (library.history.isNotEmpty()) {
                item { SectionTitle("最近播放") }
                items(library.history.take(6).size) { index ->
                    val track = library.history.take(6)[index]
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = state.current?.id == track.id,
                        playing = state.playing,
                        onPlay = { store.playTrack(track, library.history) },
                    )
                }
            }
            if (library.favorites.isNotEmpty()) {
                item { SectionTitle("我喜欢的音乐") }
                items(library.favorites.size) { index ->
                    val track = library.favorites[index]
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = state.current?.id == track.id,
                        playing = state.playing,
                        onPlay = { store.playTrack(track, library.favorites) },
                    )
                }
            }
        }
    }
}

/** 自建歌单行：封面 + 名字 + 曲目数。点开走和平台歌单同一条详情路径。 */
@Composable
private fun LocalPlaylistRow(playlist: PlaylistSummary, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(
            seedKey = playlist.id,
            label = playlist.name,
            modifier = Modifier.size(44.dp),
            corner = 12.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.trackCount} 首 · ${playlist.curator}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        // 返回图标转 180° 当"进入"箭头用：自绘图标集里只保留了 Back，省一个符号
        AppIconView(
            AppIcon.Back,
            MaterialTheme.colorScheme.onSurfaceVariant,
            size = 16.dp,
            modifier = Modifier.graphicsLayer(rotationZ = 180f),
        )
    }
}
