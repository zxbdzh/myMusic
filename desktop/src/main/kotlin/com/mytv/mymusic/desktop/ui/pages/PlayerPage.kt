package com.mytv.mymusic.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatTime
import com.mytv.mymusic.desktop.theme.PlayerTheme
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView
import com.mytv.mymusic.desktop.ui.components.DockPrefs
import com.mytv.mymusic.desktop.ui.components.HoverCover
import com.mytv.mymusic.desktop.ui.components.PanePlayMode
import com.mytv.mymusic.desktop.ui.components.PcSeekBar
import com.mytv.mymusic.desktop.ui.components.PcVolumeBar
import com.mytv.mymusic.desktop.ui.components.PlayPauseButton
import com.mytv.mymusic.desktop.ui.components.RoundIconButton
import com.mytv.mymusic.desktop.ui.components.cleanLyricLine
import com.mytv.mymusic.desktop.ui.components.coverSeedColor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 播放页封面上限：320dp。
 *
 * 与 `docs/pc/PC-NATIVE-CHECKLIST.md` P-03 对齐（大封面 ≤320dp）——再大就成了"整屏铺满"，
 * PC 上右半屏的信息与歌词同样重要。
 */
private val CoverMaxSize = 320.dp

/**
 * 播放页（PC 版）。
 *
 * Android 的播放页是「上下滑切换的沉浸式大封面」，那是竖屏手势的必然结果；
 * PC 窗口是横的、鼠标是可点的，所以这里改成**左封面 + 右信息/歌词**的两区结构：
 *  - 左：封面 ≤480dp（窗口再大也不放大）+ 曲目信息；
 *  - 右：常驻两行歌词（点进全屏）+ 播放队列预览；
 *  - 顶：返回 / 曲目信息 / 更多（查看专辑、查看歌手、分享、音质）；
 *  - 底：完整走带 + 音量 + 播放模式 + 队列。
 *
 * 配色仍是 Android 的 PlayerTheme（深色 + 当前封面种子色），深色固定不跟随外观模式——
 * 播放页是一块"舞台"，浏览页才跟随主题。
 */
@Composable
fun PlayerPage(state: MusicUiState, store: MusicStore) {
    val track = state.current
    val seed = remember(track?.id) { coverSeedColor(track?.id ?: "mymusic") }
    PlayerTheme(seed) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        ) {
            if (track == null) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppIconView(AppIcon.Music, MaterialTheme.colorScheme.outline, size = 40.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("还没有正在播放的歌曲", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "回到榜单挑一首",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Box
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
                PlayerTopBar(track = track, store = store)

                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                ) {
                    PlayerStage(track = track, state = state, store = store, modifier = Modifier.weight(0.92f))
                    PlayerRightRail(state = state, store = store, modifier = Modifier.weight(1.08f))
                }

                PlayerBottomBar(track = track, state = state, store = store)
            }
        }
    }
}

