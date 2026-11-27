package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.CommentItem
import com.mytv.mymusic.desktop.state.CommentsUiState
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.PcVerticalScrollbar
import com.mytv.mymusic.desktop.ui.components.RoundIconButton

/**
 * 歌曲评论侧栏抽屉（360dp，右侧贴边）：展示热评与最新评论，支持分页加载更多。
 */
@Composable
fun CommentsDrawer(
    comments: CommentsUiState,
    store: MusicStore,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Row(
        modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = comments.track?.title ?: "歌曲评论",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (comments.total > 0) "共 ${comments.total} 条评论" else "平台评论",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RoundIconButton(AppIcon.Close, "关闭评论", store::closeComments, size = 28.dp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (comments.error.isNotBlank()) {
                        item {
                            Text(
                                text = comments.error,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (comments.loading && comments.hotComments.isEmpty() && comments.comments.isEmpty()) {
                        item {
                            Text(
                                text = "正在加载评论...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (comments.hotComments.isNotEmpty()) {
                        item {
                            Text(
                                text = "精彩评论",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(comments.hotComments, key = { "hot-${it.id}" }) { item ->
                            CommentCard(item)
                        }
                    }
                    if (comments.comments.isNotEmpty()) {
                        item {
                            Text(
                                text = "最新评论",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(comments.comments, key = { "new-${it.id}" }) { item ->
                            CommentCard(item)
                        }
                    }
                    if (!comments.loading && comments.hotComments.isEmpty() && comments.comments.isEmpty() && comments.error.isBlank()) {
                        item {
                            Text(
                                text = "暂无评论或该平台暂不支持评论。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (comments.hasMore) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(enabled = !comments.loading, onClick = store::loadMoreComments)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (comments.loading) "加载中..." else "加载更多评论",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
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
    }
}

@Composable
private fun CommentCard(item: CommentItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.userName.ifBlank { "匿名用户" },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.likedCount > 0) {
                Text(
                    text = "👍 ${item.likedCount}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = item.content,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.timeText.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.timeText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
