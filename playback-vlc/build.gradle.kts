import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :playback-vlc —— libVLC 后端。
// 为什么是 libVLC 而不是 Java 自带的 javax.sound：它一次给齐 FLAC/APE/ALAC/DSD 解码、
// 均衡器、跨段无缝与"边播边取"的网络缓冲，正好对上现有 ExoPlayer 的能力面
// （见 docs/pc/ARCHITECTURE.md「播放后端」）。
// 运行期需要 VLC 3.0.x 的 libvlc.dll（安装包或随包分发），构建期只需要 vlcj + JNA 的 jar。
plugins {
    alias(libs.plugins.kotlin.jvm)
    // 只有这个模块需要命令行 runner：`./gradlew :playback-vlc:run --args="..."` 直接听声
    application
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

application {
    mainClass.set("com.mytv.mymusic.playback.vlc.cli.PlaybackCliKt")
}

// 验收日志里全是中文：控制台/重定向统一 UTF-8，否则 Windows 默认 GBK 会变乱码。
// JNA 在 JDK 21+ 上加载 native 库要走 --enable-native-access，否则每次启动刷 4 行警告。
val playbackJvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "--enable-native-access=ALL-UNNAMED",
)

tasks.named<JavaExec>("run") {
    jvmArgs(playbackJvmArgs)
}

dependencies {
    api(project(":playback-core"))
    // vlcj 4.x 对应 VLC 3.0.x；JNA 版本显式钉住，别被其它库拽到不兼容的 native 绑定
    implementation(libs.vlcj)
    implementation(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    jvmArgs(playbackJvmArgs)
    // 本机没把 VLC 装到默认路径时，用环境变量指出 libvlc.dll 所在目录；没装则相关用例跳过
    (System.getenv("MYMUSIC_VLC_HOME") ?: System.getProperty("mymusic.vlc.home").orEmpty())
        .takeIf { it.isNotBlank() }
        ?.let { systemProperty("mymusic.vlc.home", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
