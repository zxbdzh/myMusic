package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.EmptyState
import com.mytv.mymusic.desktop.ui.components.ErrorBanner
import com.mytv.mymusic.desktop.ui.components.PagePadding
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.SkeletonRow
import com.mytv.mymusic.desktop.ui.components.TrackRow

/**
 * 榜单 / 歌单的详情页（Android 里是 `collection/{key}` 独立路由）。
 *
 * 桌面骨架先做成页内子视图：返回按钮回到所属入口，不引入导航库。
 * 结构照抄 Android `CollectionScreen`：头图 + 标题 + 副标题 + 播放全部 + 曲目列表。
 */
@Composable
fun CollectionPage(state: MusicUiState, store: MusicStore) {
    val collection = state.collection
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RoundIconButton(AppIcon.Back, "返回", store::closeCollection, size = 40.dp)
                CoverArt(
                    seedKey = collection.title,
                    label = collection.title,
                    modifier = Modifier.size(88.dp),
                    corner = 18.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = collection.subtitle.ifBlank { collection.source },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${collection.tracks.size} 首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayAllButton(
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
            items(6) { SkeletonRow() }
        } else if (collection.tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcon.Music,
                    title = "这个歌单还是空的",
                    hint = "接 t2 的真实音源后，这里会显示平台返回的曲目",
                )
            }
        } else {
            items(collection.tracks.size) { index ->
                val track = collection.tracks[index]
                TrackRow(
                    track = track,
                    index = index,
                    isCurrent = state.current?.id == track.id,
                    playing = state.playing,
                    onPlay = { store.playTrack(track, collection.tracks) },
                )
            }
        }
    }
}
