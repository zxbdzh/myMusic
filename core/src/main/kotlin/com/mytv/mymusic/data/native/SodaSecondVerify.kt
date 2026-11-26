package com.mytv.mymusic.data.native

/**
 * 汽水二次验证（`error_code=2046`）的桥。
 *
 * 官方验证组件要真实浏览器环境，Android 上由 `SodaQrSigner` 开可见 WebView 跑；
 * 桌面端没有等价物（ADR 里约定桌面不做二次验证），直接返回 false 让上层按「已取消」收尾。
 *
 * 网关要把本会话 jar 里的 cookie 先种进 WebView，所以把 cookie 表交给平台实现，
 * 由平台决定怎么种（Android 走 `CookieManager`）。
 */
object SodaSecondVerify {
    /**
     * 参数：本会话的 cookie（name -> value）、decisionJson、generalParamsJson。
     * 返回用户是否完成验证；未安装时为 null，网关按 false 处理。
     */
    @Volatile
    var bridge: (suspend (cookies: Map<String, String>, decisionJson: String, generalParamsJson: String) -> Boolean)? = null
}
