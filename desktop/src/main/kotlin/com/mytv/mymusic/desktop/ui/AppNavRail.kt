package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.components.EngineDot

/**
 * 顶部四个入口，与 Android `AppNavigation.kt` 的 `TopTab` 一一对应。
 *
 * 图标用同一套 Material 图标（`Icons.Outlined.*` 未选中 / `Icons.Filled.*` 选中），
 * 顺序、文案、路由都保持与原版一致——这是"只适配 PC"的前提。
 */
enum class TopTab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Charts(Route.Charts, "榜单", Icons.Outlined.Insights, Icons.Filled.Insights),
    Playlists(Route.Playlists, "歌单", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
    Search(Route.Search, "搜索", Icons.Outlined.Search, Icons.Filled.Search),
    Library(Route.Library, "我的", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
}

/**
 * 左侧导航栏——**逐字照搬** `ui/adaptive/LargeScreen.kt` 的 `TabletNavRail`：
 * `NavigationRail` + 96dp 宽 + `NavigationRailItem`（图标在上、文字在下）+ 底部设置项。
 *
 * 只改了两处：
 * 1. 选中判断从"路由字符串相等"换成"Route 枚举相等"（桌面没有 NavHost 的字符串路由）；
 * 2. 底部多加一个后端状态点——安卓没有，但桌面在 libVLC 缺失时必须让用户看见"现在是模拟输出"。
 */
@Composable
fun AppNavRail(current: Route, engineReady: Boolean, onSelect: (Route) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(96.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        TopTab.entries.forEach { tab ->
            val on = current == tab.route
            NavigationRailItem(
                selected = on,
                onClick = { onSelect(tab.route) },
                icon = { Icon(if (on) tab.selectedIcon else tab.icon, null) },
                label = { Text(tab.label) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        // 把设置项压到底部：与 Android 一致
        NavigationRail(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            EngineDot(engineReady)
            val on = current == Route.Settings
            NavigationRailItem(
                selected = on,
                onClick = { onSelect(Route.Settings) },
                icon = { Icon(if (on) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
                label = { Text("设置") },
                modifier = Modifier.padding(bottom = 16.dp),
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

