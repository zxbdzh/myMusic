package com.mytv.mymusic.data.native

/**
 * QQ 扫码登录的「隐藏 WebView」通道。
 *
 * QQ 的 ptqrlogin 只认浏览器发出的 script 请求，本机 HTTP 客户端一律 403，所以轮询必须交给
 * 官方登录页自己做（见 QQProvider.createQqLogin 的注释）。这套页面能力依赖 WebView / Activity / Looper，
 * 只能在 :app 实现；:core 里只留接口，由 [QqLoginChannels] 注入。
 *
 * 没安装时（桌面端、单测）行为与「WebView 起不来」完全一致：[begin] 返回 false，
 * `createQqLogin` 走原有的原生取码兜底。
 */
interface QqLoginChannel {
    /** 拉起登录页并等它首帧就绪；起不来返回 false。 */
    suspend fun begin(pageUrl: String): Boolean

    /** 页面还活着吗；Activity 重建后旧页面已废。 */
    fun isAlive(): Boolean

    /** 页面当前轮询到的那张二维码地址（ptqrshow 的 URL）。 */
    suspend fun qrImageUrl(): String

    /** 同一张二维码的 `data:image/png;base64,` 内联图。 */
    suspend fun qrImageDataUrl(): String

    /** 页面里是否已经拿到登录态 cookie。 */
    suspend fun hasLoginCookie(): Boolean

    /** 从页面 cookie 罐里捞出这些地址的 cookie 头。 */
    suspend fun harvestCookies(urls: List<String>): String

    fun close()
}

/** 平台层安装 [QqLoginChannel] 的入口；为空表示没有 WebView 通道。 */
object QqLoginChannels {
    @Volatile
    var current: QqLoginChannel? = null
}
