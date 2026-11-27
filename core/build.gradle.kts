import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core —— PC 端与 Android 端共用的纯 Kotlin/JVM 层。
// 约束（见 docs/pc/ARCHITECTURE.md「模块边界」）：
//   1. 这里不许出现 android.* / androidx.*（除 androidx.annotation 这类纯编译期注解）；
//   2. 平台差异通过接口注入（PlatformPaths / SecureStore / LoginWebHost），不在这里分支判断；
//   3. 只要能被 JVM 单测直接跑，就算合格——这也是 195 个现有单测的复用前提。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Room 的 KSP 处理器在 JVM 目标上生成 AppDatabase_Impl（schema 与迁移都在这边）
    alias(libs.plugins.ksp)
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
    // Room：schema / 实体 / DAO / 迁移都在 :core，Android 与桌面共用一份声明。
    // 注意：**不要**引 room-ktx —— 那是 Android-only 的 AAR，纯 JVM 消费者解析不到变体（ROOM-SPIKE 坑①）。
    api(libs.androidx.room.runtime)
    // 纯 JVM 侧的 SQLite 驱动：桌面用自带 SQLite，不依赖系统库
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
    // 设置项：Preferences / edit / 各类 Key 都是 :core 可用的（Android 的 preferencesDataStore 委托留在 :app）
    api(libs.androidx.datastore.preferences.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // WebDAV 同步位置的测试用它模拟服务器（PROPFIND、条件写入、412）
    testImplementation(libs.okhttp.mockwebserver)
    // 证书钉住的测试在测试里现生成自签名证书和根证书
    testImplementation(libs.okhttp.tls)
}

// 从这一版起导出 schema：后续 AutoMigration / 迁移校验才有依据（app 侧原本是 exportSchema=false）
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}
