package com.mytv.mymusic.data.native

/**
 * [QqLoginChannel] 的 Android 实现：直接转给隐藏 WebView 的 [QQLoginPage]。
 *
 * 行为与搬迁前 QQProvider 直接调 QQLoginPage 完全一致——这里只是把静态调用包成一层接口，
 * 让 :core 的 QQProvider 不必依赖 WebView。
 */
internal object AndroidQqLoginChannel : QqLoginChannel {
    override suspend fun begin(pageUrl: String): Boolean = QQLoginPage.begin(pageUrl)

    override fun isAlive(): Boolean = QQLoginPage.isAlive()

    override suspend fun qrImageUrl(): String = QQLoginPage.qrImageUrl()

    override suspend fun qrImageDataUrl(): String = QQLoginPage.qrImageDataUrl()

    override suspend fun hasLoginCookie(): Boolean = QQLoginPage.hasLoginCookie()

    override suspend fun harvestCookies(urls: List<String>): String = QQLoginPage.harvestCookies(urls)

    override fun close() = QQLoginPage.close()
}
