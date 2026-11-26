package com.mytv.mymusic.desktop.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 外观快照：桌面版只保留和窗口有关的那几项。
 *
 * 对照 Android 的 `ui/theme/Appearance.kt`：那边的 `LayoutMode`、`FontScale`、`BlurMode`
 * 都是为手机 / 电视准备的；桌面窗口尺寸由用户直接拖，不需要布局模式，字号交给系统 DPI，
 * 背景模糊由 Skia 自己处理。所以这里只留「明暗 / 配色风格 / 种子色 / 纯黑 / 对比度 / 动效」。
 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val palette: PaletteKind = PaletteKind.TonalSpot,
    val seed: Color = BrandSeed,
    val amoled: Boolean = false,
    /** 与 Material 3 的 contrastLevel 同义：-1 更柔和，1 更分明，0 是基线。 */
    val contrast: Float = 0f,
    val reduceMotion: Boolean = false,
) {
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }
}

enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/** 配色风格：标签和 Android 端一致，取值映射到 material-kolor 的 PaletteStyle。 */
enum class PaletteKind(val label: String) {
    TonalSpot("柔和"),
    Vibrant("鲜明"),
    Expressive("表现力"),
    Fidelity("保真"),
}

/** 品牌松绿：和 Android `BrandSeed` 同一个色值，封面取色失败时的兜底。 */
val BrandSeed = Color(0xFF006B5F)

/** 自选色板，与 Android `CustomSeedSwatches` 保持同一组颜色。 */
val SeedSwatches: List<Color> = listOf(
    Color(0xFF006B5F),
    Color(0xFF1F6F78),
    Color(0xFF3A5BA0),
    Color(0xFF4B3F9E),
    Color(0xFF7D4BA0),
    Color(0xFFD9668F),
    Color(0xFFB8322A),
    Color(0xFFE8833A),
    Color(0xFFB08B47),
    Color(0xFF5E7D3A),
)

/**
 * 播放页共用当前种子色生成深色配色（同 Android 的 `PlayerTheme`）：
 * 播放页铺满整窗，用高彩度配色才压得住大封面。
 */
const val PLAYER_CONTRAST_BOOST: Float = 0.2f