/** 顶部工具条：返回 / 曲目信息 / 更多。高度 40dp 内解决，不抢内容。 */
@Composable
private fun PlayerTopBar(track: Track, store: MusicStore) {
    var moreOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(AppIcon.Back, "返回", store::closePlayer, size = 36.dp)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 「更多」：查看专辑 / 查看歌手 / 分享 / 音质
        Box {
            RoundIconButton(AppIcon.More, "更多", { moreOpen = true }, size = 36.dp)
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                // Track 目前只带名字、没带平台侧 artistId/albumId；先用名字当 key 试，
                // 平台侧能按名字反查的（酷狗/咪咕等）能打开，纯数字 id 的平台会给出"未找到"。
                // TODO(P2): CoreMusicCatalog 的 toUi 补 artistId/albumId 后换成真 id。
                MoreItem("查看专辑", enabled = track.album.isNotBlank()) {
                    moreOpen = false
                    store.closePlayer()
                    store.openAlbum(track.providerId, track.album, track.album)
                }
                MoreItem("查看歌手", enabled = track.artist.isNotBlank()) {
                    moreOpen = false
                    store.closePlayer()
                    store.openArtist(track.providerId, track.artist, track.artist)
                }
                MoreItem("分享（复制「曲名 - 歌手」）") {
                    moreOpen = false
                    copyToClipboard("${track.title} - ${track.artist}")
                }
                // 音质：播放侧还没有"实际档位"的落点（PlayableStream.level 未接进 Track），
                // 这里如实说明，不编一个假的"无损"。
                Text(
                    text = "音质：资源由 ${track.providerName} 解析（实际档位 P2 接入）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        RoundIconButton(AppIcon.Fullscreen, "全屏歌词", store::openLyrics, size = 36.dp)
    }
}

@Composable
private fun MoreItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** 左区：封面（≤480dp）+ 曲目信息。 */
@Composable
private fun PlayerStage(
    track: Track,
    state: MusicUiState,
    store: MusicStore,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 封面按"可用宽高都放得下"取小，且不超过 480dp —— 大窗口里它也不会变成一整屏
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side: Dp = minOf(CoverMaxSize, maxWidth, maxHeight)
            HoverCover(
                seedKey = track.id,
                label = track.title,
                imageUrl = track.coverUrl,
                onClick = store::openLyrics,
                modifier = Modifier.size(side),
                corner = 20.dp,
                hoverText = "打开全屏歌词",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = track.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(track.providerName)
                if (!track.playable) {
                    Text("仅展示", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 右区：两行常驻歌词（可折叠）+ 队列预览。 */
@Composable
private fun PlayerRightRail(
    state: MusicUiState,
    store: MusicStore,
    modifier: Modifier = Modifier,
) {
    // PC 上歌词栏是可收起的次要信息：收起后队列拿到整块高度（P-03「歌词侧栏可折叠」）
    var lyricsCollapsed by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (lyricsCollapsed) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("歌词已收起", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                RoundIconButton(AppIcon.ChevronDown, "展开歌词", { lyricsCollapsed = false }, size = 28.dp)
            }
        } else {
            LyricsPeek(
                state = state,
                store = store,
                onCollapse = { lyricsCollapsed = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        UpNextSection(state = state, store = store, modifier = Modifier.weight(1f))
    }
}

/**
 * 两行常驻歌词：当前行 + 下一行。
 *
 * 点整块进全屏歌词——PC 上"点歌词展开"比"记住某个按钮"更符合直觉。
 * 没有歌词时给一行说明，不留空洞。
 */
@Composable
private fun LyricsPeek(
    state: MusicUiState,
    store: MusicStore,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lyrics = state.lyrics
    val index = lyrics?.indexAt(state.positionMs) ?: -1
    val current = lyrics?.lines?.getOrNull(index)?.text?.let(::cleanLyricLine)?.ifBlank { "♪" }
    val next = lyrics?.lines?.getOrNull(index + 1)?.text?.let(::cleanLyricLine)?.ifBlank { null }

    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
            .clickable(onClick = store::openLyrics)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "歌词",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // 收起：只收这一块，队列拿到整块高度（P-03「歌词侧栏可折叠」）
            RoundIconButton(AppIcon.ChevronDown, "收起歌词", onCollapse, size = 26.dp)
            AppIconView(AppIcon.Fullscreen, MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
        }
        when {
            state.lyricsLoading -> Text(
                "歌词加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            current == null -> Text(
                "暂无歌词 · 点这里看全屏歌词",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = current,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = next.orEmpty(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 接下来播放：把队列里后面几首摆出来。
 * 行高 32dp、hover 高亮 —— 与窗格同一套密度，播放页不再用平板的 46dp。
 */
@Composable
private fun UpNextSection(state: MusicUiState, store: MusicStore, modifier: Modifier = Modifier) {
    val upNext = state.queue.drop(state.queueIndex + 1).take(10)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "接下来播放",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (upNext.isEmpty()) {
            Text(
                text = "队列里没有更多歌曲",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Column
        }
        upNext.forEachIndexed { offset, track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { store.playTracks(state.queue, state.queueIndex + 1 + offset) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = track.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = track.artist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.6f),
                )
                Text(
                    text = formatTime(track.durationMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 底部：完整走带 + 音量 + 播放模式 + 队列。一条高度固定 56dp，播放页永远露得出来。 */
@Composable
private fun PlayerBottomBar(track: Track, state: MusicUiState, store: MusicStore) {
    var modeOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(formatTime(state.positionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PcSeekBar(
            progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeekFraction = { fraction -> store.seekTo((state.durationMs * fraction).toLong()) },
            bubble = { fraction -> formatTime((state.durationMs * fraction).toLong()) },
            modifier = Modifier.weight(1f),
            barHeight = 6.dp,
        )
        Text(formatTime(state.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val favorite = state.favorites.contains(track.id)
        RoundIconButton(
            icon = if (favorite) AppIcon.Heart else AppIcon.HeartOutline,
            label = if (favorite) "取消收藏" else "收藏",
            onClick = { store.toggleFavorite(track) },
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            selected = favorite,
            size = 32.dp,
        )
        RoundIconButton(AppIcon.Previous, "上一首", store::previous, size = 32.dp, tint = MaterialTheme.colorScheme.onSurface)
        PlayPauseButton(playing = state.playing, onClick = store::togglePlay, size = 44.dp)
        RoundIconButton(AppIcon.Next, "下一首", store::next, size = 32.dp, tint = MaterialTheme.colorScheme.onSurface)

        // 播放模式：与窗格右键菜单共用同一份选择（DockPrefs）；图标用循环箭头，
        // 不能和右边的「队列」按钮同样式，否则两个按钮看起来是一件事。
        Box {
            RoundIconButton(AppIcon.Refresh, DockPrefs.playMode.label, { modeOpen = true }, size = 32.dp)
            DropdownMenu(expanded = modeOpen, onDismissRequest = { modeOpen = false }) {
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
                            modeOpen = false
                        },
                    )
                }
            }
        }

        AppIconView(AppIcon.Volume, MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
        PcVolumeBar(
            value = state.volume,
            onValueChange = store::setVolume,
            width = 96.dp,
        )
        RoundIconButton(AppIcon.Grid, "播放队列（海报墙）", store::openPosterWall, size = 32.dp)
    }
}

@Composable
private fun SourceBadge(name: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** 分享：把「曲名 - 歌手」放进系统剪贴板（PC 上没有系统分享面板，剪贴板是通用落点）。 */
private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
