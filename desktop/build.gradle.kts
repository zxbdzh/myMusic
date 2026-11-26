import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// :desktop —— Compose Multiplatform Desktop 的 Windows 桌面壳。
// 只做窗口与进程生命周期，业务全部在 :core / :playback-core / :playback-vlc。
// 打包：`./gradlew :desktop:packageMsi` 产出 MSI，`runDistributable` 本地直跑。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    // Kotlin 2.x 的 compose 编译器插件必须单独应用，CMP 自己不再带编译器
    alias(libs.plugins.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Material 3 Expressive 在 CMP 里仍是实验 API：ExpressiveTheme / MotionScheme.expressive()
        // 与 LargeFlexibleTopAppBar 之类都用它标注，统一在模块级打开，省得每个文件写一遍
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    // currentOs 会按当前平台挑 skiko 的原生库；CI 在 Windows 上打 MSI
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // 配色生成沿用 Android 端的 material-kolor（KMP 工程，jvm 变体在 Maven Central 上，
    // 依赖的 compose 版本正好是 1.12.2，和本模块一致）。用它而不是自己写 HCT，
    // 是为了让「同一个种子色在手机和 PC 上生成同一套 M3 配色」成为事实而不是近似。
    implementation(libs.material.kolor)

    implementation(project(":core"))
    implementation(project(":playback-core"))
    implementation(project(":playback-vlc"))

    // Compose Desktop 的主线程调度器：ui 层的协程都要回主线程更新状态
    implementation(libs.kotlinx.coroutines.swing)

    // Windows 集成（托盘/全局热键/任务栏缩略图按钮/悬浮歌词窗）全部走 JNA 直调 user32 与 COM。
    // 这里只是 implementation：JNA 的类型不出现在 :desktop 的公开 API 上。
    implementation(libs.jna)
    // SMTC 桥的 stdin/stdout 协议用 JSON 行；:core 已经 api 出序列化运行时，这里显式声明
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Windows 集成自检（t5）需要真实窗口与消息泵，宿主在 win 包里；
// ui 这边只准备运行任务，二者的接缝就是这一个 mainClass。
tasks.register<JavaExec>("runWinSelfTest") {
    group = "windows"
    description = "Windows 集成自检（托盘/热键/任务栏按钮/SMTC/悬浮窗），结果写 build/win-selftest.json"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mytv.mymusic.desktop.win.WinSelfTestMainKt")
    args("build/win-selftest.json")
    // JDK 25 起 JNA 的 System.load 会打 Restricted method 警告，显式放行 native 访问
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ---------------------------------------------------------------------------
// SMTC 桥（desktop/native/smtc-bridge）
//
// Windows 媒体浮层只能通过 WinRT 的 SystemMediaTransportControls 写入，JVM 侧没有投影，
// 所以编译一个 .NET 小进程做桥，走 stdin/stdout 的 JSON 行通信（决策与实测见
// docs/pc/SMTC-SPIKE.md）。
//
// 构建机没装 .NET SDK 时不能让整个 :desktop 构建失败：桥是可选增强，
// 缺了只是没有媒体浮层，播放/托盘/热键全部照常工作。
// ---------------------------------------------------------------------------
val smtcBridgeDir = layout.projectDirectory.dir("native/smtc-bridge")
val smtcBridgeOut = layout.buildDirectory.dir("native/smtc")
val smtcBridgeExe = smtcBridgeOut.map { it.file("myMusic.SmtcBridge.exe") }

val dotnetAvailable: Boolean by lazy {
    try {
        val process = ProcessBuilder("dotnet", "--list-sdks").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}

val buildSmtcBridge by tasks.registering(Exec::class) {
    group = "windows"
    description = "编译 SMTC 桥（需要 .NET SDK；缺失时跳过）"
    onlyIf { dotnetAvailable && !smtcBridgeExe.get().asFile.exists() }
    workingDir = smtcBridgeDir.asFile
    commandLine(
        "dotnet", "publish", "-c", "Release", "-r", "win-x64",
        "-o", smtcBridgeOut.get().asFile.absolutePath,
    )
}

// 打包时把桥放进 jpackage 的 app 资源目录，运行期用 compose.application.resources.dir 找
val stageSmtcBridge by tasks.registering(Copy::class) {
    group = "windows"
    description = "把 SMTC 桥复制进 jpackage app 资源目录"
    onlyIf { smtcBridgeExe.get().asFile.exists() }
    from(smtcBridgeOut) { include("*.exe") }
    into(layout.buildDirectory.dir("windowsAppResources/smtc"))
    dependsOn(buildSmtcBridge)
}

compose.desktop {
    application {
        mainClass = "com.mytv.mymusic.desktop.MainKt"
        // JDK 25 起 JNA（托盘/热键/SMTC 桥）加载 native 会打 Restricted method 警告，
        // 打包与 run 都显式放行，避免用户以为出了问题
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")

        // 悬浮歌词窗、托盘、全局热键都是 Windows 专属实现，其它平台启动时自动降级；
        // appResourcesRootDir 在 CMP 1.12 属于 nativeDistributions（AbstractDistributions），
        // 放在 application 层会 Unresolved reference，所以挪到下面的 nativeDistributions 里。
        nativeDistributions {
            // 打包时由 stageSmtcBridge 填好 build/windowsAppResources/smtc
            appResourcesRootDir.set(layout.buildDirectory.dir("windowsAppResources"))
            // MSI 给常规安装，EXE 给免安装分发；两者都用 jpackage
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "myMusic"
            packageVersion = "1.1.0"
            description = "myMusic 桌面版"
            vendor = "myMusic"

            windows {
                menuGroup = "myMusic"
                upgradeUuid = "0c6f1a9a-6d8d-4bb6-b1f5-9a7d1f3d2c11"
                // 依赖本机 VLC（下一步随包分发 libvlc 后改为 false）
                dirChooser = true
                perUserInstall = true
                // 托盘/热键/任务栏按钮都要按 DPI 缩放取图标，声明 PerMonitorV2 避免模糊
                iconFile.set(project.file("icons/app.ico"))
            }
        }
    }
}

// 打安装包前必须先有桥；缺 .NET SDK 时 stageSmtcBridge 会跳过，安装包只是没有媒体浮层
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach { dependsOn(stageSmtcBridge) }
