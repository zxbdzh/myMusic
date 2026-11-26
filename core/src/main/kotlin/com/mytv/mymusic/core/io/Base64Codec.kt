package com.mytv.mymusic.core.io

/**
 * 标准 Base64（RFC 4648，带 `=` 补齐）。
 *
 * 原来这些地方用的是 `android.util.Base64`，搬进 :core 后没有它了：
 * - 播放上报的请求签名（NetEase / Qianqian / Soda）；
 * - QQ 扫码登录的 `data:image/png;base64,` 内联二维码；
 * - 汽水签名页的响应解析。
 *
 * 与 `Base64.NO_WRAP` 的编码输出完全一致（无换行）；解码按 `Base64.DEFAULT` 的习惯
 * 忽略换行与空白、遇到非法字符返回 null，调用方按“解不开”处理而不是拿到半截字节。
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /** 编码；[wrap] 为 true 时每 76 字符换行（MIME 风格），默认无换行。 */
    fun encode(bytes: ByteArray, wrap: Boolean = false): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4 + 8)
        var index = 0
        var lineLength = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            out.append(ALPHABET[b0 shr 2])
            val second = ((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)
            out.append(ALPHABET[second])
            out.append(
                when {
                    b1 < 0 -> '='
                    else -> ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)]
                }
            )
            out.append(
                when {
                    b2 < 0 -> '='
                    else -> ALPHABET[b2 and 0x3F]
                }
            )
            index += 3
            if (wrap) {
                lineLength += 4
                if (lineLength >= 76 && index < bytes.size) {
                    out.append('\n')
                    lineLength = 0
                }
            }
        }
        return out.toString()
    }

    /**
     * 解码；忽略换行与空白，遇到非法字符返回 null。
     *
     * 长度不是 4 的整数倍时按缺省补齐处理（`Base64.DEFAULT` 也接受这种输入）；
     * `=` 之后的内容一律忽略——补齐即结束，这是与 `Base64.DEFAULT` 一致的宽容度。
     */
    fun decode(text: String): ByteArray? {
        val cleaned = StringBuilder(text.length)
        for (char in text) {
            if (char == '\n' || char == '\r' || char == ' ' || char == '\t') continue
            cleaned.append(char)
        }
        if (cleaned.isEmpty()) return ByteArray(0)
        when (cleaned.length % 4) {
            0, 2, 3 -> Unit
            // 只有一个孤立的尾字符：凑不出任何字节
            else -> return null
        }
        val buffer = ByteArray(cleaned.length / 4 * 3 + 3)
        var out = 0
        var pending = 0
        var chars = 0
        for (char in cleaned) {
            if (char == '=') break
            val value = if (char.code < 128) DECODE_TABLE[char.code] else -1
            if (value < 0) return null
            pending = (pending shl 6) or value
            chars++
            if (chars == 4) {
                buffer[out++] = ((pending shr 16) and 0xFF).toByte()
                buffer[out++] = ((pending shr 8) and 0xFF).toByte()
                buffer[out++] = (pending and 0xFF).toByte()
                pending = 0
                chars = 0
            }
        }
        when (chars) {
            0 -> Unit
            2 -> buffer[out++] = ((pending shr 4) and 0xFF).toByte()
            3 -> {
                buffer[out++] = ((pending shr 10) and 0xFF).toByte()
                buffer[out++] = ((pending shr 2) and 0xFF).toByte()
            }
            else -> return null
        }
        return buffer.copyOf(out)
    }

    /** 文本按 UTF-8 编码后 Base64。 */
    fun encodeUtf8(text: String, wrap: Boolean = false): String = encode(text.toByteArray(Charsets.UTF_8), wrap)

    /** Base64 解成 UTF-8 文本；解不开返回 null。 */
    fun decodeUtf8(text: String): String? = decode(text)?.toString(Charsets.UTF_8)
}
