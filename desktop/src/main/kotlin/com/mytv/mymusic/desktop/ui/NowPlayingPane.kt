package com.mytv.mymusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.CoverArt
import com.mytv.mymusic.desktop.ui.components.DockDivider
import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.components.HoverCover
import com.mytv.mymusic.desktop.ui.components.PanePlayMode
import com.mytv.mymusic.desktop.ui.components.PcSeekBar
import com.mytv.mymusic.desktop.ui.components.onSecondaryClick

/**
 * 右侧常驻播放窗格（PC 版）。
 *
 * 与 Android 平板 `NowPlayingPane` 的差别就是这一版存在的理由：
 *  - **宽度可拖**：默认 320dp（不是原来照抄平板的 380dp），鼠标拖左侧分隔条在 240–480dp 之间调，
 *    选择落在 `dock.properties`（见 [DockPrefs]）；
 *  - **可折叠**：折起来只剩 44dp 的竖条，一个箭头就能展开；窄窗口把内容区让出来的收益比展示封面大；
 *  - **密度按 PC**：封面是"窗格宽 - 40dp"的方块而不是铺满，标题 15sp / 歌手 12sp / 次要 11sp；
 *  - **交互按 PC**：进度条 hover 出精确时间气泡、拖动放大；走带 32dp、主键 44dp；
 *    「接下来播放」行高 32dp、hover 才露播放键；右键弹播放模式菜单。
 *
 * 折叠状态与宽度都由 [DockPrefs] 持有，AppShell 不需要为窗格改布局参数——
 * 组件自己带上分隔条并把总宽度算进去。
 */
@Composable
fun NowPlayingPane(state: MusicUiState, store: MusicStore) {
    if (DockPrefs.collapsed) {
        CollapsedDockStrip(store)
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    // 右键按下位置：菜单从这里弹出，而不是窗格固定的左上角
    var menuAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Row(Modifier.fillMaxHeight()) {
        // 分隔条自己管理命中区与 hover 高亮；拖动只改内存，松手才落盘
        DockDivider(
            onDrag = { deltaDp -> DockPrefs.resizeBy(deltaDp) },
            onDragStop = { DockPrefs.commit() },
        )
        Box(
            Modifier
                .width(DockPrefs.widthDp.dp)
                .fillMaxHeight()
                .background(PaneBackground)
                .onSecondaryClick { position ->
                    menuAt = position
                    menuOpen = true
                },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PaneHeader(store)
                val current = state.current
                if (current == null) {
                    EmptyPane()
                    return@Column
                }

                // 封面：方形，宽度 = 窗格宽 - 40dp（两侧 padding 各 20dp），不铺满
                HoverCover(
                    seedKey = current.id,
                    label = current.title,
                    imageUrl = current.coverUrl,
                    onClick = store::openPlayer,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    corner = 16.dp,
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = current.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaneOnBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(current.artist, current.album).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = PaneMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${current.providerName}${if (current.playable) "" else " · 不可播"}",
                        fontSize = 11.sp,
                        color = PaneMuted,
                    )
                }

                // 进度：hover 显示精确时间，拖动时条体放大
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PcSeekBar(
                        progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                        onSeekFraction = { fraction -> store.seekTo((state.durationMs * fraction).toLong()) },
                        bubble = { fraction -> formatTime((state.durationMs * fraction).toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                        barHeight = 6.dp,
                        trackColor = PaneSurfaceHigh,
                        activeColor = PaneAccentSoft,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(state.positionMs), fontSize = 11.sp, color = PaneMuted)
                        Text(formatTime(state.durationMs), fontSize = 11.sp, color = PaneMuted)
                    }
                }

                // 走带：两侧 32dp、主键 44dp
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaneButton(AppIcon.Previous, "上一首") { store.previous() }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        PaneIcon(
                            icon = if (state.playing) AppIcon.Pause else AppIcon.Play,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            size = 20.dp,
                            onClick = { store.togglePlay() },
                            label = if (state.playing) "暂停" else "播放",
                        )
                    }
                    PaneButton(AppIcon.Next, "下一首") { store.next() }
                }

                // 入口：播放页 / 歌词 / 队列
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaneAction("播放页", Modifier.weight(1f)) { store.openPlayer() }
                    PaneAction("歌词", Modifier.weight(1f)) { store.openLyrics() }
                    PaneAction("队列", Modifier.weight(1f)) { store.openPosterWall() }
                }

                // 接下来播放：行高 32dp，hover 才露出播放键
                if (state.queue.size > 1) {
                    Text(
                        text = "接下来播放",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PaneMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val upcoming = state.queue.drop(state.queueIndex + 1).take(6)
                    upcoming.forEachIndexed { offset, track ->
                        UpNextRow(
                            seedKey = track.id,
                            title = track.title,
                            artist = track.artist,
                            coverUrl = track.coverUrl,
                            onClick = { store.playTracks(state.queue, state.queueIndex + 1 + offset) },
                        )
                    }
                }
            }

            // 右键菜单：播放模式（顺序 / 随机 / 单曲 / 心动），在鼠标处弹出
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = with(density) {
                    androidx.compose.ui.unit.DpOffset(menuAt.x.toDp(), menuAt.y.toDp())
                },
            ) {
                Text(
                    text = "播放模式",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                PanePlayMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (mode == DockPrefs.playMode) "✓ ${mode.label}" else mode.label,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            DockPrefs.selectPlayMode(mode)
                            val mapped = when (mode) {
                                PanePlayMode.Single -> com.mytv.mymusic.desktop.state.usecase.PlayMode.Single
                                PanePlayMode.Shuffle, PanePlayMode.Heart -> com.mytv.mymusic.desktop.state.usecase.PlayMode.Shuffle
                                PanePlayMode.Sequential -> com.mytv.mymusic.desktop.state.usecase.PlayMode.Sequence
                            }
                            store.setPlayMode(mapped)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

/** 窗格头部：「正在播放」+ 折叠按钮；PC 上折叠是常用动作，放在右上角而不是藏进菜单。 */
@Composable
private fun PaneHeader(store: MusicStore) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "正在播放",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = PaneMuted,
        )
        Spacer(Modifier.weight(1f))
        PaneIcon(AppIcon.ChevronDown, PaneMuted, 16.dp, { DockPrefs.toggleCollapsed() }, "收起窗格")
    }
}

