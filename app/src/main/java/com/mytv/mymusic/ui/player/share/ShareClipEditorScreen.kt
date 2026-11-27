package com.mytv.mymusic.ui.player.share

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.mymusic.data.share.shareClipRange
import kotlin.math.abs

/**
 * 片段编辑页：时间轴 + 双端手柄 + 范围时间。
 *
 * 范围默认由选句推出（首句前、末句后各留 1 秒），拖手柄微调；歌词没有时间戳时整块不可用，
 * 只说明原因，不自动改成整曲。
 *
 * 试听不在这一页：它要独立的播放器实例（不打断正在播放的歌），单独一片做。
 */
@Composable
internal fun ShareClipEditorScreen(
    session: ShareSession,
    durationMs: Long,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val duration = durationMs.coerceAtLeast(1L)
    val available = session.synced && session.selected.isNotEmpty()
    var trackWidth by remember { mutableFloatStateOf(1f) }

    fun resetFromSelection() {
        val range = shareClipRange(
            starts = session.lines.map { it.timeMs },
            selected = session.selected,
            durationMs = duration,
            reliableTiming = session.synced,
        ) ?: return
        session.clipStartMs = range.startMs
        session.clipEndMs = range.endMs
    }

    // 进页面时按选句算一次范围；回选句层改过选择再进来会重新算
    LaunchedEffect(session.selected, session.synced) {
        if (available) resetFromSelection()
    }

    val startX = session.clipStartMs.toFloat() / duration * trackWidth
    val endX = session.clipEndMs.toFloat() / duration * trackWidth
    val handleWidth = with(density) { 12.dp.toPx() }
    val segments = remember(session.lines, session.selected, trackWidth, duration) {
        session.lines.filter { it.order in session.selected }.map { line ->
            val index = session.lines.indexOf(line)
            val next = session.lines.getOrNull(index + 1)?.timeMs ?: duration
            (line.timeMs.toFloat() / duration * trackWidth) to (next.toFloat() / duration * trackWidth)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, "取消调整", tint = scheme.onSurface)
            }
            Text("调整片段", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${fmtMs(session.clipStartMs)} – ${fmtMs(session.clipEndMs)}",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "· ${fmtMs((session.clipEndMs - session.clipStartMs).coerceAtLeast(0L))}",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
                .drawBehind {
                    if (trackWidth <= 1f) return@drawBehind
                    val trackHeight = 26.dp.toPx()
                    val top = (size.height - trackHeight) / 2
                    val radius = CornerRadius(9.dp.toPx())
                    drawRoundRect(scheme.surfaceContainerHighest, Offset(0f, top), Size(size.width, trackHeight), radius)
                    segments.forEach { (from, to) ->
                        drawRoundRect(
                            scheme.primary.copy(alpha = 0.55f),
                            Offset(from, top + 3.dp.toPx()),
                            Size((to - from).coerceAtLeast(2f), trackHeight - 6.dp.toPx()),
                            CornerRadius(6.dp.toPx()),
                        )
                    }
                    if (!available) return@drawBehind
                    val start = session.clipStartMs.toFloat() / duration * size.width
                    val end = session.clipEndMs.toFloat() / duration * size.width
                    drawRoundRect(
                        scheme.primary.copy(alpha = 0.14f),
                        Offset(start, top - 8.dp.toPx()),
                        Size((end - start).coerceAtLeast(handleWidth), trackHeight + 16.dp.toPx()),
                        CornerRadius(12.dp.toPx()),
                    )
                    listOf(start, end).forEach { x ->
                        drawRoundRect(
                            scheme.primary,
                            Offset(x - handleWidth / 2, top - 10.dp.toPx()),
                            Size(handleWidth, trackHeight + 20.dp.toPx()),
                            CornerRadius(6.dp.toPx()),
                        )
                    }
                }
                .pointerInput(available, trackWidth, duration) {
                    if (!available) return@pointerInput
                    var draggingStart = true
                    detectDragGestures(
                        onDragStart = { offset ->
                            val start = session.clipStartMs.toFloat() / duration * trackWidth
                            val end = session.clipEndMs.toFloat() / duration * trackWidth
                            draggingStart = abs(offset.x - start) <= abs(offset.x - end)
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val deltaMs = (drag.x / trackWidth * duration).toLong()
                            if (draggingStart) {
                                session.clipStartMs = (session.clipStartMs + deltaMs)
                                    .coerceIn(0L, (session.clipEndMs - MIN_CLIP_MS).coerceAtLeast(0L))
                            } else {
                                session.clipEndMs = (session.clipEndMs + deltaMs)
                                    .coerceIn((session.clipStartMs + MIN_CLIP_MS).coerceAtMost(duration), duration)
                            }
                        },
                    )
                },
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
            Text(fmtMs(duration), fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
        }

        if (!available) {
            Spacer(Modifier.height(14.dp))
            Text(
                "这首歌词没有时间轴，只能整曲分享。",
                fontSize = 12.5.sp,
                color = scheme.error,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                "拖两端手柄改范围；粉色段是选中的句子，区间内没选的歌词声音会保留。",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    session.onlineMode = com.mytv.mymusic.data.share.ShareMode.Clip
                    onConfirm()
                },
                enabled = available && session.clipEndMs > session.clipStartMs,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text("确认范围")
            }
        }
    }
}

/** 片段的起止时间：分:秒（超过一小时也够用，分享不会那么长）。 */
private fun fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 片段最短 1 秒：再短就没有分享的意义，也会被服务端判无效。 */
private const val MIN_CLIP_MS = 1_000L

/** 手柄颜色（保留给以后做播放头时复用）。 */
@Suppress("unused")
private val ClipHandleColor = Color.Unspecified
