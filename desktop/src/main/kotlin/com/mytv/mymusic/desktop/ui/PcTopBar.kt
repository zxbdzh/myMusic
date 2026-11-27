package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.pcTooltip

/**
 * PC 原生 40dp 顶部一体功能栏（ADR-0001 / Q7）。
 *
 * 布局对齐主流桌面客户端（Spotify / Apple Music / 网易云 PC）：
 * - 左：返回上级 / 详情关闭 + 刷新当前页；
 * - 中：全局搜索框（输入关键词回车直达搜索结果，粘贴 http(s) 平台链接直接触发链接解析）；
 * - 右：音频引擎状态徽章 + 右侧「正在播放」抽屉开关 + 设置直达。
 */
@Composable
fun PcTopBar(
    state: MusicUiState,
    store: MusicStore,
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    var input by remember(state.search.submitted) { mutableStateOf(state.search.query) }

    fun submitGlobalInput() {
        val text = input.trim()
        if (text.isBlank()) return
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            store.parseLink(text)
        } else {
            store.navigate(Route.Search)
            store.updateQuery(text)
            store.submitSearch(text)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧导航控制：有详情打开时优先关详情，否则回到上一个路由
        val backEnabled = canGoBack || state.collection.open || state.detailKey != null
        RoundIconButton(
            icon = AppIcon.Back,
            label = "返回上级",
            onClick = {
                when {
                    state.collection.open -> store.closeCollection()
                    state.detailKey != null -> store.closeDetail()
                    else -> onBack()
                }
            },
            size = 28.dp,
            tint = if (backEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
        RoundIconButton(
            icon = AppIcon.Refresh,
            label = "刷新当前页 (F5)",
            onClick = {
                when (state.route) {
                    Route.Charts -> store.refreshCharts()
                    Route.Playlists -> store.refreshPlaylists()
                    Route.Search -> store.retrySearch()
                    Route.Library -> store.refreshLibrary()
                    Route.Settings -> Unit
                }
            },
            size = 28.dp,
        )

        Spacer(Modifier.width(4.dp))

        // 中部全局搜索 / 链接解析框
        Row(
            Modifier
                .width(360.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIconView(AppIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) {
                    Text(
                        text = "搜索歌曲 / 歌手 / 歌单，或粘贴分享链接...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                                submitGlobalInput()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
            if (input.isNotEmpty()) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable {
                            input = ""
                            store.clearSearch()
                        }
                        .pcTooltip("清空"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconView(AppIcon.Close, MaterialTheme.colorScheme.onSurfaceVariant, size = 12.dp)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 右侧：音频后端状态指示 + 抽屉开关 + 设置直达
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { store.navigate(Route.Settings) }
                .pcTooltip(state.engine.note.ifBlank { state.engine.backend })
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.engine.ready) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                    ),
            )
            Text(
                text = if (state.engine.ready) state.engine.backend else "${state.engine.backend} · 未就绪",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        RoundIconButton(
            icon = AppIcon.Queue,
            label = if (DockPrefs.collapsed) "展开右侧播放抽屉 (Ctrl+Shift+P)" else "收起右侧播放抽屉 (Ctrl+Shift+P)",
            onClick = { DockPrefs.toggleCollapsed() },
            size = 28.dp,
            selected = !DockPrefs.collapsed,
        )
        RoundIconButton(
            icon = AppIcon.Settings,
            label = "设置 (Ctrl+,)",
            onClick = { store.navigate(Route.Settings) },
            size = 28.dp,
            selected = state.route == Route.Settings,
        )
    }
}
