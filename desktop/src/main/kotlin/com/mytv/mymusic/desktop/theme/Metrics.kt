package com.mytv.mymusic.desktop.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
// PointerEvent.keyboardModifiers 的修饰键扩展在 input.pointer 包（不是 input.key）
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.desktop.state.MusicStore
import com.mytv.mymusic.desktop.state.MusicUiState
import com.mytv.mymusic.desktop.state.Route
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.desktop.ui.components.AppIcon
import com.mytv.mymusic.desktop.ui.components.AppIconView

/**
 * PC 原生尺寸体系（t15）。
 *
 * 背景：上一版直接沿用 Android 平板的数值（行高 56dp、字号 16sp、大卡 248dp、内边距 32dp），
 * 在 1440×900 的窗口里呈现为「平板界面放大版」，被用户否决。PC 端要的是**信息密度**：
 * 同一屏要看到 18+ 行歌、5-6 列卡片，而不是把手机控件等比放大。
 *
 * 这一份是四页唯一的尺寸来源，页面里不再出现裸的 dp / sp 数值；
 * 同时把「紧凑行 + 列头 + 右键菜单 + 多选」这几个 PC 列表原语也放在这里，
 * 避免四个页面各写一份。
 *
 * 取舍说明见 docs/pc/UI-METRICS.md。
 */
object Metrics {
    // ---- 字号（比平板小 2-3sp，PC 的观看距离更近、DPI 更高）----
    val body = 13.sp
    val caption = 11.sp
    /** 来源 / 类型这类小标签，不占胶囊的体积。 */
    val tag = 12.sp
    val cardTitle = 13.sp
    val sectionTitle = 15.sp
    val pageTitle = 22.sp
    /** 键值数字（榜单序号、时长）等宽感更强。 */
    val number = 12.sp

    // ---- 行高 ----
    /** 单行列表：序号 + 标题 + 歌手 + 时长 + 操作，一行放得下。 */
    val row = 32.dp
    /** 带封面小图的双行（歌单 / 专辑）。 */
    val rowCover = 48.dp

    // ---- 网格 / 卡片 ----
    val gridCover = 168.dp
    val carouselW = 320.dp
    val carouselH = 180.dp
    val moreChartH = 100.dp
    /** 卡片底部文案区留白。 */
    val cardTextGap = 6.dp

    // ---- 间距 ----
    val pageH = 20.dp
    val pageV = 16.dp
    val sectionGap = 12.dp
    val gap = 8.dp
    val gapSmall = 4.dp
    val gapTiny = 2.dp

    // ---- 圆角 ----
    val cardRadius = 10.dp
    val coverRadius = 8.dp

    // ---- 图标 ----
    val iconInline = 16.dp
    val iconButton = 20.dp
    val iconSmall = 12.dp

    /** 四页统一的滚动容器内边距。 */
    val pagePadding = PaddingValues(horizontal = pageH, vertical = pageV)

    /** 列头高度（搜索页的排序条）。 */
    val header = 28.dp

    /** 两栏之间的主间距（榜单页左列表 / 右榜单）。 */
    val columnGap = 16.dp
    /** 搜索框高度。 */
    val inputHeight = 38.dp
    /** 浮层播放按钮（卡片 hover 时出现）。 */
    val playBadge = 24.dp
    /** 卡片内文案的左右留白。 */
    val cardTextPad = 10.dp
    /** 行内小封面（自建歌单行）。 */
    val coverMini = 20.dp
    /** 小封面的圆角。 */
    val coverTinyRadius = 4.dp
    /** 行内纵向留白。 */
    val rowPadV = 6.dp
    /** 分组标题的缩进（与折叠箭头对齐）。 */
    val groupIndent = 22.dp
    /** 骨架行的文案区高度。 */
    val skeletonText = 28.dp
    /** 小角标图标。 */
    val badgeIcon = 10.dp

    /** 行 hover / 选中底色的过渡时长：100ms，跟手但不闪烁。 */
    const val HOVER_MS = 100
}

/** 页面标题：22sp 主标题 + 11sp 副标题 + 右侧动作，高度比平板版矮一截。 */
@Composable
fun PcPageHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = Metrics.pageTitle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = Metrics.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
            content = actions,
        )
    }
}

