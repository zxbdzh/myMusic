package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.EngineUiState
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.ExpressiveNavItem

/**
 * 左侧导航栏（对应 Android 平板/桌面形态的 `TabletNavRail`）：
 * 品牌 + 四个入口 + 底部设置。
 *
 * 桌面窗口比平板高，留白给到 216dp 放得下文字标签；入口顺序和 Android `TopTab` 一致，
 * 设置不占入口位（Android 手机端也是从"我的"进，桌面额外在底部给一个直达）。
 * 底部的后端状态点不是装饰：libVLC 没装时用户要能一眼看到"现在是模拟输出"。
 */
@Composable
fun AppNavRail(state: MusicUiState, onSelect: (Route) -> Unit) {
    Column(
        Modifier
            .width(216.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(vertical = 18.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                AppIconView(AppIcon.Music, MaterialTheme.colorScheme.onPrimary, size = 18.dp)
            }
            Column {
                Text(
                    text = "myMusic",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "PC 预览版",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        listOf(Route.Charts, Route.Playlists, Route.Search, Route.Library).forEach { route ->
            ExpressiveNavItem(
                icon = route.icon(),
                label = route.label,
                selected = state.route == route,
                onClick = { onSelect(route) },
            )
        }
        Spacer(Modifier.weight(1f))
        EngineBadge(state.engine)
        Spacer(Modifier.height(8.dp))
        ExpressiveNavItem(
            icon = AppIcon.Settings,
            label = Route.Settings.label,
            selected = state.route == Route.Settings,
            onClick = { onSelect(Route.Settings) },
        )
    }
}

/** 播放后端状态：就绪是主色点，未就绪是第三色点 + 一行原因。 */
@Composable
private fun EngineBadge(engine: EngineUiState) {
    Row(
        Modifier
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (engine.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                ),
        )
        Column {
            Text(
                text = "播放后端 · ${engine.backend.ifBlank { "未知" }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (engine.ready) "libVLC 就绪" else "模拟输出（未接真实音频）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 路由 → 图标。放在导航栏文件里，避免五个页面各自记一套映射。 */
fun Route.icon(): AppIcon = when (this) {
    Route.Charts -> AppIcon.Charts
    Route.Playlists -> AppIcon.Playlists
    Route.Search -> AppIcon.Search
    Route.Library -> AppIcon.Library
    Route.Settings -> AppIcon.Settings
}
