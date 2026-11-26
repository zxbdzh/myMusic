package com.mytv.mymusic.desktop.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.material3.rememberDynamicColorScheme

/** 当前生效的外观快照，播放页 / 海报墙这类"整窗深色"的界面读它生成自己的配色。 */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** 当前种子色：播放页用它生成 Vibrant 深色配色，和主界面同一个色源。 */
val LocalSeedColor = staticCompositionLocalOf { BrandSeed }

/** 配色风格 → material-kolor 的调色板。名字和 Android 端一一对应，改一处两边一起变。 */
private fun PaletteKind.toStyle(): PaletteStyle = when (this) {
    PaletteKind.TonalSpot -> PaletteStyle.TonalSpot
    PaletteKind.Vibrant -> PaletteStyle.Vibrant
    PaletteKind.Expressive -> PaletteStyle.Expressive
    PaletteKind.Fidelity -> PaletteStyle.Fidelity
}

/**
 * 桌面版主题。
 *
 * 和 Android `ui/theme/Theme.kt` 的差别：
 *  1. 没有 Android 12 的系统壁纸取色，`ColorSource.Wallpaper` 这一档在桌面上不存在；
 *  2. 配色仍由 material-kolor 生成（同一个库、同一套 HCT 规则），保证跨端观感一致；
 *  3. **Expressive 主题在 CMP 里是 internal**：`MaterialExpressiveTheme` / `MotionScheme`
 *     在 material3-desktop 1.9.0 的 Kotlin 元数据里标了 internal（字节码有、源码不可见），
 *     所以这里退回 `MaterialTheme`，并把 expressive 的"手感"用 [ExpressiveMotion] 常量
 *     和 `ui/components/Expressive.kt` 里的自绘组件补齐——见那个文件的说明。
 */
@Composable
fun DesktopTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = appearance.isDark(SystemTheme.isDark)
    val scheme = rememberDynamicColorScheme(
        seedColor = appearance.seed,
        isDark = dark,
        isAmoled = appearance.amoled,
        style = appearance.palette.toStyle(),
        contrastLevel = appearance.contrast.toDouble(),
    )
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalSeedColor provides appearance.seed,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // 没有被 Surface 包着的文字默认跟 onSurface 走，深色主题下否则会是黑的
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                content = content,
            )
        }
    }
}

/**
 * 播放页主题：固定深色 + 更高彩度（Vibrant）。
 * 桌面播放页铺满整窗，大封面 + 歌词需要更强的色彩层次；主界面保持用户选的风格。
 */
@Composable
fun PlayerTheme(seed: Color, content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val scheme: ColorScheme = rememberDynamicColorScheme(
        seedColor = seed,
        isDark = true,
        isAmoled = false,
        style = PaletteStyle.Vibrant,
        contrastLevel = (appearance.contrast + PLAYER_CONTRAST_BOOST).coerceIn(-1f, 1f).toDouble(),
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}

/**
 * Expressive 的手感：M3 Expressive 用"多段弹簧 + 路径回弹"替代标准缓动，
 * 桌面这边没有 MotionScheme，就用同一组物理参数近似表达到处都用（导航指示器、播放按钮、
 * 播放条进度）。数值取自 M3 expressive 文档的 spatial/effects 两档。
 *
 * 刻意给成**具体的 Float 弹簧**而不是泛型函数：`animateFloatAsState` / `animateFloat` 的
 * 形参类型是 `AnimationSpec<Float>`，泛型版本在调用点会推导失败（CMP 里报 Cannot infer T）。
 */
object ExpressiveMotion {
    /** 空间变换（尺寸、位移）：低阻尼弹簧，收尾带一点点回弹。 */
    val spatialSpec: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 380f)

    /** 颜色 / 透明度：不要回弹，用标准缓动避免闪烁。 */
    val effects: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 快速响应（按下、抬起）。 */
    val press: Easing = FastOutSlowInEasing

    /** 默认的 effects 弹簧，给需要弹簧但不需要回弹的属性用。 */
    val settleSpec: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * 字号：沿用 M3 基线，桌面只需要把标题压紧一点。
 * 不断言字体族——Compose Desktop 走 Skia 的系统字体回退，中文由微软雅黑等系统字体接住；
 * 硬绑定字体名在缺字体的机器上会整屏变豆腐块。
 */
val AppTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Expressive 的圆角明显更大：卡片一档 20dp 起，胶囊直接给到全圆。 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
