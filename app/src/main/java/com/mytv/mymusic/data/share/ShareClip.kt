package com.mytv.mymusic.data.share

/** 音频片段在整首歌内的起止时间（毫秒）。 */
internal data class ShareClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * 由选句推出片段范围：最早一句往前留 [leadMs]、最晚一句往后留 [tailMs]，夹在歌曲长度内。
 *
 * 末句的结束时间取下一句起点，没有下一句就取歌曲长度——区间内未选中的句子声音保留，
 * 所以这里只关心首尾，不按选句切段。
 *
 * 返回 null 表示「这个片段不可用」：歌词没有时间戳、没有选中句、行号越界、时长不成立。
 * 调用方必须据此禁用片段模式并说明原因，不能自动改成整曲。
 */
internal fun shareClipRange(
    starts: List<Long>,
    selected: Set<Int>,
    durationMs: Long,
    reliableTiming: Boolean,
    leadMs: Long = 1_000L,
    tailMs: Long = 1_000L,
): ShareClipRange? {
    if (!reliableTiming || durationMs <= 0L || selected.isEmpty()) return null
    val indexes = selected.sorted()
    val first = indexes.first()
    val last = indexes.last()
    if (first < 0 || last >= starts.size) return null

    val firstStart = starts[first]
    val lastStart = starts[last]
    // 时间必须落在歌曲内，否则这条歌词的时间轴不可信
    if (firstStart < 0L || firstStart >= durationMs || lastStart < 0L || lastStart >= durationMs) return null

    val start = (firstStart - leadMs).coerceAtLeast(0L)
    val lastEnd = starts.getOrNull(last + 1) ?: durationMs
    val end = (lastEnd + tailMs).coerceAtMost(durationMs)
    if (end <= start) return null
    return ShareClipRange(startMs = start, endMs = end)
}
