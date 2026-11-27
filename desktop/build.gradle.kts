import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
// 注意：Gradle Kotlin DSL 里的 `java` 是 JavaPluginExtension，写 `java.net.URI` 会被解析成扩展成员，
// 所以 JDK 类型一律走 import
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

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
    // 图标直接用 Android 端那套 Material 图标，而不是自绘——
    // 两边图标一模一样才叫"只是适配 PC"（TopTab / 播放控制 / 空态图标都用它）
    implementation(compose.materialIconsExtended)

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

// ---------------------------------------------------------------------------
// 版本号（t8）
//
// jpackage 只认 1.1.0 这种纯数字段；CI 用 PC_VERSION=1.1.<run_number> 注入构建号。
// MSI 的升级判定依赖版本递增，同版本重装会被拒绝，所以发布任务必须带新版本号。
// 本地不设环境变量时固定 1.1.0，保证复现与文档一致。
// ---------------------------------------------------------------------------
val pcVersion: String = providers.environmentVariable("PC_VERSION").orElse("1.1.0").get()

// ---------------------------------------------------------------------------
// 随包 libVLC（t8）
//
// jpackage 的 app-image 只带 JRE，不带 libvlc；用户机器不该被迫自装 VLC。
// 这里在打包前把 VLC 发行包里的播放必需子集抽到 appResourcesRootDir：
//   libvlc.dll / libvlccore.dll / plugins/ / lua/
// 运行时由 VlcOptions 按 `compose.application.resources.dir/vlc` 发现（见 VlcAudioOutput）。
//
// 下载源可用 -PpcVlcZip=<本地 zip> 覆盖（内网/离线构建），默认走清华镜像；
// build/vlc-runtime 已存在时整个任务跳过，重复打包不会重复解压 77 MB。
// ---------------------------------------------------------------------------
val vlcVersion: String = providers.gradleProperty("pcVlcVersion").orElse("3.0.21").get()
val vlcRuntimeDir = layout.buildDirectory.dir("vlc-runtime")

val fetchVlcRuntime by tasks.registering {
    group = "windows"
    description = "准备随包 libVLC 运行库（libvlc.dll + plugins/ + lua/）"
    outputs.dir(vlcRuntimeDir)
    doLast {
        val outDir = vlcRuntimeDir.get().asFile
        if (File(outDir, "libvlc.dll").isFile) {
            logger.lifecycle("[vlc] 运行库已就绪，跳过：${outDir.absolutePath}")
            return@doLast
        }
        // 本地 zip 优先（离线构建）；否则用缓存 zip，再否则从镜像下载
        val localZip = providers.gradleProperty("pcVlcZip").orNull?.let { file(it) }
        val cachedZip = layout.buildDirectory.file("vlc/vlc-$vlcVersion-win64.zip").get().asFile
        val zip = when {
            localZip != null && localZip.isFile -> localZip
            cachedZip.isFile && cachedZip.length() > 10_000_000L -> cachedZip
            else -> {
                val mirrors = listOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/videolan-ftp/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip",
                    "https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip",
                )
                cachedZip.parentFile.mkdirs()
                var lastError: Exception? = null
                for (url in mirrors) {
                    try {
                        logger.lifecycle("[vlc] 下载 $url")
                        URI(url).toURL().openStream().use { input ->
                            cachedZip.outputStream().use { input.copyTo(it) }
                        }
                        if (cachedZip.length() > 10_000_000L) break
                    } catch (error: Exception) {
                        lastError = error
                        logger.warn("[vlc] 镜像失败：$url（${error.message}）")
                    }
                }
                if (cachedZip.length() <= 10_000_000L) {
                    throw GradleException(
                        "libVLC 运行库下载失败（${lastError?.message}）。" +
                            "可手工下载 vlc-$vlcVersion-win64.zip 后加 -PpcVlcZip=<路径> 重跑。",
                    )
                }
                cachedZip
            }
        }
        logger.lifecycle("[vlc] 解压播放子集：${zip.absolutePath}")
        outDir.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // 发行包里所有文件都在 vlc-<version>/ 顶层目录下，去掉这一层
                val rel = entry.name.substringAfter("vlc-$vlcVersion/", entry.name)
                val keep = rel == "libvlc.dll" || rel == "libvlccore.dll" ||
                    rel.startsWith("plugins/") || rel.startsWith("lua/")
                if (keep && !entry.isDirectory) {
                    val target = File(outDir, rel.replace('/', File.separatorChar))
                    target.parentFile.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val dll = File(outDir, "libvlc.dll")
        if (!dll.isFile) throw GradleException("解压后没有 libvlc.dll：${outDir.absolutePath}")
        logger.lifecycle("[vlc] 完成：${outDir.absolutePath}（${File(outDir, "plugins").listFiles()?.size ?: 0} 个插件目录）")
    }
}

