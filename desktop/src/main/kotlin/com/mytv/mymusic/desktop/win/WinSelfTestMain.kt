package com.mytv.mymusic.desktop.win

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

/**
 * `--win-selftest` 的宿主入口（独立 `main`）。
 *
 * 为什么不放在 `Main.kt` 里：`Main.kt` 属于 UI 组，Windows 集成只在 `win` 包里落地。
 * 这里提供一个可被 `JavaExec` 直接调起的入口，Gradle 侧只需要：
 *
 * ```kotlin
 * tasks.register<JavaExec>("runWinSelfTest") {
 *     dependsOn(tasks.named("classes"))
 *     classpath = sourceSets["main"].runtimeClasspath
 *     mainClass.set("com.mytv.mymusic.desktop.win.WinSelfTestMainKt")
 *     args("build/win-selftest.json")
 * }
 * ```
 *
 * 用法：`./gradlew :desktop:runWinSelfTest`（或 `java -cp ... com.mytv.mymusic.desktop.win.WinSelfTestMainKt`）。
 */
fun main(args: Array<String>) = application {
    val reportPath = args.firstOrNull { !it.startsWith('-') }
        ?: File(System.getProperty("user.dir"), "build/win-selftest.json").path
    WinSelfTestHost(reportPath, ::exitApplication)
}

/**
 * 自检必须在**真实窗口**里跑：任务栏缩略图按钮、悬浮窗"不抢焦点"、`WM_COMMAND`
 * 分发都要求有 HWND 和消息泵。窗口内容只是一块提示牌，真正的工作在 [WinSelfTest]。
 */
@Composable
private fun WinSelfTestHost(reportPath: String, onFinished: () -> Unit) {
    val reportFile = remember { File(reportPath) }

    Window(onCloseRequest = onFinished, title = WinSelfTest.WINDOW_TITLE) {
        LaunchedEffect(Unit) {
            val report = WinSelfTest.execute(window)
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(report.reportJson, Charsets.UTF_8)
            println("win-selftest: ${report.summaryLine()}")
            println("win-selftest: 报告写入 ${reportFile.absolutePath}")
            onFinished()
        }
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                MaterialTheme { Text("Windows 集成自检运行中…（完成会自动退出）") }
            }
        }
    }
}
