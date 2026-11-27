package com.mytv.mymusic.desktop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.mytv.mymusic.desktop.win.ShortcutKey
import kotlinx.coroutines.delay

// =====================================================================================
// PC 交互底座（t17）
//
// 这一份是给 ui2 / audio2 复用的「手感层」：hover、右键、tooltip、细滚动条、单击/双击。
// 每条都只做一件事，页面把 Modifier 串上去即可；调用示例见 docs/pc/WINDOW-CHROME.md。
// 风格参照 Windows 11 资源管理器与 Spotify 桌面版：100ms 的 hover 过渡、8dp 细滚动条、
// 右键菜单用系统原生 JPopupMenu（和菜单栏同源，不受 Compose 场景内的层级影响）。
// =====================================================================================

/**
 * 鼠标进入时变色的背景高亮（列表行、卡片通用）。
 *
 * - 默认色是 `onSurface` 6% 混合，深浅主题都不刺眼；需要强调时传 [color]；
 * - 过渡 100ms：再快会闪、再慢会拖沓，这是 Windows 11 列表项的标准节奏；
 * - [enabled] = false 用于「整行禁用」的场景（拖拽中、占位行）。
 */
fun Modifier.hoverHighlight(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color = Color.Unspecified,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else color
    val alpha by animateFloatAsState(
        targetValue = if (enabled && hovered) 1f else 0f,
        animationSpec = tween(100),
        label = "hoverHighlight",
    )
    this
        .hoverable(interaction, enabled = enabled)
        .background(base.copy(alpha = base.alpha * alpha), shape)
}

/**
 * 右键菜单的一项。
 *
 * [dividerAfter] 在这一项之后画一条分隔线（动作分组，如「播放」「加进歌单」「删除」）；
 * [enabled] = false 的项灰显；[shortcut] 用 [ShortcutKey] 而不是字符串，
 * 显示文本由 `displayText()` 统一生成，改键位不用改调用点。
 */