val stageVlcRuntime by tasks.registering(Copy::class) {
    group = "windows"
    description = "把 libVLC 运行库放进 jpackage 的 app 资源目录"
    from(vlcRuntimeDir)
    into(layout.buildDirectory.dir("windowsAppResources/vlc"))
    dependsOn(fetchVlcRuntime)
}

// ---------------------------------------------------------------------------
// 构建信息与校验和（t8）
//
// 产物版本、JDK、libVLC 版本一起写进 build/pc-build-info.txt；发布时把
// exe / msi / app-image 的 SHA-256 落到 build/pc-checksums.txt，同时打印到日志。
// CI 会再算一遍并作为 artifact 上传，文档里记录的就是这两份值。
// ---------------------------------------------------------------------------
val pcBuildInfo by tasks.registering {
    group = "windows"
    description = "记录本次打包的版本 / JDK / libVLC 信息"
    val info = layout.buildDirectory.file("pc-build-info.txt")
    outputs.file(info)
    doLast {
        val text = buildString {
            appendLine("appVersion=$pcVersion")
            appendLine("jdk=${System.getProperty("java.version")} (${System.getProperty("java.home")})")
            appendLine("libVlc=$vlcVersion")
            appendLine("builtAt=${OffsetDateTime.now()}")
        }
        info.get().asFile.writeText(text)
        logger.lifecycle("[pack] 构建信息：\n$text")
    }
}

val pcChecksums by tasks.registering {
    group = "windows"
    description = "计算安装包与 app-image 的 SHA-256（build/pc-checksums.txt）"
    val binaries = layout.buildDirectory.dir("compose/binaries/main")
    val out = layout.buildDirectory.file("pc-checksums.txt")
    outputs.file(out)
    doLast {
        val root = binaries.get().asFile
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val lines = mutableListOf<String>()
        listOf("exe", "msi").forEach { folder ->
            val dir = File(root, folder)
            dir.listFiles { f -> f.isFile }?.sortedBy { it.name }?.forEach { file ->
                lines += "${sha256(file)}  ${file.relativeTo(root).invariantSeparatorsPath}"
            }
        }
        // app-image 是目录，算逐文件哈希没有意义；给它一个 zip 的哈希（CI 打包上传时用）
        val zip = File(root, "app-image.zip")
        if (zip.isFile) lines += "${sha256(zip)}  app-image.zip"
        if (lines.isEmpty()) throw GradleException("没有找到安装包产物：${root.absolutePath}")
        val text = lines.joinToString("\n", postfix = "\n")
        out.get().asFile.writeText(text)
        logger.lifecycle("[pack] SHA-256：\n$text")
    }
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
            packageVersion = pcVersion
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

// 打安装包前必须先有桥与 libVLC；缺 .NET SDK / 下载源不可达时任务会跳过或报明确原因
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach {
        dependsOn(stageSmtcBridge)
        dependsOn(stageVlcRuntime)
        dependsOn(pcBuildInfo)
    }

// 校验和必须等产物落地：only the package tasks 会产生 exe/msi/app-image
tasks.named("pcChecksums") {
    dependsOn(tasks.matching { it.name in setOf("packageExe", "packageMsi", "createDistributable") })
}
