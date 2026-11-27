package com.mytv.mymusic.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.theme.DesktopTheme
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
 * 主窗口外壳。
 *
 * 结构 = 左导航 + 内容区 + 底部迷你播放条 + 三层覆盖（播放页 / 全屏歌词 / 海报墙）。
 * 和 Android `MyMusicApp` 的对应关系：
 *  - 导航 = `TopTab` + `NavHost`（桌面不用导航库，一个 enum 足够，返回栈由各页自己管）；
 *  - 迷你播放条 = 平板 `BottomPlayerBar`；
 *  - 播放页 / 歌词 / 海报墙 = 那三个 `AnimatedVisibility` 覆盖层；
 *  - Snackbar = 底部一条浮动提示（[NoticeHost]）。
 *
 * 覆盖层用 `AnimatedVisibility` 而不是 `Crossfade`：进入/退出都只是淡入淡出，
 * 播放页里的大封面不会跟着缩放（缩放会让程序化封面重绘，视觉上像在抖）。
 */
@Composable
fun AppShell(store: MusicStore) {
    val state by store.state.collectAsState()
    DesktopTheme(state.appearance) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // 宽度分档跟 Android 平板一致：≥1200dp 右侧常驻 380dp 播放窗格，
            // 更窄时退回底部播放条（840–1199dp 在 Android 上也是底部条）。
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 1200.dp
                Row(Modifier.fillMaxSize()) {
                    AppNavRail(
                        current = state.route,
                        engineReady = state.engine.ready,
                        onSelect = store::navigate,
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f)) {
                            RouteBody(state = state, store = store)
                        }
                        // 宽屏时右侧窗格已经承担了播放控制，不再叠一条底部条
                        if (!wide) MiniPlayerBar(state = state, store = store)
                    }
                    if (wide) NowPlayingPane(state = state, store = store)
                }
            }

            // 覆盖层顺序即层级：海报墙在最下（从播放页可以盖到墙上），歌词在最上
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
 * 底部浮动提示：播放失败、收藏成功、取流降级这类一次性消息。
 * 3.6 秒后自动消失；再次出现同一条消息时（text 变了）重新计时。
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
            .padding(bottom = 104.dp)
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
