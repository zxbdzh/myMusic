package com.mytv.mymusic.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘图标集（24×24 虚拟网格）。
 *
 * 不用 `material-icons-extended`：CMP 1.8 之后 JetBrains 已不再随 compose 分发那套扩展图标，
 * 而桌面骨架只用得到二十个符号；用 Canvas 原语（线、圆、三角、弧）画出来，
 * 既不多背 20MB 依赖，粗细也能和自绘的 expressive 控件（见 Expressive.kt）对齐。
 *
 * 全部为几何图形，不依赖任何字体或位图，缩放不会糊。
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

@Immutable
data class IconSpec(val icon: AppIcon, val label: String)

@Composable
fun AppIconView(
    icon: AppIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 1.7.dp,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        drawAppIcon(icon, tint, u, strokeWidth.toPx())
    }
}

private fun DrawScope.drawAppIcon(icon: AppIcon, tint: Color, u: Float, sw: Float) {
    // 24×24 网格 → 像素：o(x, y) 把网格坐标转成偏移量，方便直接抄几何图形
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw) =
        drawLine(tint, o(x1, y1), o(x2, y2), strokeWidth = width, cap = StrokeCap.Round)

    fun triangle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
        val path = Path().apply {
            moveTo(ax * u, ay * u)
            lineTo(bx * u, by * u)
            lineTo(cx * u, cy * u)
            close()
        }
        drawPath(path, tint)
    }

    fun roundBar(x: Float, y: Float, w: Float, h: Float, radius: Float = w / 2f) {
        drawRoundRect(
            color = tint,
            topLeft = o(x, y),
            size = Size(w * u, h * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * u, radius * u),
        )
    }

    when (icon) {
        // 榜单：三根高低不同的柱子，和 Android 的 Insights 图标同义
        AppIcon.Charts -> {
            roundBar(4f, 11f, 4f, 9f)
            roundBar(10f, 5f, 4f, 15f)
            roundBar(16f, 14f, 4f, 6f)
        }

        // 歌单：三条线 + 右下角一个音符
        AppIcon.Playlists -> {
            line(3.5f, 6f, 15f, 6f)
            line(3.5f, 11f, 15f, 11f)
            line(3.5f, 16f, 10f, 16f)
            drawCircle(tint, radius = 2.4f * u, center = o(17.5f, 16.2f))
            line(19.9f, 16.2f, 19.9f, 7f)
            line(19.9f, 7f, 22f, 7.8f, width = sw * 0.9f)
        }

        // 搜索：圆环 + 手柄
        AppIcon.Search -> {
            drawCircle(tint, radius = 5.6f * u, center = o(10.5f, 10.5f), style = Stroke(sw))
            line(14.8f, 14.8f, 19.5f, 19.5f, width = sw * 1.1f)
        }

        // 我的：唱片（圆环 + 中心点）叠在底板上
        AppIcon.Library -> {
            drawRoundRect(
                color = tint,
                topLeft = o(3f, 3.5f),
                size = Size(18f * u, 17f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * u, 4f * u),
                style = Stroke(sw),
            )
            drawCircle(tint, radius = 4.6f * u, center = o(12f, 12f), style = Stroke(sw))
            drawCircle(tint, radius = 1.5f * u, center = o(12f, 12f))
        }

        // 设置：圆环 + 八向刻度
        AppIcon.Settings -> {
            drawCircle(tint, radius = 6.4f * u, center = o(12f, 12f), style = Stroke(sw))
            drawCircle(tint, radius = 2.2f * u, center = o(12f, 12f))
            val ticks = listOf(
                0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f,
                0.71f to -0.71f, 0.71f to 0.71f, -0.71f to -0.71f, -0.71f to 0.71f,
            )
            ticks.forEach { (dx, dy) ->
                line(
                    12f + dx * 8.2f, 12f + dy * 8.2f,
                    12f + dx * 10.2f, 12f + dy * 10.2f,
                    width = sw * 1.15f,
                )
            }
        }

        AppIcon.Play -> triangle(7.5f, 4.5f, 7.5f, 19.5f, 19.5f, 12f)
        AppIcon.Pause -> {
            roundBar(6.5f, 4.5f, 4f, 15f)
            roundBar(13.5f, 4.5f, 4f, 15f)
        }
        AppIcon.Next -> {
            triangle(6f, 5f, 6f, 19f, 16f, 12f)
            roundBar(17f, 4.5f, 2.6f, 15f)
        }
        AppIcon.Previous -> {
            roundBar(4.4f, 4.5f, 2.6f, 15f)
            triangle(18f, 5f, 18f, 19f, 8f, 12f)
        }

        // 队列：四条线，尾两条短
        AppIcon.Queue -> {
            line(3.5f, 6.5f, 20.5f, 6.5f)
            line(3.5f, 11f, 20.5f, 11f)
            line(3.5f, 15.5f, 13f, 15.5f)
            line(3.5f, 20f, 13f, 20f)
        }

        // 音量：喇叭 + 两道弧
        AppIcon.Volume -> {
            val path = Path().apply {
                moveTo(3f * u, 9.5f * u)
                lineTo(7f * u, 9.5f * u)
                lineTo(11.5f * u, 5f * u)
                lineTo(11.5f * u, 19f * u)
                lineTo(7f * u, 14.5f * u)
                lineTo(3f * u, 14.5f * u)
                close()
            }
            drawPath(path, tint)
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = o(9.5f, 6.5f),
                size = Size(7f * u, 11f * u),
                style = Stroke(sw),
            )
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = o(10f, 3f),
                size = Size(12f * u, 18f * u),
                style = Stroke(sw),
            )
        }

        AppIcon.Heart, AppIcon.HeartOutline -> {
            val path = Path().apply {
                moveTo(12f * u, 20.2f * u)
                cubicTo(4.2f * u, 13.6f * u, 2.6f * u, 10.6f * u, 2.6f * u, 8.2f * u)
                cubicTo(2.6f * u, 5.4f * u, 4.8f * u, 3.4f * u, 7.4f * u, 3.4f * u)
                cubicTo(9.2f * u, 3.4f * u, 10.9f * u, 4.4f * u, 12f * u, 6f * u)
                cubicTo(13.1f * u, 4.4f * u, 14.8f * u, 3.4f * u, 16.6f * u, 3.4f * u)
                cubicTo(19.2f * u, 3.4f * u, 21.4f * u, 5.4f * u, 21.4f * u, 8.2f * u)
                cubicTo(21.4f * u, 10.6f * u, 19.8f * u, 13.6f * u, 12f * u, 20.2f * u)
                close()
            }
            if (icon == AppIcon.Heart) drawPath(path, tint) else drawPath(path, tint, style = Stroke(sw))
        }

        // 全屏：四角朝外；退出：四角朝内
        AppIcon.Fullscreen -> {
            line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f)
            line(20f, 9f, 20f, 4f); line(20f, 4f, 15f, 4f)
            line(4f, 15f, 4f, 20f); line(4f, 20f, 9f, 20f)
            line(20f, 15f, 20f, 20f); line(20f, 20f, 15f, 20f)
        }
        AppIcon.FullscreenExit -> {
            line(9f, 4f, 9f, 9f); line(9f, 9f, 4f, 9f)
            line(15f, 4f, 15f, 9f); line(15f, 9f, 20f, 9f)
            line(9f, 20f, 9f, 15f); line(9f, 15f, 4f, 15f)
            line(15f, 20f, 15f, 15f); line(15f, 15f, 20f, 15f)
        }

        AppIcon.ChevronDown -> {
            line(5.5f, 9f, 12f, 15.5f, width = sw * 1.2f)
            line(12f, 15.5f, 18.5f, 9f, width = sw * 1.2f)
        }

        AppIcon.Grid -> {
            listOf(3.5f to 3.5f, 13f to 3.5f, 3.5f to 13f, 13f to 13f).forEach { (x, y) ->
                drawRoundRect(
                    color = tint,
                    topLeft = o(x, y),
                    size = Size(7.5f * u, 7.5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                )
            }
        }

        AppIcon.More -> {
            listOf(5.5f, 12f, 18.5f).forEach { x ->
                drawCircle(tint, radius = 1.9f * u, center = o(x, 12f))
            }
        }

        AppIcon.Close -> {
            line(5.5f, 5.5f, 18.5f, 18.5f, width = sw * 1.2f)
            line(18.5f, 5.5f, 5.5f, 18.5f, width = sw * 1.2f)
        }

        AppIcon.Back -> {
            line(15f, 5f, 8.5f, 12f, width = sw * 1.2f)
            line(8.5f, 12f, 15f, 19f, width = sw * 1.2f)
        }

        AppIcon.Refresh -> {
            drawArc(
                color = tint,
                startAngle = 60f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = o(4f, 4f),
                size = Size(16f * u, 16f * u),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            triangle(17.4f, 2.6f, 17.4f, 8.6f, 21.4f, 5.6f)
        }

        // 音符：播放页占位封面、列表前缀
        AppIcon.Music -> {
            drawCircle(tint, radius = 3.4f * u, center = o(8.5f, 17.5f))
            line(11.9f, 17.5f, 11.9f, 5.5f, width = sw * 1.2f)
            line(11.9f, 5.5f, 19f, 7.2f, width = sw * 1.2f)
            drawCircle(tint, radius = 1.6f * u, center = o(19f, 8.6f))
        }
    }
}
