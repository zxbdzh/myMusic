package com.mytv.mymusic

import android.content.Context
import android.webkit.CookieManager
import com.mytv.mymusic.core.PlatformInfo
import com.mytv.mymusic.data.native.AndroidQqLoginChannel
import com.mytv.mymusic.data.native.AppleProvider
import com.mytv.mymusic.data.native.ExtendedTrustHosts
import com.mytv.mymusic.data.native.MHubProvider
import com.mytv.mymusic.data.native.MHubTls
import com.mytv.mymusic.data.native.NativeProviders
import com.mytv.mymusic.data.native.QqLoginChannels
import com.mytv.mymusic.data.native.SodaQrSigner
import com.mytv.mymusic.data.native.SodaSecondVerify
import com.mytv.mymusic.data.native.SpotifyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * :core 平台缝（seam）的 Android 安装点。
 *
 * 核心层搬走之后，凡是原本直接引用 Android 能力的地方都变成了注入点；这里按
 * 「搬迁前的行为」逐个装回去，一次装完：
 *
 * | 注入点 | 搬迁前的实现 |
 * | --- | --- |
 * | [AppLog.platformLogger] | `android.util.Log.println`（targetLog 白名单） |
 * | [PlatformInfo] | `android.os.Build.VERSION.RELEASE` |
 * | [ExtendedTrustHosts] | `MHubTls.hosts` + `MHubTls.client` |
 * | [QqLoginChannels] | `QQLoginPage` 的隐藏 WebView |
 * | [SodaSecondVerify] | `SodaQrSigner` 的可见 WebView（先种 Cookie 再跑） |
 * | [NativeProviders.registerFactory] | 留在 :app 的 Apple / Spotify / MHub 三个平台实现 |
 *
 * **必须在第一次摸 [NativeProviders] 之前调用**：它的静态列表首次访问即定型。
 */
object AndroidPlatformSeams {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        AppLogAndroid.install()
        PlatformInfo.osRelease = android.os.Build.VERSION.RELEASE
        PlatformInfo.osName = "android"
        ExtendedTrustHosts.install(MHubTls.hosts, MHubTls.client)
        QqLoginChannels.current = AndroidQqLoginChannel
        installSodaSecondVerify()
        installProviderFactories()
    }

    /**
     * 汽水二次验证桥：先把网关会话的 cookie 种进全局 CookieManager（组件发的请求跑在
     * WebView 里，身份必须和轮询时一致），再交给 [SodaQrSigner] 的可见窗口。
     * 与搬迁前 `SodaQrHttpGateway.seedWebViewCookies()` 做的事逐条一致。
     */
    private fun installSodaSecondVerify() {
        SodaSecondVerify.bridge = { cookies, decisionJson, generalParamsJson ->
            seedWebViewCookies(cookies)
            SodaQrSigner.showSecondVerify(decisionJson, generalParamsJson)
        }
    }

    private suspend fun seedWebViewCookies(cookies: Map<String, String>) = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        cookies.forEach { (name, value) ->
            manager.setCookie("https://api.qishui.com/", "$name=$value")
        }
        manager.flush()
    }

    /**
     * 留在 :app 的三个内置平台实现。顺序无关——:core 的静态列表按固定位置取，
     * 这里只提供工厂。
     */
    private fun installProviderFactories() {
        NativeProviders.registerFactory("apple") { AppleProvider() }
        NativeProviders.registerFactory("spotify") { SpotifyProvider() }
        NativeProviders.registerFactory("mhub") { MHubProvider() }
    }
}
