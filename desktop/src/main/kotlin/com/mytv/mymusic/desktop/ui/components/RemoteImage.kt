package com.mytv.mymusic.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 封面下载与缓存（B2：真实封面图）。
 *
 * 为什么不引 Coil：桌面只需要"给一个 URL，拿一张位图"，而 Coil 会把 okhttp / 解码器 / 缓存策略
 * 整包带进来（还要配 CMP 的 image loader）。这里直接用 `HttpURLConnection` + Skia 解码，
 * 一共百来行，行为完全可控，也方便截图模式在渲染前同步预热（Coil 的异步管线做不到这一点）。
 *
 * 三个设计点：
 *  1. **有界 LRU**：封面是"列表里一眼扫过"的东西，缓存上限 240 张，超了就淘汰最久未用的；
 *  2. **同一 URL 只下一次**：`inflight` 表把并发请求合并，列表滚动时不会打出十几条重复请求；
 *  3. **失败静默**：拿不到图就回落到程序化封面（`CoverArt`），不留破图占位。
 */
object RemoteImages {

    private const val MAX_CACHED = 240
    private const val TIMEOUT_MS = 6_000
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > MAX_CACHED
        },
    )

    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ImageBitmap?>>()

    fun cached(url: String): ImageBitmap? = if (url.isBlank()) null else cache[url]

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        return coroutineScope {
            val job = inflight.computeIfAbsent(url) {
                async(Dispatchers.IO) { fetch(url) }
            }
            try {
                job.await()?.also { cache[url] = it }
            } finally {
                inflight.remove(url, job)
            }
        }
    }

    /**
     * 截图模式专用：渲染前一帧就要有图，所以先在 IO 上并发拉一批。
     * 限制并发（默认 8）避免一次把几百个请求打到 CDN 上。
     */
    suspend fun preload(urls: Collection<String>, concurrency: Int = 8) {
        val pending = urls.filter { it.isNotBlank() && cache[it] == null }.distinct()
        if (pending.isEmpty()) return
        pending.chunked(concurrency.coerceAtLeast(1)).forEach { chunk ->
            coroutineScope {
                chunk.map { url -> async(Dispatchers.IO) { load(url) } }.awaitAll()
            }
        }
    }

    private fun fetch(url: String): ImageBitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            // 网易/QQ 的图床会看 Referer，给个通用的平台域名，缺了会 403
            setRequestProperty("Referer", "https://music.163.com/")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return@runCatching null
            // Skia 直接解码：PNG / JPEG / WebP 都吃，不必区分格式
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** 给截图模式用：最多等 [timeoutMs] 让封面落地，超时就先出图（文字证据仍然成立）。 */
    suspend fun preloadWithin(urls: Collection<String>, timeoutMs: Long = 12_000L) {
        withTimeoutOrNull(timeoutMs) { preload(urls) }
    }
}

/**
 * 列表里的封面：有真实 URL 就显示网络图，没有 / 还没加载好就退回落日渐变 + 首字。
 * 外层的圆角、尺寸统一由调用方给，这样播放页大封面和 32dp 小图标共用同一份实现。
 */
@Composable
fun CoverImage(
    imageUrl: String,
    seedKey: String,
    label: String,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
) {
    val cached = remember(imageUrl) { RemoteImages.cached(imageUrl) }
    var bitmap by remember(imageUrl) { mutableStateOf(cached) }
    LaunchedEffect(imageUrl) {
        if (bitmap == null && imageUrl.isNotBlank()) bitmap = RemoteImages.load(imageUrl)
    }
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(androidx.compose.ui.graphics.Color.Transparent)) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 没图时用程序化封面顶着，不留灰块：列表在等图的时候也应该有"颜色"
            Box(Modifier.fillMaxSize()) {
                ProceduralCover(seedKey = seedKey, label = label, corner = corner)
            }
        }
    }
}

/** 纯程序化封面（原 `CoverArt` 的绘制部分抽出来，供有 URL 时兜底复用）。 */
@Composable
private fun ProceduralCover(seedKey: String, label: String, corner: Dp) {
    CoverArt(seedKey = seedKey, label = label, modifier = Modifier.fillMaxSize(), corner = corner, imageUrl = "")
}
