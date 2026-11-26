import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :playback-vlc —— libVLC 后端。
// 为什么是 libVLC 而不是 Java 自带的 javax.sound：它一次给齐 FLAC/APE/ALAC/DSD 解码、
// 均衡器、跨段无缝与"边播边取"的网络缓冲，正好对上现有 ExoPlayer 的能力面
// （见 docs/pc/ARCHITECTURE.md「播放后端」）。
// 运行期需要 VLC 3.0.x 的 libvlc.dll（安装包或随包分发），构建期只需要 vlcj + JNA 的 jar。
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":playback-core"))
    // vlcj 4.x 对应 VLC 3.0.x；JNA 版本显式钉住，别被其它库拽到不兼容的 native 绑定
    implementation(libs.vlcj)
    implementation(libs.jna)
}