data class ContextMenuItem(
    val label: String,
    val shortcut: ShortcutKey? = null,
    val enabled: Boolean = true,
    val dividerAfter: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 应用级右键菜单宿主状态。
 *
 * `Modifier.contextMenu` 只能发「在某个位置要弹菜单」的请求，真正渲染由窗口层
 * `CompositionLocalProvider(LocalContextMenuState provides …)` + [ContextMenuHost] 完成。
 * 这与 [pcTooltip] 是同一套宿主模式：Modifier 不拥有 UI 子树，渲染统一收口在窗口层。
 */
class ContextMenuState {
    internal var items by mutableStateOf<List<ContextMenuItem>>(emptyList())
    internal var anchor by mutableStateOf(Offset.Zero)
    internal var visible by mutableStateOf(false)

    internal fun show(menuItems: List<ContextMenuItem>, at: Offset) {
        if (menuItems.isEmpty()) return
        items = menuItems
        anchor = Offset(at.x, at.y + 2f)
        visible = true
    }

    internal fun dismiss() {
        visible = false
    }
}

/** 应用级右键菜单宿主；页面直接用 [contextMenu] 即可。 */
val LocalContextMenuState = compositionLocalOf<ContextMenuState?> { null }

/**
 * 右键菜单：检测右键后把菜单请求交给应用级宿主（[LocalContextMenuState]）。
 *
 * 用 Compose 自绘菜单而不是 AWT `JPopupMenu` 的理由：
 *  · Modifier 扩展拿不到 AWT 窗口/组件坐标（desktop 没有 `LocalView`），而 Compose 的
 *    `Popup` 直接用「窗口内坐标」定位，和布局系统同一套坐标，跨 DPI 不会偏；
 *  · 菜单项的字体、圆角、分隔线跟随应用主题（AWT 菜单只能是系统样式，深浅主题下会突兀）。
 * 宿主缺失时静默退化为无菜单（不会崩）。
 *
 * 用法：`Modifier.contextMenu { listOf(ContextMenuItem("播放", onClick = { ... })) }`。
 * items 用惰性 lambda：每次右键才求值，菜单文案能拿到最新状态。
 */
fun Modifier.contextMenu(items: () -> List<ContextMenuItem>): Modifier = composed {
    val host = LocalContextMenuState.current ?: return@composed this
    val currentItems by rememberUpdatedState(items)
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val local = event.changes.firstOrNull()?.position ?: Offset.Zero
                        host.show(currentItems(), origin + local)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
}

/** 右键菜单的渲染端；由 `Main.kt` 放在窗口内容最外层（与 [TooltipHost] 并列）。 */
@Composable
fun ContextMenuHost(state: ContextMenuState) {
    if (!state.visible) return
    val anchorState = state
    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = anchorState.anchor.x.toInt()
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = anchorState.anchor.y.toInt()
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = { state.dismiss() },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.widthIn(min = 180.dp).padding(vertical = 4.dp)) {
                state.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.enabled) {
                                item.onClick()
                                state.dismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                        item.shortcut?.let { shortcut ->
                            Text(
                                text = shortcut.displayText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (item.dividerAfter) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

/**
 * 应用级 tooltip 宿主状态。
 *
 * 由 `Main.kt` 在窗口内容最外层 `CompositionLocalProvider(LocalTooltipState provides …)`
 * 提供、并渲染一个 [TooltipHost]。宿主缺失时 [pcTooltip] 静默退化为无提示（不会崩）。
 */
class TooltipState {
    internal var text by mutableStateOf("")
    internal var anchor by mutableStateOf(Offset.Zero)
    internal var visible by mutableStateOf(false)

    internal fun show(value: String, bounds: Rect) {
        // 锚在组件左下角再往下 6dp：和 Windows 11 的 tooltip 位置一致
        anchor = Offset(bounds.left, bounds.bottom + 6f)
        text = value
        visible = true
    }

    internal fun hide(value: String) {
        // 只有当前显示的就是这条提示时才关：避免鼠标快速划过多个按钮时互相踩状态
        if (visible && text == value) visible = false
    }
}

/** 应用级 tooltip 宿主；页面不需要直接碰它，用 [pcTooltip] 即可。 */
val LocalTooltipState = compositionLocalOf<TooltipState?> { null }

/**
 * 图标按钮的悬停提示：hover 800ms 后出现（Windows 的标准延迟），移开立即消失。
 *
 * 需要窗口层提供 [LocalTooltipState]；没有宿主时什么都不做。
 */
fun Modifier.pcTooltip(text: String, delayMs: Long = 800): Modifier = composed {
    val host = LocalTooltipState.current ?: return@composed this
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(hovered, text) {
        if (hovered && text.isNotBlank()) {
            delay(delayMs)
            host.show(text, bounds)
        } else {
            host.hide(text)
        }
    }
    DisposableEffect(text) {
        onDispose { host.hide(text) }
    }
    this
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .hoverable(interaction)
}

/** tooltip 的渲染端；由 `Main.kt` 放在窗口内容的最外层（AppShell 之后）。 */
@Composable
fun TooltipHost(state: TooltipState) {
    if (!state.visible || state.text.isBlank()) return
    val anchorState = state
    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = anchorState.anchor.x.toInt()
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = anchorState.anchor.y.toInt()
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = { state.visible = false },
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = state.text,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 应用级滚动条样式：8dp 细条、圆角、半透明。
 *
 * 默认色用中性灰（`0x8A808080` 一类），深浅主题下都可见——滚动条在 `MaterialTheme` 外
 * 也能安全使用；需要贴合主题时由调用方传色。
 * hover 变 12dp 由 [PcVerticalScrollbar] / [PcHorizontalScrollbar] 负责：
 * `ScrollbarStyle` 的 thickness 是固定值，宽度动画必须是局部覆盖样式。
 */
fun appScrollbarStyle(
    unhoverColor: Color = Color(0x59808080),
    hoverColor: Color = Color(0x8F808080),
): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 150,
    unhoverColor = unhoverColor,
    hoverColor = hoverColor,
)

private const val HOVER_SCROLLBAR_THICKNESS_DP = 12
private const val BASE_SCROLLBAR_THICKNESS_DP = 8

/**
 * 细滚动条（垂直）：hover 时从 8dp 平滑长到 12dp。
 *
 * 页面里把 `VerticalScrollbar(adapter)` 换成 `PcVerticalScrollbar(adapter)` 即可；
 * 宽度动画走 `animateDpAsState`，与 hover 高亮同为 100–150ms 的节奏。
 */
@Composable
fun PcVerticalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        targetValue = if (hovered) HOVER_SCROLLBAR_THICKNESS_DP.dp else BASE_SCROLLBAR_THICKNESS_DP.dp,
        animationSpec = tween(120),
        label = "scrollbarThickness",
    )
    val base = androidx.compose.foundation.LocalScrollbarStyle.current
    val style = ScrollbarStyle(
        minimalHeight = base.minimalHeight,
        thickness = thickness,
        shape = base.shape,
        hoverDurationMillis = base.hoverDurationMillis,
        unhoverColor = base.unhoverColor,
        hoverColor = base.hoverColor,
    )
    Box(
        modifier = modifier
            .pointerHoverScrollbar { hovered = it }
            .pointerHoverIcon(PointerIcon.Default),
    ) {
        androidx.compose.foundation.VerticalScrollbar(adapter = adapter, modifier = Modifier, style = style)
    }
}

/** 细滚动条（水平）：hover 变宽逻辑与垂直版一致。 */
@Composable
fun PcHorizontalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        targetValue = if (hovered) HOVER_SCROLLBAR_THICKNESS_DP.dp else BASE_SCROLLBAR_THICKNESS_DP.dp,
        animationSpec = tween(120),
        label = "scrollbarThickness",
    )
    val base = androidx.compose.foundation.LocalScrollbarStyle.current
    val style = ScrollbarStyle(
        minimalHeight = base.minimalHeight,
        thickness = thickness,
        shape = base.shape,
        hoverDurationMillis = base.hoverDurationMillis,
        unhoverColor = base.unhoverColor,
        hoverColor = base.hoverColor,
    )
    Box(modifier = modifier.pointerHoverScrollbar { hovered = it }) {
        androidx.compose.foundation.HorizontalScrollbar(adapter = adapter, modifier = Modifier, style = style)
    }
}

/** 鼠标进入/离开组件（用于滚动条宽度动画）；onPointerEvent 的 Enter/Exit 在 macOS/Linux 也成立。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.pointerHoverScrollbar(onChange: (Boolean) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Enter) { onChange(true) }
        .then(
            onPointerEvent(PointerEventType.Exit) { onChange(false) },
        )

/**
 * 列表行：单击选中 / 双击播放。
 *
 * 不给 [onDoubleClick] 时双击也只算一次单击（[onClick] 会延迟 300ms 等双击窗口关闭，
 * 这是 Compose `detectTapGestures` 的标准行为）。手型光标自动带上，省得每处重复写。
 */
fun Modifier.clickableRow(
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
): Modifier = composed {
    this
        .pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(onClick, onDoubleClick) {
            detectTapGestures(
                onTap = { onClick() },
                onDoubleTap = { if (onDoubleClick != null) onDoubleClick() else onClick() },
            )
        }
}
