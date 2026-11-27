package com.mytv.mymusic.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import java.io.File
import java.util.Properties
import kotlin.math.roundToInt

/**
 * 桌面独有的交互底座（t16 播放侧 PC 化）。
 *
 * 这里只放"安卓上没有、PC 才需要"的四件东西：
 *  1. 窗格拖动分隔条（鼠标横向拖，改窗格宽度）；窗格宽度 / 折叠 / 播放模式的选择一并落在
 *     `%APPDATA%\myMusic\dock.properties`——桌面版目前还没有把 :core 的 SettingsStore 接进窗口，
 *     先按 CoreMusicCatalog 缓存 Cookie 的同一套「用户目录纯文本」办法持久化，
 *     等 store 侧接上 SettingsStore（`dockWidth` 字段）后整块搬过去，调用点不用改。
 *  2. 进度条 / 音量条：hover 时按光标位置换算数值显示气泡，拖动时高度放大 1.5 倍
 *     （鼠标拖动比手指精确，放大是为了让"正在拖"这件事肉眼可见）。
 *  3. 封面 hover 遮罩：「打开播放页」。
 *  4. 右键手势（`Modifier.onSecondaryClick`），窗格用它弹播放模式菜单。
 *
 * TODO(t17): win2 的 `DesktopInteraction.kt` 落地后，hover 高亮 / tooltip 改用它的实现，
 * 这里的 pointerInput 保留为兜底（分隔条与进度条的指针语义不属于通用底座）。
 */
object DockPrefs {

    const val MIN_WIDTH_DP = 240
    const val MAX_WIDTH_DP = 480
    const val DEFAULT_WIDTH_DP = 320

    /** 折叠后留下的窄条：只放一个展开按钮，不能被拖窄到看不见。 */
    const val COLLAPSED_WIDTH_DP = 44

    private val file: File = File(
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        "myMusic/dock.properties",
    ).also { runCatching { it.parentFile?.mkdirs() } }

    private val props: Properties = Properties().apply {
        runCatching { if (file.exists()) file.inputStream().use { load(it) } }
    }

    /**
     * 窗格宽度（dp）。读写都走这个可观察属性：拖动时界面立刻跟手，
     * 落盘只在拖动结束（[commit]）时做一次——每拖一像素写一次文件没必要。
     */
    var widthDp: Int by mutableStateOf(
        props.getProperty("dockWidth")?.toIntOrNull()?.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP) ?: DEFAULT_WIDTH_DP,
    )
        private set

    var collapsed: Boolean by mutableStateOf(props.getProperty("collapsed")?.toBooleanStrictOrNull() ?: false)
        private set

    var playMode: PanePlayMode by mutableStateOf(
        props.getProperty("playMode")?.let { name -> PanePlayMode.entries.firstOrNull { it.name == name } }
            ?: PanePlayMode.Sequential,
    )
        private set

    /** 拖动中调用：只改内存里的宽度，界面立即跟随。 */
    fun resizeBy(deltaDp: Float) {
        widthDp = clampDockWidth((widthDp + deltaDp).roundToInt())
    }

    /** 拖动结束（或折叠、切模式）时调用：把当前选择落盘，下次启动原样恢复。 */
    fun commit() {
        runCatching {
            props.setProperty("dockWidth", widthDp.toString())
            props.setProperty("collapsed", collapsed.toString())
            props.setProperty("playMode", playMode.name)
            file.outputStream().use { props.store(it, "myMusic desktop dock preferences") }
        }
    }

    fun toggleCollapsed() {
        collapsed = !collapsed
        commit()
    }

    fun selectPlayMode(mode: PanePlayMode) {
        playMode = mode
        commit()
    }
}

/**
 * 窗格宽度夹取：拖动 / 恢复历史值都要经过它。
 *
 * 单独提出来是为了能脱离 Compose 单测（`DockPrefsTest#宽度夹在 240 到 480 之间`，
 * 对应 PC-NATIVE-CHECKLIST 的 P-05 验证要求）。
 */
fun clampDockWidth(value: Int): Int = value.coerceIn(DockPrefs.MIN_WIDTH_DP, DockPrefs.MAX_WIDTH_DP)

/** 逐字歌词的时间标签：网易云 yrc 的 `<00:30.560>字</00:30.780>`。 */
private val inlineWordTag = Regex("""<[^>]*>""")

/**
 * 歌词显示前的兜底清洗。
 *
 * 根因在 `state/lyric/LyricsParser.kt`：它只剥 `[mm:ss]`，遇到 yrc 的尖括号逐字标签会把
 * `<00:30.560>曾</00:30.780>…` 整串当正文。解析器修好之前先在显示层去掉标签，
 * 否则播放页与全屏歌词直接露出 LRC 原文（2026-11-27 截图可见）。
 * 修好之后这个函数是幂等的空操作，可以留着防其他平台的变体格式。
 */
fun cleanLyricLine(raw: String): String = raw.replace(inlineWordTag, "").trim()

/**
 * 播放窗格右键菜单里的四种模式，文案与 Android 播放页一致。
 *
 * TODO(t3/P2): 桌面 store 还没有 repeat/shuffle 的写入口（:playback-core 的
 * `PlaybackQueue.setRepeatMode/setShuffle` 尚未接进 `PlaybackUseCase`）。
 * 当前先记住选择并如实显示，等 store 开放方法后在这里分发即可。
 */
