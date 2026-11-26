pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "myMusic"
include(":app")
include(":baselineprofile")
include(":lib-decoder-flac")
include(":lib-runtime-lx")

// PC 端（Windows 桌面）分层：纯 JVM 的 core 与播放层，Android 侧不受影响。
// 设计与边界见 docs/pc/ARCHITECTURE.md；这四个模块不参与 :app 的构建图。
include(":core")           // 纯 Kotlin/JVM：领域模型、平台接口（登录 / 取流 / 歌词）与 HTTP 客户端
include(":playback-core")  // 纯 Kotlin/JVM：播放器无关的队列、时钟、混音分析与歌词时间轴
include(":playback-vlc")   // JVM：libVLC（vlcj + JNA）实现的播放后端
include(":desktop")        // Compose Multiplatform Desktop：Windows 桌面壳与 UI
