package com.mytv.mymusic.desktop.theme

/**
 * 系统深浅色。
 *
 * Windows 10/11 把用户选择写在注册表 `AppsUseLightTheme` 里（0 = 深色，1 = 浅色）；
 * 读失败（非 Windows、注册表被策略锁住、reg.exe 不在 PATH）时按深色处理——
 * 音乐播放器的封面和大图在深色底上观感更稳，也避免首次启动闪白。
 *
 * 只查一次并缓存：主题切换不频繁，监听注册表要额外线程和句柄，不值得。
 */
object SystemTheme {
    val isDark: Boolean by lazy { detect() }

    private fun detect(): Boolean = runCatching {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        // 输出形如：    AppsUseLightTheme    REG_DWORD    0x0
        val hex = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)
        hex?.toIntOrNull(16)?.let { it == 0 }
    }.getOrNull() ?: true
}
