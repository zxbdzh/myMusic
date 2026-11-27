package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.components.pcTooltip

/** 顶部四个主入口，图标沿用 Android `AppNavigation.kt` 的 `TopTab` 定义。 */
enum class TopTab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Charts(Route.Charts, "榜单", Icons.Outlined.Insights, Icons.Filled.Insights),
    Playlists(Route.Playlists, "歌单", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
    Search(Route.Search, "搜索", Icons.Outlined.Search, Icons.Filled.Search),
    Library(Route.Library, "我的", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
}

/** 200dp PC 标准三段式横排侧边栏宽（ADR-0001 / Q1=1A + Q7）。 */
private val SidebarWidth = 200.dp

/**
 * PC 原生左侧边栏：彻底替代原先 96dp 的安卓平板竖排 `TabletNavRail`。
 *
 * 三段结构：
 * 1. **发现**：榜单 / 歌单 / 搜索（32dp 横排图标+文字，带 hover 高亮与选中胶囊底）；
 * 2. **我的音乐**：我的曲库（右侧显示收藏总数）；
 * 3. **自建歌单**：直接列出 Room 持久化的歌单树，支持行内 `+` 新建、单击进入、悬停删除。
 */
@Composable
fun AppNavRail(
    state: MusicUiState,
    store: MusicStore,
) {
    var creating by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column(
        Modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        // 品牌标识（单行紧凑 32dp）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = "myMusic",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "0.0.1",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // 中间可滚动导航与歌单树
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SidebarSectionHeader("发现")
            listOf(TopTab.Charts, TopTab.Playlists, TopTab.Search).forEach { tab ->
                val selected = state.route == tab.route && !state.collection.open
                SidebarNavRow(
                    icon = if (selected) tab.selectedIcon else tab.icon,
                    label = tab.label,
                    selected = selected,
                    onClick = {
                        if (state.collection.open) store.closeCollection()
                        store.navigate(tab.route)
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            SidebarSectionHeader("我的音乐")
            val libSelected = state.route == Route.Library && !state.collection.open
            SidebarNavRow(
                icon = if (libSelected) TopTab.Library.selectedIcon else TopTab.Library.icon,
                label = "我的曲库",
                badge = state.library.stats.favorites.takeIf { it > 0 }?.toString(),
                selected = libSelected,
                onClick = {
                    if (state.collection.open) store.closeCollection()
                    store.navigate(Route.Library)
                },
            )

            Spacer(Modifier.height(10.dp))
            // 第三段：自建歌单树（Room 持久化）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "自建歌单",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { creating = !creating }
                        .pcTooltip("新建自建歌单"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "新建歌单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            if (creating) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (newPlaylistName.isEmpty()) {
                            Text("输入歌单名回车...", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        BasicTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                                        val name = newPlaylistName.trim()
                                        if (name.isNotEmpty()) {
                                            store.createUserPlaylist(name)
                                            newPlaylistName = ""
                                            creating = false
                                        }
                                        true
                                    } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                                        newPlaylistName = ""
                                        creating = false
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                }
            }

            val playlists = state.library.playlists
            if (playlists.isEmpty() && !creating) {
                Text(
                    text = "点右上角 + 新建歌单",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                playlists.forEach { playlist ->
                    SidebarPlaylistRow(
                        playlist = playlist,
                        selected = state.collection.open && state.collection.title == playlist.name,
                        onOpen = { store.openPlaylist(playlist) },
                        onDelete = { store.deleteUserPlaylist(playlist.id) },
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )

        val settingsSelected = state.route == Route.Settings
        SidebarNavRow(
            icon = if (settingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
            label = "设置",
            selected = settingsSelected,
            onClick = {
                if (state.collection.open) store.closeCollection()
                store.navigate(Route.Settings)
            },
        )
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SidebarNavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SidebarPlaylistRow(
    playlist: PlaylistSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = playlist.name,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hovered) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .pcTooltip("删除歌单"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "删除歌单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else if (playlist.trackCount > 0) {
            Text(
                text = "${playlist.trackCount}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 路由 → 图标（设置页等单点入口用）。 */
fun Route.icon(): ImageVector = when (this) {
    Route.Charts -> Icons.Filled.Insights
    Route.Playlists -> Icons.AutoMirrored.Filled.QueueMusic
    Route.Search -> Icons.Filled.Search
    Route.Library -> Icons.Filled.LibraryMusic
    Route.Settings -> Icons.Filled.Settings
}
