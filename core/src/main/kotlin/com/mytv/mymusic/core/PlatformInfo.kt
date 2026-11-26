package com.mytv.mymusic.core

/**
 * 平台事实的注入点。
 *
 * 播放上报要往请求里塞系统版本（网易的 `osver`、汽水的 `os_version`…），Android 上取
 * `android.os.Build.VERSION.RELEASE`，桌面端取 `System.getProperty("os.version")`。
 * 这段取值搬进 :core 后不能直接引用 android.os.Build，所以由平台层在启动时填一次。
 *
 * 默认空串：与搬迁前单测里的行为一致——`runCatching { android.os.Build... }` 在 JVM 上
 * 本来也取不到值，上报字段就是空的。
 */
object PlatformInfo {
    /** 系统版本字符串，例如 `14`（Android）或 `10.0`（Windows）。 */
    @Volatile
    var osRelease: String = ""

    /** 平台名，上报里区分设备用；Android 侧填 `android`。 */
    @Volatile
    var osName: String = "jvm"
}
