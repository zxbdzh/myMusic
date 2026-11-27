package com.mytv.mymusic.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面用到的图标集合。
 *
 * **不再自绘**：这里每个名字都直接映射到 Android 端用的同一套 Material 图标
 * （`Icons.Filled.*` / `Icons.AutoMirrored.*`）。之前是 Canvas 手画的几何图形，
 * 跟安卓的图标形状对不上——"只适配 PC"要求图标逐像素一致，所以整层换掉。
 *
 * 保留这个枚举的意义只剩一个：调用点不必关心具体是哪个 Material 图标，
 * 想换形状只改下面那张映射表。
 */
enum class AppIcon {
    Charts,
    Playlists,
    Search,
    Library,
    Settings,
    Play,
    Pause,
    Next,
    Previous,
    Queue,
    Volume,
    Heart,
    HeartOutline,
    Fullscreen,
    FullscreenExit,
    ChevronDown,
    Grid,
    More,
    Close,
    Back,
    Refresh,
    Music,
}

/** 枚举 → 与 Android 端一致的 Material 图标。 */
fun AppIcon.vector(): ImageVector = when (this) {
    AppIcon.Charts -> Icons.Filled.Insights
    AppIcon.Playlists -> Icons.AutoMirrored.Filled.QueueMusic
    AppIcon.Search -> Icons.Filled.Search
    AppIcon.Library -> Icons.Filled.LibraryMusic
    AppIcon.Settings -> Icons.Filled.Settings
    AppIcon.Play -> Icons.Filled.PlayArrow
    AppIcon.Pause -> Icons.Filled.Pause
    AppIcon.Next -> Icons.Filled.SkipNext
    AppIcon.Previous -> Icons.Filled.SkipPrevious
    // 队列在 Android 端也用 QueueMusic（AutoMirrored）
    AppIcon.Queue -> Icons.AutoMirrored.Filled.QueueMusic
    AppIcon.Volume -> Icons.Filled.VolumeUp
    AppIcon.Heart -> Icons.Filled.Favorite
    AppIcon.HeartOutline -> Icons.Filled.FavoriteBorder
    AppIcon.Fullscreen -> Icons.Filled.Fullscreen
    AppIcon.FullscreenExit -> Icons.Filled.FullscreenExit
    AppIcon.ChevronDown -> Icons.Filled.KeyboardArrowDown
    AppIcon.Grid -> Icons.Filled.GridView
    AppIcon.More -> Icons.Filled.MoreVert
    AppIcon.Close -> Icons.Filled.Close
    AppIcon.Back -> Icons.AutoMirrored.Filled.ArrowBack
    AppIcon.Refresh -> Icons.Filled.Refresh
    AppIcon.Music -> Icons.Filled.MusicNote
}

/**
 * 画一个图标。
 *
 * 签名保持不变（`icon / tint / modifier / size`），内部改成 Material `Icon`——
 * 全项目上百个调用点不用动，视觉立刻和安卓对齐。
 * `strokeWidth` 参数留着忽略：Material 图标是实心矢量，没有描边粗细的概念。
 */
@Composable
fun AppIconView(
    icon: AppIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Dp = 1.7.dp,
) {
    Icon(
        imageVector = icon.vector(),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** 图标 + 文案，用于按钮配置。 */
data class IconSpec(val icon: AppIcon, val label: String)

/**
 * 播放后端状态点。
 *
 * 安卓没有这个：它永远有可用的 Media3。桌面在没有 libVLC 时会退到模拟输出
 * （进度照走、没有声音），用户必须能一眼看到，否则会以为"播了但没声"是 bug。
 */
@Composable
fun EngineDot(ready: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                if (ready) {
                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                },
            ),
    )
}