/** 折叠后的竖条：44dp，一个展开箭头，别让用户找不到回来的路。 */
@Composable
private fun CollapsedDockStrip(store: MusicStore) {
    Column(
        Modifier
            .width(DockPrefs.COLLAPSED_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(PaneBackground)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PaneIcon(AppIcon.Back, PaneMuted, 18.dp, { DockPrefs.toggleCollapsed() }, "展开播放窗格")
        // 竖条上只放一个音符图标：空间只够表达"这里还是播放器"
        AppIconView(AppIcon.Music, PaneAccentSoft, size = 18.dp)
    }
}

@Composable
private fun EmptyPane() {
    Spacer(Modifier.height(80.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(PaneAccent),
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(AppIcon.Music, PaneOnAccent, size = 30.dp)
        }
    }
    Text(
        text = "还没有在播放",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = PaneOnBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "从榜单、歌单或搜索结果开始。",
        fontSize = 11.sp,
        color = PaneMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 「接下来播放」的一行：固定 32dp 高，hover 时右侧让出播放键。 */
@Composable
private fun UpNextRow(
    seedKey: String,
    title: String,
    artist: String,
    coverUrl: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .background(if (hovered) PaneSurfaceHigh else PaneBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoverArt(
            seedKey = seedKey,
            label = title,
            modifier = Modifier.size(24.dp),
            corner = 6.dp,
            imageUrl = coverUrl,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = PaneOnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                fontSize = 11.sp,
                color = PaneMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hovered) {
            AppIconView(AppIcon.Play, PaneAccentSoft, size = 14.dp)
        }
    }
}

@Composable
private fun PaneButton(icon: AppIcon, label: String, onClick: () -> Unit) {
    PaneIcon(icon, PaneOnBackground, 18.dp, onClick, label)
}

@Composable
private fun PaneIcon(
    icon: AppIcon,
    tint: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    label: String,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = label) { onClick() }
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(icon, tint, size = size)
    }
}

@Composable
private fun PaneAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PaneSurfaceHigh)
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = PaneOnBackground)
    }
}

// ---- 窗格专用深色配色（与 Android 一致：浏览页浅色、窗格深色，并置才不抢画面）----
private val PaneBackground = androidx.compose.ui.graphics.Color(0xFF12161B)
private val PaneOnBackground = androidx.compose.ui.graphics.Color(0xFFE8EAED)
private val PaneMuted = androidx.compose.ui.graphics.Color(0xFF9AA3AD)
private val PaneSurfaceHigh = androidx.compose.ui.graphics.Color(0xFF232932)
private val PaneAccent = androidx.compose.ui.graphics.Color(0xFF2B4A46)
private val PaneAccentSoft = androidx.compose.ui.graphics.Color(0xFF7FD3C6)
private val PaneOnAccent = androidx.compose.ui.graphics.Color(0xFFB7E4DC)
