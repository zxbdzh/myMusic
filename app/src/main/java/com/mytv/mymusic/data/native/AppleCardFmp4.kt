package com.mytv.mymusic.data.native

/**
 * Apple 卡密流的 fMP4 重写。
 *
 * 解密后播放器看到的必须是普通 MP4：init 里的加密描述要摘掉、moof 里的
 * senc/saiz/saio 要置空、trex 默认帧长要补上，否则 ExoPlayer/ffmpeg 会在
 * 第一段后停住或把明文再解一次。
 */
internal object AppleCardFmp4 {
    private data class Box(val type: String, val offset: Int, val size: Int, val header: Int) {
        val body get() = offset + header
        val end get() = offset + size
    }

    /** init 段只改一次：把两个 enca 描述改成 mp4a，并补总时长与 trex 帧长。 */
    fun rewriteInit(init: ByteArray, totalDuration: Long): ByteArray {
        val out = init.copyOf()
        val moov = find(out, 0, out.size, "moov") ?: return out
        find(out, moov, "trak")?.let { trak ->
            find(out, trak, "mdia")?.let { mdia ->
                find(out, mdia, "minf")?.let { minf ->
                    find(out, minf, "stbl")?.let { stbl ->
                        find(out, stbl, "stsd")?.let { stsd ->
                            rewriteSampleEntries(out, stsd)
                        }
                    }
                }
                patchDuration(out, find(out, mdia, "mdhd"), 16, 24, totalDuration)
            }
            patchDuration(out, find(out, trak, "tkhd"), 20, 28, totalDuration)
        }
        patchDuration(out, find(out, moov, "mvhd"), 16, 20, totalDuration)
        find(out, moov, "mvex")?.let { mvex ->
            find(out, mvex, "trex")?.let { trex ->
                // Apple 的 trex 默认帧长写成 0，播放器会算成 0 秒
                putU32(out, trex.body + 12, 1024)
                putU32(out, trex.body + 16, 0)
            }
            return insertMehd(out, moov, mvex, totalDuration)
        }
        return out
    }

    /**
     * 解密一个 byterange 分片。sample 数据原地替换，senc/saiz/saio 等长改成 free，
     * 这样 trun 的 data_offset 不需要重算。
     */
    fun decryptSegment(segment: ByteArray, decrypt: ((ByteArray) -> ByteArray)?): ByteArray {
        val out = segment.copyOf()
        val moof = boxes(out, 0, out.size).firstOrNull { it.type == "moof" } ?: return out
        val traf = find(out, moof, "traf") ?: return out
        var trun: Box? = null
        val encrypted = mutableListOf<Box>()
        for (child in boxes(out, traf.body, traf.end)) {
            when (child.type) {
                "trun" -> trun = child
                "senc", "saiz", "saio" -> encrypted += child
            }
        }
        val sizes = trun?.let { sampleSizes(out, it) } ?: IntArray(0)
        val mdat = boxes(out, moof.end, out.size).firstOrNull { it.type == "mdat" } ?: return out
        if (decrypt != null) {
            var position = mdat.body
            for (size in sizes) {
                val end = position + size
                if (end > out.size) break
                val plain = decrypt(out.copyOfRange(position, end))
                plain.copyInto(out, position, 0, minOf(plain.size, size))
                position = end
            }
        }
        for (box in encrypted) {
            "free".toByteArray(Charsets.ISO_8859_1).copyInto(out, box.offset + 4)
        }
        return out
    }

    private fun rewriteSampleEntries(out: ByteArray, stsd: Box) {
        val count = u32(out, stsd.body + 4)
        var position = stsd.body + 8
        repeat(count) {
            if (position + 8 > stsd.end) return
            val entry = boxAt(out, position) ?: return
            if (entry.type == "enca" || entry.type == "encv") {
                "mp4a".toByteArray(Charsets.ISO_8859_1).copyInto(out, entry.offset + 4)
                // AudioSampleEntry 固定 28 字节字段，子盒从 +28 开始
                for (child in boxes(out, entry.body + 28, entry.end)) {
                    if (child.type == "sinf" || child.type == "wave") {
                        "free".toByteArray(Charsets.ISO_8859_1).copyInto(out, child.offset + 4)
                    }
                }
            }
            position = entry.end
        }
    }

    private fun sampleSizes(data: ByteArray, trun: Box): IntArray {
        val flags = u32(data, trun.body) and 0xFFFFFF
        val count = u32(data, trun.body + 4)
        var position = trun.body + 8
        if (flags and 0x1 != 0) position += 4
        if (flags and 0x4 != 0) position += 4
        val sizes = IntArray(count)
        for (index in 0 until count) {
            if (flags and 0x100 != 0) position += 4
            if (flags and 0x200 != 0) {
                sizes[index] = u32(data, position)
                position += 4
            }
            if (flags and 0x400 != 0) position += 4
            if (flags and 0x800 != 0) position += 4
        }
        return sizes
    }

    private fun patchDuration(data: ByteArray, box: Box?, v0Offset: Int, v1Offset: Int, duration: Long) {
        if (box == null) return
        when (data[box.body].toInt()) {
            0 -> if (duration <= 0xFFFFFFFFL) putU32(data, box.body + v0Offset, duration.toInt())
            else -> putU64(data, box.body + v1Offset, duration)
        }
    }

    /** 在 mvex 末尾插 mehd，并把 mvex/moov 的 size 各加 16。 */
    private fun insertMehd(data: ByteArray, moov: Box, mvex: Box, duration: Long): ByteArray {
        val mehd = ByteArray(16)
        putU32(mehd, 0, 16)
        "mehd".toByteArray(Charsets.ISO_8859_1).copyInto(mehd, 4)
        putU32(mehd, 12, duration.toInt())
        val at = mvex.end
        val out = ByteArray(data.size + mehd.size)
        data.copyInto(out, 0, 0, at)
        mehd.copyInto(out, at)
        data.copyInto(out, at + mehd.size, at)
        putU32(out, moov.offset, moov.size + mehd.size)
        putU32(out, mvex.offset, mvex.size + mehd.size)
        return out
    }

    private fun boxAt(data: ByteArray, offset: Int): Box? {
        if (offset + 8 > data.size) return null
        var size = u32(data, offset)
        var header = 8
        if (size == 1) {
            if (offset + 16 > data.size) return null
            size = u64(data, offset + 8).toInt()
            header = 16
        } else if (size == 0) {
            size = data.size - offset
        }
        if (size < header || offset + size > data.size) return null
        val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
        return Box(type, offset, size, header)
    }

    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        var position = start
        while (position + 8 <= end) {
            val box = boxAt(data, position) ?: break
            out += box
            position = box.end
        }
        return out
    }

    private fun find(data: ByteArray, parent: Box, type: String): Box? =
        boxes(data, parent.body, parent.end).firstOrNull { it.type == type }

    private fun find(data: ByteArray, start: Int, end: Int, type: String): Box? =
        boxes(data, start, end).firstOrNull { it.type == type }

    private fun u32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun u64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (data[offset + index].toLong() and 0xFF)
        return value
    }

    private fun putU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun putU64(data: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) data[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}
