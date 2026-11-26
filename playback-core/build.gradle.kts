import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :playback-core —— 播放层的"与播放器无关"那一半。
// 这里放的是能脱离 media3 / vlcj 单独跑的算法与状态机：节拍分析、Automix 规划、交叉淡入的
// 交接状态、响度归一化、播放时钟。Android 侧继续用 media3 实现引擎接口，桌面侧用 :playback-vlc。
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
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
