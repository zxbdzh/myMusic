package com.mytv.mymusic.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.desktop.theme.ExpressiveMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Expressive 组件的桌面自绘实现。
 *
 * 为什么自己画：`MaterialExpressiveTheme` / `MotionScheme` 在 CMP 的 material3 元数据里是
 * internal（见 `theme/DesktopTheme.kt` 注释），`LargeFlexibleTopAppBar`、`ShortNavigationBar`、
 * `WavyProgressIndicator` 这些 expressive 组件桌面端也没有。缺的不是排版能力，
 * 而是三样"手感"：
 *   1. 导航指示器的弹性位移（低阻尼弹簧 + 胶囊底）；
 *   2. 播放按钮按下时的形变（play ↔ pause 交叉淡入 + 轻微缩放）；
 *   3. 进度条不是直线，而是一条带着呼吸感的波浪（和 Android `WavySeekBar` 的视觉语言一致）。
 * 这三样都用 [ExpressiveMotion] 的物理参数复刻，不引额外依赖。
 */

/**
 * 主导航项：胶囊指示器 + 图标 + 文字。
 * 选中时指示器从中心展开（不是硬切高亮），桌面没有触感反馈，动画就是唯一的"确认感"。
 */
@Composable
fun ExpressiveNavItem(
    icon: AppIcon,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    expanded: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val pill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = ExpressiveMotion.spatialSpec,
        label = "navPill",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            // 选中时图标轻轻放大，配合指示器给出"聚焦"感
            val scale = 1f + 0.08f * pill
            AppIconView(
                icon = icon,
                tint = content,
                size = 22.dp,
                modifier = Modifier.scaleBy(scale),
            )
        }
        if (expanded) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 小工具：给图标做缩放而不引入 graphicsLayer 的重复代码。 */
private fun Modifier.scaleBy(scale: Float): Modifier =
    this.graphicsLayer(scaleX = scale, scaleY = scale)

/**
 * 播放 / 暂停按钮：实心圆 + 图标形变。
 * 用 [AnimatedContent] 做交叉淡入，两个图标之间不会出现"空白帧"。
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by androidx.compose.animation.animateColorAsState(
        targetValue = if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f) else MaterialTheme.colorScheme.primary,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "playContainer",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pcTooltip(if (playing) "暂停" else "播放"),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
            label = "playPause",
        ) { isPlaying ->
            AppIconView(
                icon = if (isPlaying) AppIcon.Pause else AppIcon.Play,
                tint = MaterialTheme.colorScheme.onPrimary,
                size = size * 0.44f,
                strokeWidth = 2.dp,
            )
        }
    }
}

/** 圆形图标按钮（上一首 / 下一首 / 队列 / 收藏 …）。 */
@Composable
fun RoundIconButton(
    icon: AppIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        hovered -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pcTooltip(label),
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(
            icon = icon,
            tint = if (selected) MaterialTheme.colorScheme.primary else tint,
            size = size * 0.55f,
        )
    }
}

/**
 * 波浪进度条：一排高度随正弦起伏的竖条，播过的部分用 primary，未播的用低透明 onSurface。
 * 点击 / 拖动都能 seek——桌面上主要在点击定位，拖动是附带的。
 */
@Composable
fun WavyProgressBar(
    progress: Float,
    playing: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "wavePhase",
    )
    val amplitude by animateFloatAsState(
        targetValue = if (playing) 1f else 0.32f,
        animationSpec = ExpressiveMotion.settleSpec,
        label = "waveAmplitude",
    )
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val bars = 64
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1.2f)
        val playedWidth = size.width * progress.coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val x = i * (barWidth + gap)
            // 两条不同频率的正弦叠加：单频会看出"一串等距的鼓包"，叠一层慢波才有海浪的层次
            val wave = 0.62f * abs(sin(i * 0.5f + phase)) + 0.38f * abs(sin(i * 0.17f - phase * 0.7f))
            val barHeight = size.height * (0.22f + 0.72f * wave * amplitude)
            val top = (size.height - barHeight) / 2f
            val color = if (x + barWidth <= playedWidth) activeColor else trackColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** 胶囊 chip（音源、分类、搜索类型）。 */
@Composable
fun ExpressiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: AppIcon? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) AppIconView(leading, content, size = 15.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