/** 区块标题：15sp，右侧可挂一个「查看全部」之类的动作。 */
@Composable
fun PcSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = Metrics.sectionTitle,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** 小胶囊（音源 / 分类 / 搜索类型）：28dp 高、11sp 字，比平板的 32dp 紧凑一档。 */
@Composable
fun PcChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = Metrics.caption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
        )
    }
}

/** 卡片 hover：轻微抬升（缩放 1.02），PC 上不做 Material 的大阴影。 */
@Composable
fun Modifier.hoverLift(hovered: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.02f else 1f,
        animationSpec = tween(120),
        label = "lift",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 列表多选状态（Ctrl 单选切换 / Shift 区间）。
 *
 * 放在页面级 `remember` 里，不上升为全局状态：多选是"当前这一屏"的临时语义，
 * 切页/刷新都该清掉。
 */
@Stable
class RowSelection {
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set
    private var anchor by mutableStateOf(-1)

    fun contains(id: String): Boolean = id in ids

    val size: Int get() = ids.size

    /** 点一行：Ctrl 切换、Shift 从锚点区间选、裸点击只选它自己。 */
    fun click(index: Int, id: String, allIds: List<String>, ctrl: Boolean, shift: Boolean) {
        ids = when {
            shift && anchor in allIds.indices -> {
                val from = minOf(anchor, index).coerceAtLeast(0)
                val to = maxOf(anchor, index).coerceAtMost(allIds.lastIndex)
                (ids + allIds.subList(from, to + 1)).toSet()
            }

            ctrl -> if (id in ids) ids - id else ids + id
            else -> setOf(id)
        }
        if (!shift) anchor = index
    }

    fun clear() {
        ids = emptySet()
        anchor = -1
    }
}

/** 右键菜单里的一项。 */
data class ContextAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 一个曲目行的标准右键菜单：播放 / 下一首播放 / 加入歌单 / 查看歌手 / 复制链接。
 *
 * 两个如实降级（写进 UI-METRICS.md）：
 *  - 「加入歌单」：桌面还没有本地歌单库（Room 在 :core，装配是 t2b 之后的事），禁用而不是假装成功；
 *  - 「复制链接」：平台链接字段（Song.link）还没经 MusicCatalog 暴露，先复制"平台 + 曲目标识"，
 *    能被后续的链接解析用上。
 */
@Composable
fun trackMenuActions(state: MusicUiState, store: MusicStore, track: Track): List<ContextAction> {
    val liked = track.id in state.favorites
    return listOf(
        ContextAction("播放") { store.playTrack(track, state.queue.ifEmpty { listOf(track) }) },
        ContextAction("下一首播放") { store.playNext(track) },
        ContextAction("加入歌单", enabled = false) { /* t2b：本地歌单库接线后启用 */ },
        ContextAction("查看歌手") {
            store.navigate(Route.Search)
            store.updateQuery(track.artist)
            store.submitSearch(track.artist)
        },
        ContextAction(if (liked) "取消喜欢" else "喜欢") { store.toggleFavorite(track) },
        ContextAction("复制链接") { copyToClipboard("${track.providerId}:${track.id}") },
    )
}

/** 剪贴板写入：Compose Desktop 没有跨平台 API，直接走 AWT。 */
fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

/**
 * PC 紧凑行：32dp 高，支持 hover / 单击选中 / 双击播放 / 右键菜单。
 *
 * 手势自己处理（不用 combinedClickable）是因为需要**点击时的修饰键**：
 * Ctrl 多选、Shift 区间选、右键菜单，`clickable` 拿不到这些信息。
 *
 * TODO(t17): win2 的 `ui/components/DesktopInteraction.kt` 落地后，把这里的
 * hover/右键/双击手势换成 `Modifier.clickableRow` 的统一实现，避免两套手势并存。
 */
@Composable
fun PcRow(
    index: Int,
    id: String,
    allIds: List<String>,
    selection: RowSelection,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = Metrics.row,
    menuActions: List<ContextAction> = emptyList(),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = selection.contains(id)
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        animationSpec = tween(Metrics.HOVER_MS),
        label = "rowBg",
    )

    var menuOpen by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    // 双击判定：同一行 400ms 内两次左键释放
    var lastClickAt by remember { mutableStateOf(0L) }
    val density = LocalDensity.current

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Metrics.gapSmall))
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.first()
                            val secondary = event.buttons.isSecondaryPressed
                            val ctrl = event.keyboardModifiers.isCtrlPressed
                            val shift = event.keyboardModifiers.isShiftPressed
                            val position = change.position
                            // Press 即处理：右键菜单要跟手；左键的多选/播放等 Release 更稳妥，
                            // 但 PC 列表里没有拖拽语义，Press 直接判定也不会误触
                            if (secondary) {
                                if (menuActions.isNotEmpty()) {
                                    menuAt = position
                                    menuOpen = true
                                }
                            } else {
                                val now = System.currentTimeMillis()
                                val doubleClick = now - lastClickAt < 400L
                                lastClickAt = now
                                if (doubleClick) {
                                    onPlay()
                                } else {
                                    selection.click(index, id, allIds, ctrl, shift)
                                }
                            }
                            change.consume()
                        }
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            content = content,
        )

        if (menuOpen) {
            val offset = with(density) { DpOffset(menuAt.x.toDp(), menuAt.y.toDp()) }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = offset,
            ) {
                menuActions.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                action.label,
                                fontSize = Metrics.body,
                                color = if (action.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                        },
                        enabled = action.enabled,
                        onClick = {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

/** 列头的一个格子。[key] 非空表示可点排序。 */
data class PcColumn(val label: String, val weight: Float, val key: String? = null)

/**
 * 可排序的列头（搜索页）。
 *
 * 排序在页面内完成（结果是内存列表），不升到状态层：
 * 换关键词会重新取数，排序偏好只对当前列表有意义。
 */
@Composable
fun PcColumnHeader(
    columns: List<PcColumn>,
    sortKey: String?,
    ascending: Boolean,
    onSort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Metrics.header)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
    ) {
        columns.forEach { column ->
            val active = column.key != null && column.key == sortKey
            Row(
                Modifier
                    .weight(column.weight)
                    .then(
                        if (column.key != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onSort(column.key) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = column.label,
                    fontSize = Metrics.caption,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(2.dp))
                    // 升序时箭头朝上，降序朝下：向下箭头转 180°
                    AppIconView(
                        AppIcon.ChevronDown,
                        MaterialTheme.colorScheme.primary,
                        size = Metrics.iconSmall,
                        modifier = Modifier.rotate(if (ascending) 180f else 0f),
                    )
                }
            }
        }
    }
}

