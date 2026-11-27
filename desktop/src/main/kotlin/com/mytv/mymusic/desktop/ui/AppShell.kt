package com.mytv.mymusic.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.theme.DesktopTheme
import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.pages.ChartsPage
import com.mytv.mymusic.desktop.ui.pages.LibraryPage
import com.mytv.mymusic.desktop.ui.pages.LyricsPage
import com.mytv.mymusic.desktop.ui.pages.PlayerPage
import com.mytv.mymusic.desktop.ui.pages.PlaylistsPage
import com.mytv.mymusic.desktop.ui.pages.PosterWallPage
import com.mytv.mymusic.desktop.ui.pages.SearchPage
import com.mytv.mymusic.desktop.ui.pages.SettingsPage
import kotlinx.coroutines.delay

/**
 * PC 原生主窗口外壳（ADR-0001 / Q1=1A + Q7）：
 *
 * ```
 * ┌─────────────────────────────────────────────────────────────┐
 * │ PcTopBar (42dp：返回/刷新 + 全局搜索/链接解析 + 状态/抽屉)     │
 * ├──────────────┬────────────────────────────────┬─────────────┤
 * │ AppNavRail   │ RouteBody                      │ NowPlaying  │
 * │ (200dp 横排  │ (高密度内容区，吃满剩余宽度)     │ Pane (按需  │
 * │  + 歌单树)   │                                │ 240-480dp)  │
 * ├──────────────┴────────────────────────────────┴─────────────┤
 * │ MiniPlayerBar (64dp 常驻完整播放控制栏)                       │
 * └─────────────────────────────────────────────────────────────┘
 * ```
 * 覆盖层（海报墙 / 播放页 / 全屏歌词）保持淡入淡出，避免缩放抖动。
 */
@Composable
fun AppShell(store: MusicStore) {
    val state by store.state.collectAsState()
    val history = remember { mutableStateListOf(Route.Charts) }

    LaunchedEffect(state.route) {
        if (history.lastOrNull() != state.route) {
            history.add(state.route)
            if (history.size > 20) history.removeAt(0)
        }
    }

    DesktopTheme(state.appearance) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val allowRightDrawer = maxWidth >= 1080.dp && !DockPrefs.collapsed
                val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

                Column(Modifier.fillMaxSize()) {
                    PcTopBar(
                        state = state,
                        store = store,
                        canGoBack = history.size > 1,
                        onBack = {
                            if (history.size > 1) {
                                history.removeAt(history.lastIndex)
                                store.navigate(history.last())
                            }
                        },
                    )
                    HorizontalDivider(color = dividerColor)

                    Row(Modifier.weight(1f).fillMaxSize()) {
                        AppNavRail(state = state, store = store)
                        VerticalDivider(color = dividerColor)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            RouteBody(state = state, store = store)
                        }
                        if (allowRightDrawer) {
                            NowPlayingPane(state = state, store = store)
                        }
                    }

                    HorizontalDivider(color = dividerColor)
                    MiniPlayerBar(state = state, store = store)
                }
            }

            // 覆盖层顺序即层级：海报墙在最下，歌词在最上
            AnimatedVisibility(
                visible = state.posterOpen,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                PosterWallPage(state, store)
            }
            AnimatedVisibility(
                visible = state.playerOpen,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
            ) {
                PlayerPage(state, store)
            }
            AnimatedVisibility(
                visible = state.lyricsOpen,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
            ) {
                LyricsPage(state, store)
            }

            NoticeHost(state, store)
        }
    }
}

/** 内容路由：五个入口各一页；榜单/歌单页内部自己处理详情子视图。 */
@Composable
private fun RouteBody(state: MusicUiState, store: MusicStore) {
    when (state.route) {
        Route.Charts -> ChartsPage(state, store)
        Route.Playlists -> PlaylistsPage(state, store)
        Route.Search -> SearchPage(state, store)
        Route.Library -> LibraryPage(state, store)
        Route.Settings -> SettingsPage(state, store)
    }
}

/**
 * 底部浮动提示：播放失败、换源提示、收藏成功这类一次性消息。
 * 位于 64dp 常驻底栏上方，不压住控制条。
 */
@Composable
private fun BoxScope.NoticeHost(state: MusicUiState, store: MusicStore) {
    val text = state.notice.ifBlank { state.error }
    if (text.isBlank()) return
    LaunchedEffect(text) {
        delay(3600)
        store.clearNotice()
        store.dismissError()
    }
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 80.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