enum class PanePlayMode(val label: String) {
    Sequential("顺序播放"),
    Shuffle("随机播放"),
    Single("单曲循环"),
    Heart("心动模式"),
}

/**
 * 窗格左侧的可拖动分隔条。
 *
 * 命中区 7dp 但只画 1dp 的线：鼠标要容易抓到，视觉上不能抢内容。
 * 拖动时 `onDrag` 收到的是 dp（已按当前 density 换算），调用方直接改宽度。
 */
@Composable
fun DockDivider(
    onDrag: (Float) -> Unit,
    onDragStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier
            .width(7.dp)
            .fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx -> onDrag(with(density) { deltaPx.toDp().value }) },
                onDragStarted = { dragging = true },
                onDragStopped = {
                    dragging = false
                    onDragStop()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (hovered || dragging) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(
                    if (hovered || dragging) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                    },
                ),
        )
    }
}

/**
 * PC 版进度条：hover 显示精确数值气泡，拖动时高度 ×1.5。
 *
 * [bubble] 把 0..1 的分数换算成要显示的文字（时间或百分比）——进度条与音量条共用一份实现，
 * 差别只在文案与高度。
 *
 * 气泡层单独占 22dp 高：hover 出现 / 消失时不改变布局，否则整块内容会跟着跳。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PcSeekBar(
    progress: Float,
    onSeekFraction: (Float) -> Unit,
    bubble: (Float) -> String,
    modifier: Modifier = Modifier,
    barHeight: Dp = 6.dp,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    /** 滚轮步进（0 表示不响应滚轮）；音量条用 0.05 = ±5%，与 P-06 的验收口径一致。 */
    wheelStep: Float = 0f,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val grow = hoverFraction != null || dragging
    val height by animateDpAsState(if (grow) barHeight * 1.5f else barHeight, tween(120), label = "pcBarGrow")
    val shownFraction = hoverFraction

    Box(modifier) {
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            if (shownFraction != null && widthPx > 0) {
                // 气泡按光标位置居中；两端各自夹住，避免文字被裁掉
                val bubbleWidthDp = 64.dp
                val halfPx = with(LocalDensity.current) { bubbleWidthDp.toPx() / 2f }
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (shownFraction * widthPx - halfPx).roundToInt()
                                    .coerceIn(0, (widthPx - halfPx * 2).roundToInt().coerceAtLeast(0)),
                                0,
                            )
                        }
                        .width(bubbleWidthDp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = bubble(shownFraction),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .onPointerEvent(PointerEventType.Move) { event ->
                    hoverFraction = (event.changes.first().position.x / widthPx).coerceIn(0f, 1f)
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoverFraction = null
                }
                // 滚轮：音量条 ±5%（滚轮向下 = 减小）。进度条不接（滚轮改进度太容易误触）
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    if (wheelStep > 0f) {
                        val down = event.changes.first().scrollDelta.y > 0f
                        val next = (progress + if (down) -wheelStep else wheelStep).coerceIn(0f, 1f)
                        onSeekFraction(next)
                    }
                }
                .pointerInput(widthPx) {
                    // 拖动：按下即 seek，位置持续跟手（进度条比"点一下跳一段"更符合 PC 习惯）
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            onSeekFraction((offset.x / widthPx).coerceIn(0f, 1f))
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        onSeekFraction((change.position.x / widthPx).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
                .pointerInput(widthPx) {
                    detectTapGestures { offset -> onSeekFraction((offset.x / widthPx).coerceIn(0f, 1f)) }
                }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(trackColor),
            )
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(height)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(activeColor),
            )
            if (grow && widthPx > 0) {
                // 拖动 / hover 时露出圆点，明确"这一下抓在哪"
                Box(
                    Modifier
                        .offset { IntOffset((progress.coerceIn(0f, 1f) * widthPx - 5).roundToInt(), 0) }
                        .width(10.dp)
                        .height(height)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(activeColor),
                )
            }
        }
    }
}

/** 音量条：同一套 hover 气泡，显示百分比；宽度固定，避免窗格变宽时滑块无限拉长。 */
@Composable
fun PcVolumeBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
) {
    PcSeekBar(
        progress = value,
        onSeekFraction = onValueChange,
        bubble = { fraction -> "${(fraction * 100f).roundToInt()}%" },
        modifier = modifier.width(width),
        barHeight = 4.dp,
        wheelStep = 0.05f,
    )
}

/**
 * 封面 + hover 遮罩：PC 上没有"点封面进播放页"的手势暗示，
 * 悬停时给一句明确的「打开播放页」，用户才知道封面可点。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HoverCover(
    seedKey: String,
    label: String,
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    hoverText: String = "打开播放页",
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick),
    ) {
        CoverArt(
            seedKey = seedKey,
            label = label,
            modifier = Modifier.fillMaxSize(),
            corner = corner,
            imageUrl = imageUrl,
        )
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(90)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hoverText,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * 右键手势：PC 上这是"更多操作"的惯用入口。
 *
 * 回调带上按下位置（[androidx.compose.ui.geometry.Offset]，相对本组件左上角）——
 * 右键菜单要出现在鼠标处，而不是窗格固定的某个角落。
 *
 * 用 `PointerEventType.Press` + `isSecondaryPressed` 而不是 clickable 的 onClick：
 * Compose 的 clickable 只认主键。消费掉这次事件，底下的页面不会再收到。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryClick(action: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) {
            val position = event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero
            action(position)
            event.changes.forEach { it.consume() }
        }
    }
