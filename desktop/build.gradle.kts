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
    }
}

dependencies {
    // currentOs 会按当前平台挑 skiko 的原生库；CI 在 Windows 上打 MSI
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation(project(":core"))
    implementation(project(":playback-core"))
    implementation(project(":playback-vlc"))

    // Compose Desktop 的主线程调度器：ui 层的协程都要回主线程更新状态
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.mytv.mymusic.desktop.MainKt"

        nativeDistributions {
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
            }
        }
    }
}
