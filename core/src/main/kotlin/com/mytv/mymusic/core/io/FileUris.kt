package com.mytv.mymusic.core.io

import java.io.File

/**
 * `android.net.Uri.fromFile(file).toString()` 的 :core 等价物。
 *
 * 汽水把加密流解密到本地文件后要把地址交给播放器，原本用 `Uri.fromFile(...)`；
 * 那是 Android 专属类，搬进 :core 后换成这里。
 *
 * 规则与 Android 的实现保持一致：`file://` + 以 `/` 开头的绝对路径，路径按段做
 * 百分号编码（空格、中文等编成 UTF-8 的 %XX），`/` 本身保留。
 * 与 Android 的唯一差别是 Windows 盘符里的 `:` 不编码——`file:///C:/…` 才是有效的
 * 文件 URI，编成 `C%3A` 谁都打不开；Android 的路径里本来也不会出现 `:`。
 */
fun fileUri(file: File): String {
    // Windows 的绝对路径是反斜杠 + 盘符，统一成正斜杠再补前导 /
    val absolute = file.absolutePath.replace('\\', '/').let { if (it.startsWith("/")) it else "/$it" }
    return "file://" + absolute.split('/').joinToString("/") { encodePathSegment(it) }
}

/** 与 `Uri.encode` 的保留集一致：字母数字 + `-_.!~*'()`，另外放行 Windows 盘符的 `:`。 */
private fun encodePathSegment(segment: String): String {
    if (segment.isEmpty()) return segment
    val bytes = segment.toByteArray(Charsets.UTF_8)
    var needsEncoding = false
    for (byte in bytes) {
        val char = byte.toInt() and 0xFF
        val safe = (char in 'a'.code..'z'.code) || (char in 'A'.code..'Z'.code) || (char in '0'.code..'9'.code) ||
            char.toChar() in "-_.!~*'():" 
        if (!safe) {
            needsEncoding = true
            break
        }
    }
    if (!needsEncoding) return segment
    return buildString(bytes.size + 8) {
        for (byte in bytes) {
            val char = byte.toInt() and 0xFF
            val safe = (char in 'a'.code..'z'.code) || (char in 'A'.code..'Z'.code) || (char in '0'.code..'9'.code) ||
                char.toChar() in "-_.!~*'():"
            if (safe) {
                append(char.toChar())
            } else {
                append('%')
                append(HEX[char shr 4])
                append(HEX[char and 0x0F])
            }
        }
    }
}

private const val HEX = "0123456789ABCDEF"
