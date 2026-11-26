package com.mytv.mymusic

import android.os.Build
import com.mytv.mymusic.data.native.NativeProviders

/**
 * :core 的 [AppLog] 在 Android 侧的适配层。
 *
 * 搬进 :core 之后 AppLog 不能再直接引用 `android.util.Log` / `android.os.Build`，
 * 这里补上两件事：把环形缓冲的写出口接到 logcat，以及采集导出用的设备信息。
 * 行为与搬迁前一致——同样的 tag 白名单、同样的头部格式。
 */
object AppLogAndroid {
    /**
     * 把 [AppLog.platformLogger] 接到 logcat。幂等，Application.onCreate 里调一次即可。
     * 必须早于任何 `AppLog.d/i/w/e` 调用，否则那几条只在内存里。
     */
    fun install() {
        AppLog.platformLogger = { level, tag, message ->
            val priority = when (level) {
                "E" -> android.util.Log.ERROR
                "W" -> android.util.Log.WARN
                "I" -> android.util.Log.INFO
                else -> android.util.Log.DEBUG
            }
            android.util.Log.println(priority, tag, message)
        }
    }

    /** 收集一次设备和版本信息，导出时放在最前面。启动时采一次就够。 */
    fun captureHeader(context: android.content.Context) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                "${it.versionName}(${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode})"
            }
        }.getOrDefault("unknown")
        AppLog.header = buildString {
            append("myMusic $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append(" · ${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE}")
            append(" · ABI ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            append(" · providers ${NativeProviders.infos().joinToString(",") { it.id }}")
        }
    }
}
