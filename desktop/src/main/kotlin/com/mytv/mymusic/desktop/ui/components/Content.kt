package com.mytv.mymusic.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.state.ChartSummary
import com.mytv.mymusic.desktop.state.PlaylistSummary
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.state.formatCount
import com.mytv.mymusic.desktop.state.formatTime
import kotlin.math.abs

/**
 * 列表与卡片的基础件。
 *
 * 封面全部是**程序化生成**的：骨架期没有 Coil、也不该让截图依赖网络。
 * 同一个曲目 id 永远得到同一张图（色相由 id 的哈希决定），
 * 接 :core 后把 [CoverArt] 换成 "URL 非空就用 Coil、否则回退到程序化封面" 即可。
 */

private fun hueOf(key: String): Float {
    // 简单的 32 位散列 → 0..360 色相。乘一个质数再取模：曲目 id 常常是 t01/t02 这种连号，
    // 直接用 hash%360 会让相邻曲目的封面几乎同色，海报墙看上去像一整片。
    var hash = 17
    key.forEach { hash = hash * 31 + it.code }
    return abs((hash * 37 + 11) % 360).toFloat()
}

/**
 * 由曲目 / 歌单 id 推出的种子色：播放页和歌词页用它生成深色配色，
 * 这样"同一首歌永远是同一套颜色"，和 Android「跟随封面取色」的观感一致
 * （桌面骨架还没有封面图，就只能从 id 稳定地推一个出来）。
 */
fun coverSeedColor(key: String): Color {
    val hue = hueOf(key)
    return Color.hsv(hue, 0.48f, 0.66f)
}

/** 程序化封面：[seedKey] 决定配色，[label] 画首字。 */
@Composable
fun CoverArt(
    seedKey: String,
    label: String,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
) {
    val hue = remember(seedKey) { hueOf(seedKey) }
    val start = remember(hue) { Color.hsv(hue, 0.52f, 0.62f) }
    val end = remember(hue) { Color.hsv((hue + 38f) % 360f, 0.62f, 0.34f) }
    val accent = remember(hue) { Color.hsv((hue + 180f) % 360f, 0.42f, 0.95f) }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCoverPattern(accent) }
        Text(
            text = label.take(1),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.82f),
        )
    }
}

private fun DrawScope.drawCoverPattern(accent: Color) {
    val min = size.minDimension
    // 两个错位圆环 + 一条斜带：远看有层次，近看不会抢首字
    drawCircle(
        color = Color.White.copy(alpha = 0.10f),
        radius = min * 0.52f,
        center = Offset(size.width * 0.82f, size.height * 0.18f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.07f),
        radius = min * 0.34f,
        center = Offset(size.width * 0.82f, size.height * 0.18f),
    )
    drawRect(
        color = accent.copy(alpha = 0.16f),
        topLeft = Offset(size.width * 0.06f, size.height * 0.74f),
        size = Size(size.width * 0.9f, size.height * 0.12f),
    )
}

/** 页面标题：大标题 + 一行说明 + 右侧操作。桌面每页只有这一处 main 标题。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

/** 区块标题（热门单曲 / 全部榜单 …）。 */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (action != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = action)
    }
}

/**
 * 单曲行：序号 / 播放态、封面、标题歌手、专辑、时长、行尾操作。
 * 高度固定 56dp，保证左右两列列表能对齐。
 */
@Composable
fun TrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                AppIconView(
                    icon = if (playing) AppIcon.Music else AppIcon.Pause,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 16.dp,
                )
            } else {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CoverArt(seedKey = track.id, label = track.title, modifier = Modifier.size(40.dp), corner = 10.dp)
        Column(Modifier.weight(1.2f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = track.album,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (track.playable) formatTime(track.durationMs) else "不可播",
            style = MaterialTheme.typography.labelMedium,
            color = if (track.playable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        trailing.invoke(this)
    }
}

/** 榜单卡：一张带渐变的横条，点开进入曲目列表。 */
@Composable
fun ChartCard(chart: ChartSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hue = remember(chart.id) { hueOf(chart.id) }
    val start = Color.hsv(hue, 0.42f, 0.45f)
    val end = Color.hsv((hue + 46f) % 360f, 0.5f, 0.26f)
    Box(
        modifier
            .height(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(start, end)))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = chart.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column {
                Text(
                    text = chart.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chart.playCount > 0) {
                    Text(
                        text = formatCount(chart.playCount) + " 播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
        }
        if (hovered) {
            // 悬停时右下角浮出一个播放标记，告诉用户"这张卡是可点的"
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                AppIconView(AppIcon.Play, Color.White, size = 14.dp)
            }
        }
    }
}

/** 歌单卡：封面 + 名称 + 作者/曲目数。 */
@Composable
fun PlaylistCard(playlist: PlaylistSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
    ) {
        CoverArt(
            seedKey = playlist.id,
            label = playlist.name,
            modifier = Modifier.fillMaxWidth().height(148.dp),
            corner = 14.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(playlist.curator)
                if (playlist.trackCount > 0) append(" · ${playlist.trackCount} 首")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 空态：一个图标 + 一句结论 + 一句提示。 */
@Composable
fun EmptyState(
    icon: AppIcon,
    title: String,
    hint: String = "",
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIconView(icon, MaterialTheme.colorScheme.outline, size = 40.dp, strokeWidth = 1.4.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 错误条：保留在页面顶部而不是弹窗，用户能继续操作别的入口。 */
@Composable
fun ErrorBanner(text: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 统计小方块（我的页 bento）。 */
@Composable
fun StatTile(
    value: String,
    label: String,
    container: Color,
    content: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = content,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.8f))
    }
}

/** 载荷骨架：接口慢的时候占位，避免内容跳动（桌面窗口大，空白更显眼）。 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** 内容区统一的左右留白：页面滚动容器直接用这个 PaddingValues。 */
val PagePadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp)