/** 行内序号：等宽数字，右对齐，避免两位数把标题挤歪。 */
@Composable
fun RowScope.PcIndex(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = Metrics.number,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.width(22.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/** 行内主文案（标题）：13sp 单行省略。 */
@Composable
fun RowScope.PcTitle(text: String, weight: Float = 1.4f, current: Boolean = false) {
    Text(
        text = text,
        fontSize = Metrics.body,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

/** 行内次文案（歌手 / 专辑）：11sp，颜色降一档。 */
@Composable
fun RowScope.PcCaption(text: String, weight: Float = 1f) {
    Text(
        text = text,
        fontSize = Metrics.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

/** 来源小标签：12sp 文字 + 极浅底，不用大胶囊。 */
@Composable
fun SourceTag(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        fontSize = Metrics.tag,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 音源胶囊行：横向可滚，但 PC 上一般一屏放得下 13 个平台。 */
@Composable
fun PcProviderChips(state: MusicUiState, store: MusicStore, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
    ) {
        state.providers.forEach { provider ->
            PcChip(
                label = provider.name,
                selected = state.providerId == provider.id,
                onClick = { store.selectProvider(provider.id) },
            )
        }
    }
}

/** 紧凑动作按钮（播放全部 / 刷新等）：26dp 高，图标 + 11sp 文案。 */
@Composable
fun PcActionButton(
    label: String,
    icon: AppIcon? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (hovered && !primary) MaterialTheme.colorScheme.surfaceContainerHighest else container)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gapSmall),
    ) {
        if (icon != null) AppIconView(icon, content, size = Metrics.iconSmall)
        Text(label, fontSize = Metrics.caption, color = content, maxLines = 1)
    }
}
