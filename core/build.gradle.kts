import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core —— PC 端与 Android 端共用的纯 Kotlin/JVM 层。
// 约束（见 docs/pc/ARCHITECTURE.md「模块边界」）：
//   1. 这里不许出现 android.* / androidx.*（除 androidx.annotation 这类纯编译期注解）；
//   2. 平台差异通过接口注入（PlatformPaths / SecureStore / LoginWebHost），不在这里分支判断；
//   3. 只要能被 JVM 单测直接跑，就算合格——这也是 195 个现有单测的复用前提。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    // Android 侧 minSdk 23 + desugar 吃 17 的字节码；桌面 JVM 也用 17，保持一份产物两边通吃
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 领域模型（Song / Playlist / ProviderInfo / QR*）都是 @Serializable，序列化运行时必须 api 出去
    api(libs.kotlinx.serialization.json)
    // NativeProvider 的接口几乎全是 suspend 函数
    api(libs.kotlinx.coroutines.core)
    // 各平台取流 / 搜索全部走 OkHttp，属下游可见类型，不做 implementation 隐藏
    api(libs.okhttp)
    // 局域网歌单面板 / 同步配网面板 / 电视输入面板各自起一个 NanoHTTPD 服务
    implementation(libs.nanohttpd)
    // 注：Room 还没有进 :core —— 实体与 DAO 仍留在 :app 的 AppStore，
    // 搬迁步骤与依赖写法见 docs/pc/ROOM-SPIKE.md §5（room-runtime + sqlite-bundled，**不要** room-ktx）。

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // WebDAV 同步位置的测试用它模拟服务器（PROPFIND、条件写入、412）
    testImplementation(libs.okhttp.mockwebserver)
    // 证书钉住的测试在测试里现生成自签名证书和根证书
    testImplementation(libs.okhttp.tls)
}
