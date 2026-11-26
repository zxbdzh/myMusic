# myMusic PC 端架构决策记录（ADR）

| 项目 | 内容 |
| --- | --- |
| 仓库 | `F:\github\myMusic`（Android 端 `:app`，包名 `com.mytv.mymusic`） |
| 目标产物 | Windows 桌面版（Kotlin + Compose Multiplatform Desktop，核心层 JVM 复用） |
| 决策人 | architect（t1） |
| 状态 | Accepted（骨架已落地：`settings.gradle.kts:25-28`） |
| 关联 | `CLAUDE.md`（构建环境）、`docs/`（Android 侧设计文档）、`.github/workflows/build.yml`（Android 打包，未改动） |

> 本文只做架构与边界决策，不含业务实现。所有结论都给出仓库内路径；路径相对仓库根，
> 行号为写作时的实际位置。

---

## 0. 结论摘要

1. **技术栈**：Android 端保持 Kotlin/Compose 不动，PC 端新增 4 个 Gradle 模块
   `:core`（纯 JVM 领域层）→ `:playback-core`（播放器无关的播放状态机）→
   `:playback-vlc`（libVLC 后端）→ `:desktop`（CMP Desktop 壳 + UI）。
2. **为什么不是 Electron/Tauri/WinUI**：本仓库有 378 个主源码文件里 **139 个 ui 文件本来就写着
   Compose**（`app/src/main/java/com/mytv/mymusic/ui/**`），另有 195 个 JVM 单测直接跑 Provider
   解析逻辑（`app/src/test/**`）。CMP Desktop 让这些 Kotlin 代码**编译期就能复用**，其余三条路线
   都要把同一份业务重写一遍。见 §1。
3. **播放后端**：ExoPlayer(media3) 只存在于 Android；桌面用 **libVLC**（vlcj + JNA）。
   两者公共部分（队列、节拍、Automix、交叉淡入状态）上移到 `:playback-core`。
   见 §2.4、§4.3。
4. **音源层是本次最大的确定性收益**：`data/native` 43 个文件里 **36 个零 `android.*` 依赖**
   （实测：仅 7 个 import 了 android 包），可原样搬进 `:core`；剩下 7 个是 URI/Base64/WebView/
   DRM 四类平台调用，逐个替换规则见 §5。
5. **UI 不能照搬**：164 个 ui 文件里 142 个直接 import `android.*`（折叠屏、TV、指纹手势、
   分享导出），要按 6 批重写，`MusicViewModel`（379 KB）必须先拆。见 §6。

---

## 1. 选型对比

### 1.1 候选对比

| 维度 | **CMP Desktop（选中）** | Electron | Tauri | .NET WinUI 3 |
| --- | --- | --- | --- | --- |
| 现有资产复用 | Kotlin 业务 + 139 个 Compose UI 文件可编译复用；Provider 层 36/43 直接搬 | 0（Kotlin 只能跑在 JVM 进程里做后端，UI 全重写 TS） | 0（同上，且 Rust 侧要重写 IPC） | 0（C# 重写全部，Android 侧 Kotlin 无法复用） |
| 单测资产 | 195 个 `app/src/test` 里纯 JVM 的（`data/native/**`、`data/webdav/**`、`data/transfer/**`）可原样跑 | 需另建 Node 测试栈 | 需另建 Rust 测试栈 | 需重写为 xUnit |
| 体积/内存 | JVM + skiko，安装包 ~80–120 MB（后续可 jlink 裁剪） | Chromium 内核 ~150 MB+，内存高 | 最小（系统 WebView），但 Windows 侧依赖 WebView2 运行时 | 依赖 .NET 运行时，中 |
| 播放能力 | libVLC 全格式（FLAC/APE/ALAC/DSD）+ 均衡器 + 无缝切歌 | 自带 `<audio>` 只吃常见编码，无损/APE 要额外原生模块 | 同 Electron（WebView） | Media Foundation 覆盖有限，APE/DSD 要另找解码器 |
| 歌词/可视化 | Compose Canvas/Shader 与 Android 同源，`player/stage/**` 的着色器与物理动画可移植 | 需把 Canvas 代码重译到 Web Canvas/WebGL | 同 | 需重译到 Win2D |
| 团队成本 | 一套 Kotlin、一套 Gradle、一套调试心智 | 两套语言 + 两套构建 | 两套语言 + Rust 学习曲线 | 一套 C#，但 Android 侧经验清零 |
| 关键风险 | CMP Desktop 生态比 Android Compose 小；jpackage 打包需处理 VLC 原生库 | 与 Android 代码零共享，长期双维护 | 同 Electron + Rust 成本 | 与现有工程几乎无交集 |

### 1.2 决策

**选 CMP Desktop**。判据不是"哪个框架更强"，而是本仓库的实际形态：

- UI 层已经是 Compose：`app/build.gradle.kts:91` 打开 `buildFeatures { compose = true }`，
  `ui/theme/Theme.kt`、`ui/components/**`（15 个）、`ui/adaptive/**`（4 个）都是纯 Compose，
  搬到 CMP 后差异集中在"窗口尺寸/焦点/鼠标手势"，不是重写渲染。
- 业务层本来就是纯 JVM：`data/native/NativeProvider.kt:42` 的接口签名只用
  `Song`/`Playlist`/`suspend`/`OkHttp`，没有 Android 类型（见 §5 实测）。
- 构建体系单一：`gradle/libs.versions.toml` + Gradle Wrapper（`gradle/wrapper/gradle-wrapper.properties:3`）
  已经能同时承载 Android 与 JVM 模块，不必引入 Node/Rust 工具链。

代价（写在这里以免后面反复讨论）：

- **安装包偏大**：JVM 运行时 + skiko（`compose.desktop.currentOs`）不可省；缓解手段是
  `:desktop:createDistributable` 后用 jlink 裁剪，见 §7.3。
- **启动时间**：冷启动 1–2 s 量级，比原生慢；用 `-XX:+UseSerialGC` + AOT CDS 缓解。
- **CMP 版本绑定**：CMP `1.12.2` 与本仓库 Kotlin `2.4.20`（`gradle/libs.versions.toml:3`）配套，
  升级 Kotlin 必须同步升 CMP，这条约束写进 §4.1。

---

## 2. 模块边界

### 2.1 模块图

```
:desktop  (Compose Multiplatform Desktop, jvm)
   │  只做窗口/导航/输入法/文件对话框
   ├──> :playback-vlc   (vlcj + JNA, jvm)
   │        └──> :playback-core (纯 Kotlin, jvm)
   │                 └──> :core  (纯 Kotlin, jvm：模型/Provider/HTTP/存储抽象)
   └──> :core

:app (Android)  ── 继续自带 data/playback/ui，逐步改为依赖 :core / :playback-core
```

依赖只允许**单向向下**：`:desktop` 不得被任何模块依赖；`:core` 不得依赖 `:playback-*`。

### 2.2 各模块职责

| 模块 | 现存对应物（证据） | 本模块只放什么 | 构建文件 |
| --- | --- | --- | --- |
| `:core` | `data/Models.kt`、`data/native/**`、`data/webdav/**`、`data/subsonic/**`、`data/transfer/**` | 领域模型、`NativeProvider` 契约、HTTP 客户端、平台接口（`PlatformPaths`）、存储抽象 | `core/build.gradle.kts` |
| `:playback-core` | `playback/BeatTracker.kt`、`playback/AutomixAnalysis.kt`、`playback/AutomixPlanner.kt`、`playback/RhythmAnalysis.kt`、`playback/VolumeNormalization.kt`、`playback/CrossfadeStatus.kt`、`playback/PlaybackClock.kt` | 播放状态机 + 队列 + 分析算法（不含 media3 / vlcj 类型） | `playback-core/build.gradle.kts` |
| `:playback-vlc` | `playback/PlaybackService.kt`（ExoPlayer 侧） | `PlaybackEngine` 的 libVLC 实现、设备/均衡器、native 库发现 | `playback-vlc/build.gradle.kts` |
| `:desktop` | `MainActivity.kt`、`ui/AppNavigation.kt`、`MyMusicApp.kt` | `main()`、窗口、桌面导航、全局快捷键、托盘 | `desktop/build.gradle.kts` |

### 2.3 禁止清单（硬约束）

以下规则可以对 `:core` / `:playback-core` 的 `compileKotlin` 做静态校验（后续接 detekt 规则）：

1. `:core` 与 `:playback-core` **禁止** `import android.*`、`import androidx.media3.*`。
   现状里必须迁出这些依赖的点：`data/native/MHubTls.kt:3-6`（`android.net.Uri` + media3 DataSource）、
   `data/native/QQLoginPage.kt:3-10`（WebView）、`data/native/SodaQrSigner.kt`、`data/native/SodaQrHttpGateway.kt:3`。
2. `:core` **禁止**持有 `Context`；一切目录访问走 `core/src/main/kotlin/com/mytv/mymusic/core/PlatformPaths.kt`。
   现存反例：`data/native/AppleProvider.kt:1134`（`fun attach(context: Context) { dir = context.cacheDir }`）。
3. `:playback-core` **禁止**出现播放器类型；`:playback-vlc` **禁止**出现 Compose 类型。
4. `:desktop` **禁止**直接 `import com.mytv.mymusic.data.native.<具体平台>`——只能通过
   `NativeProviders` 注册表（`data/native/NativeProviders.kt:7-25`）拿 Provider。
5. JVM target 统一 17：Android 侧 `app/build.gradle.kts:76-84` 已锁 17，桌面侧
   `core/build.gradle.kts:15-21` 同样是 17，避免出现"只能在桌面跑"的字节码。

### 2.4 播放后端边界

`playback-core/src/main/kotlin/com/mytv/mymusic/playback/core/PlaybackEngine.kt` 是唯一播放契约：

- Android 实现：现有 `playback/PlaybackService.kt:54`（`MediaSessionService` + `ExoPlayer`）继续保留，
  逐步把状态改写成 `PlaybackSnapshot`；
- 桌面实现：`playback-vlc/src/main/kotlin/com/mytv/mymusic/playback/vlc/VlcPlaybackEngine.kt`（骨架，
  `MediaPlayerFactory` 延迟构造，避免"打开设置页就要求装 VLC"）；
- 算法侧：`BeatTracker`/`Automix*`/`Rhythm*`/`VolumeNormalization` 等 11 个文件不含 media3
  （实测 `playback/**` 中 13 个文件依赖 media3，`BeatTracker.kt`、`AutomixAnalysis.kt`、
  `AutomixPlanner.kt`、`RhythmAnalysis.kt`、`RhythmTimeline.kt`、`VolumeNormalization.kt` 等不依赖），
  先搬进 `:playback-core`，两侧共用。

---

## 3. 数据流

### 3.1 搜索 / 取流链路（现状，两端一致）

```
UI(ui/search/SearchScreen.kt)
  → MusicViewModel (ui/MusicViewModel.kt)
    → MusicRepository (data/MusicRepository.kt:17)
      → NativeProviders.get(id) (data/native/NativeProviders.kt:49)
        → NativeProvider.searchSongs / playableStream (data/native/NativeProvider.kt:46,66)
          → OkHttp (data/native/NativeHttp.kt) + 平台签名/解密 (data/native/NativeCrypto.kt)
```

关键点：`playableStream` 已经会返回**本地文件 URI**（加密流先落盘解密，见
`NativeProvider.kt:62-74` 注释与 `data/native/SodaDecrypt.kt`），桌面端不需要新增"下载再播"
的概念，直接把 `file:` URL 交给 VLC。

### 3.2 播放链路（Android vs 桌面）

| 步骤 | Android（现状） | 桌面（目标） |
| --- | --- | --- |
| 取流 | `MusicRepository.playableStream` → `PlayableStream.url` | 同（`:core`） |
| 交给播放器 | ExoPlayer `MediaItem` + `DefaultDataSource`（`PlaybackService.kt:16-26`） | vlcj `media().prepare(url, ":http-referrer=...")` |
| 状态 | `Player.Listener` 回调 | `MediaPlayerEventAdapter` 回调 → `PlaybackSnapshot` |
| 后台播放 | `MediaSessionService` + 通知 | 窗口/托盘常驻 + 全局快捷键（无 Android 通知概念） |
| 无缝切歌 | `CrossfadeController`（media3 AudioProcessor） | `:playback-core` 的交叠状态机 + VLC 双实例交叠 |
| 实时频谱 | `RhythmAudioSink`（TeeAudioProcessor，`playback/RhythmAudioSink.kt`） | 需要 VLC 音频回调（`libvlc_audio_set_callbacks`），列在 §8 风险 |

### 3.3 歌词 / 分享 / 同步

- 歌词：`ui/LyricsParser.kt`（7.7 KB，无 android import）+ `ui/LyricPipeline.kt` 可整体复用；
  `lyricon/**`（3 个文件）是 Android 歌词插件协议，桌面**跳过**。
- 分享图：`ui/player/share/**`（11 个文件）依赖 Android Canvas/Bitmap 导出，
  桌面改用 Compose 的 `ImageComposeScene` 渲染到 `BufferedImage`（§6 批次 B4）。
- 同步：`data/webdav/**`（7 个文件）与 `data/sync/**`（13 个文件）基于 OkHttp + 自写 XML 解析，
  可直接搬 `:core`；其中 2 个（`WebDavDataSource.kt` 等）依赖 media3 DataSource，需按 §5 规则替换。

---

## 4. 依赖清单

### 4.1 直接复用（版本已在 `gradle/libs.versions.toml`）

| 依赖 | 版本 | 用途 | 现状证据 | PC 处置 |
| --- | --- | --- | --- | --- |
| Kotlin | 2.4.20 | 语言/编译器 | `libs.versions.toml:3` | 复用 |
| kotlinx-serialization-json | 1.11.0 | 模型序列化（`@Serializable`） | `data/Models.kt` | 复用（`:core` api） |
| kotlinx-coroutines | 1.10.2 | 全层 suspend | `data/native/NativeHttp.kt` | 复用；桌面用 `-swing` 调度器 |
| OkHttp | 5.5.0 | 所有 Provider 取流 | `data/native/NativeHttp.kt`、`libs.versions.toml:25` | 复用 |
| Coil 3 | 3.6.3 | 封面加载 | `app/build.gradle.kts:136-137` | 复用（`coil-compose` 有桌面产物） |
| JNA | 5.19.1 | vlcj 的 native 底座 | 新增 `libs.versions.toml` | 新增（`:playback-vlc`） |
| Compose Multiplatform | 1.12.2 | 桌面 UI | 新增（`:desktop`） | 新增 |

### 4.2 需要换实现（不是换思路）

| 依赖 | 现状 | PC 方案 | 判定依据 |
| --- | --- | --- | --- |
| **Room** | `androidx.room:room-*:2.8.5` + KSP，实体在 `data/AppStore.kt:936-1025`（`saved_songs`/`downloads`/`automix_analysis`/`playlists`/`sync_accounts`） | **Room KMP**：`room-runtime` 2.8.5 已是 KMP 产物（`.module` 含 jvm 变体，实测 `dl.google.com/.../room-runtime-2.8.5.module` 200），桌面用 `sqlite-bundled` 驱动；KSP 换成 `kspJvm` 目标 | Room 自 2.7 起多平台化；实体与 DAO 是纯 Kotlin，可直接搬 |
| **DataStore** | `androidx.datastore:datastore-preferences:1.2.1`，5 个文件：`data/AccountProfileStore.kt`、`data/AppStore.kt`、`data/ListenTaskStore.kt`、`data/SharePrefs.kt`、`data/SwipePrefs.kt` | 用 `datastore-preferences-core` 的 JVM 变体（同版本 1.2.1，`.module` 含 jvm）落到 `%APPDATA%\myMusic\*.preferences_pb` | Preferences DataStore 的 `DataStoreFactory` 只需 `File`，Android 侧只是多了 `Context` 糖 |
| **media3 / ExoPlayer** | `app/build.gradle.kts:125-134`，13 个 `playback/**` 文件 | 桌面换 **vlcj 4.12.1**（VLC 3.0.x）；`lib-decoder-flac`（`lib-decoder-flac/build.gradle.kts`，CMake + FLAC JNI）在桌面**不需要**，VLC 自带 FLAC | ExoPlayer 无 JVM 桌面版；VLC 覆盖 FLAC/APE/ALAC/DSD 全集 |
| **QuickJS（Android）** | `wang.harlon.quickjs:wrapper-android:2.4.0`（`libs.versions.toml:27`），入口 `data/lx/LxScriptEngine.kt:9-12`；中断补丁 `lib-runtime-lx/src/main/java/com/mytv/mymusic/lxruntime/LxQuickJsInterrupt.kt:9`（`System.loadLibrary("lx-runtime")`，CMake 见 `lib-runtime-lx/src/main/jni/CMakeLists.txt`） | **Javet 6.0.4**（V8，Windows x64 native 已发布）；接口面（`LxScriptEngine` 的 `evaluate/execute/callHandler/interrupt`）在 `:core` 抽成 `ScriptEngine` 接口 | `com.whl.quickjs` 只有 Android AAR：Maven Central 上 `wang.harlon.quickjs:wrapper-jvm` 返回 404（已实测），没有现成 JVM 产物；自建 JNI 绑定等于重写 `lib-runtime-lx` |
| **Javet / quickjs-java 备选** | — | 若包体敏感：自建 `quickjs-java`（把 `lib-runtime-lx` 的 C++ 交叉编译到 Windows），代价是要维护两份 native 构建 | 需要"脚本体积 <2 MB"时才值得，先不选 |
| **WebView（扫码登录）** | `data/native/QQLoginPage.kt`、`data/native/SodaQrSigner.kt`、`data/native/SodaQrHttpGateway.kt` | 桌面首选**系统浏览器 + 本地回环回调**；需要页内 JS 时才上 JCEF（Chromium Embedded Framework） | 现状是隐藏 WebView 拿 cookie（`SodaQrHttpGateway.kt:132-141` 的 `seedWebViewCookies`），桌面没有 WebView，必须换载体 |
| **Widevine DRM** | `playback/AppleWidevineDrm.kt`、`data/native/AppleProvider.kt:11,164-167` | 桌面**不提供 Widevine**：Apple 平台整曲降级为试听片段；要完整支持只能走 CEF + Widevine CDM | Windows 上 VLC/ExoPlayer 都没有可用 Widevine CDM |

### 4.3 明确不迁移（Android 专属，桌面跳过）

| 能力 | 文件 | 理由 |
| --- | --- | --- |
| 桌面小组件 | `widget/**` 20 个文件（Glance，`widget/NowPlayingWidget.kt:11-14`） | Glance 是 Android RemoteViews；桌面对应物是托盘/迷你窗，另写 |
| 悬浮歌词 | `overlay/FloatingLyricsService.kt`（`android.app.Service`） | 桌面改"置顶无边框窗口"，不共用代码 |
| 麦克风识别 | `recognize/MicRecorder.kt:3-5`（`AudioRecord`） | 桌面用 `javax.sound.sampled.TargetDataLine`，重写 1 个采集类即可 |
| 电视投屏/竖屏面板 | `ui/tv/**`、`ui/local/TvSyncSetup`、`ui/settings/TvInputPanelScreen.kt` | 电视端专属交互，PC 无对应场景 |

---

## 5. `data/native` 43 个文件逐个处置表

统计口径：目录 `app/src/main/java/com/mytv/mymusic/data/native/`，共 43 个 `.kt`。
"Android 依赖"列由 `^import android` 实测；`—` 表示零 Android 依赖（纯 `java.*`/Kotlin/OkHttp）。

### 5.1 汇总

| 处置 | 数量 | 含义 |
| --- | --- | --- |
| **复用** | 36 | 原样搬进 `:core`，只改包名前缀（保持 `com.mytv.mymusic.*`，实际零改动） |
| **替换** | 4 | 有 1–3 处 Android API 调用，换等价 JVM API |
| **桌面跳过 / 换载体** | 3 | WebView 扫码登录，桌面改用系统浏览器或 JCEF |

### 5.2 逐个表

| # | 文件 | 大小 | Android 依赖 | 处置 | 具体做法 / 依据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `AppleChartFeed.kt` | 3.0 KB | — | 复用 | 纯 JSON 解析，`kotlinx.serialization` |
| 2 | `AppleHlsManifest.kt` | 7.1 KB | — | 复用 | HLS manifest 解析，纯字符串处理 |
| 3 | `AppleHlsRewrite.kt` | 7.4 KB | — | 复用 | 播放列表改写，纯 Kotlin |
| 4 | `AppleProvider.kt` | 69.2 KB | `android.content.Context`、`android.net.Uri` | **替换** | ① `Uri.fromFile(file)`（:199、:251）→ `file.toURI().toString()`；② `attach(context)`（:1134-1135）→ `attach(paths: PlatformPaths)` 取 `cacheDir`；③ Widevine 回调（:11、:164-167）→ 桌面无 CDM，整曲降级为试听（§4.2） |
| 5 | `ArtistText.kt` | 5.5 KB | — | 复用 | 文本清洗 |
| 6 | `AudioQuality.kt` | 6.0 KB | — | 复用 | 音质档位枚举 |
| 7 | `BilibiliCdn.kt` | 3.2 KB | — | 复用 | CDN 选路，纯字符串 |
| 8 | `BilibiliProvider.kt` | 59.2 KB | — | 复用 | 只用 OkHttp + JSON |
| 9 | `BilibiliTitles.kt` | 9.0 KB | — | 复用 | 标题解析 |
| 10 | `FivesingProvider.kt` | 19.0 KB | — | 复用 | 纯 JVM |
| 11 | `JamendoProvider.kt` | 27.3 KB | — | 复用 | 纯 JVM |
| 12 | `JooxProvider.kt` | 57.8 KB | — | 复用 | 纯 JVM |
| 13 | `KugouProvider.kt` | 206.6 KB | — | 复用 | 最大单文件但零 Android 依赖（含自写签名） |
| 14 | `KugouWelfare.kt` | 16.2 KB | — | 复用 | 纯 JVM |
| 15 | `KuwoProvider.kt` | 104.5 KB | — | 复用 | 纯 JVM（`NativeCrypto` 的 RSA/AES） |
| 16 | `MHubProvider.kt` | 33.0 KB | — | 复用 | 纯 JVM |
| 17 | `MHubTls.kt` | 5.1 KB | `android.net.Uri`、`androidx.media3.datasource*` | **替换** | 信任策略本体（`SSLContext` + 自签信任管理器）保留；`Uri.isMhub()`（:116）→ `java.net.URI`；`OkHttpDataSource/DataSource/DataSpec`（:4-7）→ 桌面改成"构造 `OkHttpClient` 时挂 per-host `X509TrustManager`"，不再包 media3 DataSource（入口从 `PlaybackSource.headers` 走） |
| 18 | `MiguProvider.kt` | 71.3 KB | — | 复用 | 纯 JVM |
| 19 | `NativeCrypto.kt` | 6.0 KB | — | 复用 | `javax.crypto` + `java.util.Base64`，天然跨平台 |
| 20 | `NativeHttp.kt` | 10.1 KB | — | 复用 | OkHttp 请求封装，`suspendCancellableCoroutine` |
| 21 | `NativeProvider.kt` | 12.0 KB | — | 复用 | 契约本体（:42 接口），搬进 `:core` 后成为唯一平台契约 |
| 22 | `NativeProviders.kt` | 2.3 KB | — | 复用 | 16 个 Provider 的注册表（:8-25），桌面与 Android 共用 |
| 23 | `NetEaseProvider.kt` | 88.7 KB | — | 复用 | 纯 JVM |
| 24 | `PlaylistWrite.kt` | 0.8 KB | — | 复用 | 写入失败的枚举 |
| 25 | `QianqianProvider.kt` | 39.0 KB | — | 复用 | 纯 JVM |
| 26 | `QQLoginPage.kt` | 9.3 KB | `android.app.Activity`、`android.webkit.WebView*` | **桌面跳过** | 隐藏 WebView 扫码页 → 桌面用系统浏览器打开二维码链接 + 本地回环端口收回调（或 JCEF） |
| 27 | `QQProvider.kt` | 178.1 KB | — | 复用 | 纯 JVM（含加密歌单解析） |
| 28 | `SodaArtistApi.kt` | 9.2 KB | — | 复用 | 纯 JVM |
| 29 | `SodaCollection.kt` | 8.4 KB | — | 复用 | 纯 JVM |
| 30 | `SodaDecrypt.kt` | 14.1 KB | — | 复用 | 纯 JVM（AES 解密，输出 `file:` URI） |
| 31 | `SodaJson.kt` | 4.1 KB | — | 复用 | 纯 JVM |
| 32 | `SodaPlaylistEdit.kt` | 7.5 KB | — | 复用 | 纯 JVM |
| 33 | `SodaPlayReport.kt` | 13.9 KB | — | 复用 | 纯 JVM |
| 34 | `SodaProvider.kt` | 200.4 KB | `android.net.Uri` | **替换** | `Uri.fromFile(file).toString()`（:146、:2318、:2341）→ `file.toURI().toString()`；扫码登录里对 WebView 的引用（:224、:299）改为走统一 `LoginWebHost` 接口 |
| 35 | `SodaQrHttpGateway.kt` | 7.5 KB | `android.webkit.CookieManager` | **桌面跳过** | 纯 HTTP 通道可用，但 `seedWebViewCookies()`（:132-141）依赖 WebView cookie 注入；桌面改写为"自持 CookieJar + 系统浏览器回跳" |
| 36 | `SodaQrLogin.kt` | 23.0 KB | — | 复用 | 状态机，纯 JVM（`SecureRandom` + UUID） |
| 37 | `SodaQrSigner.kt` | 18.1 KB | `android.app.Activity`、`android.webkit.*` | **桌面跳过** | 隐藏 WebView 签名页（`@SuppressLint` + `Handler/Looper`）；桌面无对应物，登录走 35 的 HTTP 通道 |
| 38 | `SodaResumableDownload.kt` | 14.4 KB | — | 复用 | 纯 JVM（Range 断点续传） |
| 39 | `SodaWelfare.kt` | 13.0 KB | — | 复用 | 纯 JVM |
| 40 | `SongComments.kt` | 5.5 KB | — | 复用 | 纯 JVM |
| 41 | `SpotifyProvider.kt` | 91.7 KB | `android.util.Base64` | **替换** | :157 的 `Base64.encodeToString(..., NO_WRAP)` → `java.util.Base64.getEncoder().encodeToString(...)`；仅此 1 处 |
| 42 | `YouTubeMusicProvider.kt` | 27.9 KB | — | 复用 | 纯 JVM |
| 43 | `YuntingProvider.kt` | 14.0 KB | — | 复用 | 纯 JVM |

> 复核方式：`Get-ChildItem app/src/main/java/com/mytv/mymusic/data/native -Filter *.kt | Select-String -Pattern '^import\s+android'`
> 命中 7 个文件（#4、#17、#26、#34、#35、#37、#41），与上表一致。

---

## 6. `ui` 164 个文件分批重写计划

统计口径：`app/src/main/java/com/mytv/mymusic/ui/**` 共 164 个 `.kt`（实测），
其中 142 个 import `android.*`、139 个 import `androidx.compose.*`。
重写原则：**先搬能编译的，再改不能编译的**；每一批以"能启动、能点"为验收，不追求一次对齐全部细节。

### 6.1 分批总表

| 批次 | 主题 | 文件数 | 交付物 | 阻塞点 |
| --- | --- | --- | --- | --- |
| **B0** | 主题与基础组件 | 23 | `ui/theme`（4）+ `ui/components`（15）+ `ui/adaptive`（4）可编译；窗口尺寸替代 `WindowSizeClass` | 无（Compose 同源） |
| **B1** | 壳、导航、全局状态 | 11 | `AppNavigation`/`AppUi`/`MusicViewModel` 拆分后的桌面壳 | `MusicViewModel` 379 KB 必须先拆 |
| **B2** | 列表型页面 | 34 | 曲库/本地/歌单/榜单/歌手/搜索 | 鼠标右键菜单、拖拽排序 |
| **B3** | 播放页与舞台 | 63 | `player/**` 全部：控制 18 + 舞台 35（含 `stage/modes` 24 个可视化）+ 分享 10 | 音频回调、Bitmap 导出、Skia Shader 重写 |
| **B4** | 设置与导入 | 16 | `settings/**` 15 + `ui/tv/TvNavRail.kt` | 文件选择器、字体列表 |
| **B5** | 海报墙与导出 | 17 | `poster/**` | 大图渲染性能 |

合计 23+11+34+63+16+17 = **164**。

### 6.2 各批文件清单与改造要点

**B0 主题与基础组件（23）**

- `theme/`：`Appearance.kt`、`BlurPolicy.kt`、`CoverSeedRepository.kt`、`Theme.kt`
- `components/`：`Badges.kt`、`Buttons.kt`、`CollectionSearch.kt`、`DragSettle.kt`、`Focus.kt`、
  `Lists.kt`、`Progress.kt`、`ProviderIcon.kt`、`Scaffolds.kt`、`Selection.kt`、`SharedCover.kt`、
  `Sheets.kt`、`SwipeActions.kt`、`TintedGradient.kt`、`Window.kt`
- `adaptive/`：`FoldDialogs.kt`、`FoldSplit.kt`、`LargeScreen.kt`、`PhoneBottomBar.kt`

要点：`SwipeActions.kt`（45.7 KB）在桌面改用鼠标拖拽 + 滚动条；`haze`（毛玻璃）在 CMP 有对应实现
（`dev.chrisbanes.haze` 支持 Desktop），`Window.kt` 里的 `WindowInsets` 换成 `WindowState`
尺寸回调；`materialKolor` 的 `rememberDynamicColorScheme` 保留。

**B1 壳与全局状态（11）**

`AddToPlaylistSheet.kt`、`AppNavigation.kt`、`AppUi.kt`、`CollectionScreen.kt`、`CrashReportDialog.kt`、
`LyricPipeline.kt`、`LyricsParser.kt`、`MusicViewModel.kt`、`SourcePickerSheet.kt`、`UiStates.kt`、
`UpdateOverlay.kt`

要点：`AppNavigation.kt`（35.4 KB，`navigation-compose`）在桌面换成"左侧导航栏 + 内容区"
（`navigation-compose` 的 JVM 版本可用，但桌面更常见 `AnimatedContent` + 自持路由栈，见 §8 未决项）；
`MusicViewModel` 按"曲库/搜索/播放/设置/同步"拆成 5 个 ViewModel，否则桌面端 memo 化无法做；
`UpdateOverlay.kt` 的 APK 更新流程改为"下载 exe/msi + 提示重启"。

**B2 列表型页面（34）**

- `library/`（12）：`KugouWelfareScreen`、`LibraryCollections`、`LibraryDates`、`LibraryRoutes`、
  `LibraryScreen`、`LocalPlaylistScreen`、`PlatformImportSheet`、`SyncScreens`、`TransferSheets`、
  `TvSyncSetup`、`WebDavSyncForm`、`WelfareScreen`
- `local/`（7）：`LocalCloudScreen`、`LocalMusicCommon`、`LocalMusicRoutes`、`LocalMusicScreen`、
  `LocalTrackSheets`、`LocalUploadUi`、`LocalWebDavScreen`
- `playlists/`（2）：`PlaylistsFilters.kt`、`PlaylistsScreen.kt`
- `charts/`（4）：`ChartsHero`、`ChartsScreen`、`CommentsSheet`、`DiscoverRoutes`
- `artist/`（4）：`AlbumParts`、`ArtistPageModel`、`ArtistScreen`、`DetailKeys`
- `search/`（5）：`RecognitionSheet`、`SearchResultCaches`、`SearchRoutes`、`SearchScreen`、`VoiceSearch`

要点：`TvSyncSetup`/`WelfareScreen` 这类"电视/手机配对"页面在桌面降级为"扫码看本机地址"；
`LocalMusicScreen`（38.4 KB）依赖 MediaStore，桌面换 `java.nio.file.Files.walk` + 标签解析复用
`data/local/LocalTags.kt`；`VoiceSearch.kt` 在桌面**跳过**（无语音输入）。

**B3 播放页与舞台（63）**——按依赖序内部分三小步

1. **播放控制**（18，`ui/player/*.kt` 根目录）：`TransportControls`、`PlayerBottomChrome`、`PlayerParts`、
   `PlayerScreen`、`PlayerSheets`、`PhonePlayer`、`WidePlayer`、`TabletopPlayer`、`TvPlayer`、
   `PlayerFold`、`AmbientClock`、`BeatClock`、`CoverStage`、`CoverShapes`、`LyricsBackdrop`、
   `LyricFonts`、`FlowFieldMotion`、`RhythmDebugOverlay`
2. **舞台与可视化**（35 = `player/stage/**` 11 + `player/stage/modes/**` 24）：
   `LyricStage`、`LyricVisualizer`、`LyricVisualizers`、`LyricDirector`、`AnimationSheet`、`AnimationDraft`、
   `ColorPickerSheet`、`StageChoices/Device/Layers/Preview`；
   `modes/**` 的 24 个：`AuroraVisualizer`、`CineramaProgram/Scene/Visualizer`、`JumpCourse/Director/Geometry/Visualizer`、
   `MisideDropPhysics/Visualizer`、`Rings52HzLayout/Record/Shader/Visualizer`、
   `RushBackgrounds/LyricList/Palette/Syllable/Visualizer`、`StardriftSky/Visualizer`、
   `VinylDiscVisualizer`、`WaveLightVisualizer`、`WaveVinylKit`
3. **分享与导出**（10，`player/share/**`）：`ShareCards`、`ShareCardShapes`、`ShareExport`、`ShareFonts`、
   `ShareImages`、`ShareLyricsScreen`、`ShareModel`、`ShareSession`、`LyricPickGesture`、`LyricPickLayer`

要点：`modes/**` 依赖 `android.graphics` 的 Shader 与 `RenderEffect`，CMP 侧要用 `Skia`
（`org.jetbrains.skia.Shader`）重写——这是**全项目风险最高的一批**，建议先在桌面复刻 1 个模式
（`Rings52HzVisualizer`）验证性能再批量推进；`ShareExport` 用 `ImageComposeScene` 输出 PNG。

**B4 设置与导入（16）**

`settings/`：`AboutScreen`、`EqualizerScreen`、`LocalMusicSettingsScreen`、`LxSourcesSettings`、
`LxSourcesViewModel`、`LyriconSettings`（**跳过**，Android 歌词插件）、`LyricsSettingsScreen`、`QrCode`、
`SettingsCommon`、`SettingsRoutes`、`SettingsScreen`、`SettingsSubScreens`（108 KB，需拆）、
`SubsonicSettingsScreen`、`SwipeSettingsScreen`、`TvInputPanelScreen`（**跳过**）；加 `ui/tv/TvNavRail.kt`（**跳过**）

要点：`SwipeSettingsScreen` 的"滑动"语义在桌面改成"鼠标手势 + 快捷键"，配置项保留但文案改；
`QrCode.kt` 保留（登录仍用二维码）；文件选择用 `java.awt.FileDialog`。

**B5 海报墙与导出（17）**

`poster/**`：`CollectionPosterView`、`CoverSizes`、`GrownPoster`、`PosterLyrics`、`PosterPulse`、
`PosterSettingsSheet`、`PosterTile`、`PosterWall`、`PosterWallScreen`、`PosterWallState`、`WallCamera`、
`WallEntrance`、`WallFocus`、`WallFold`、`WallGeometry`、`WallReflowTable`、`WallZoom`

要点：`WallFold`（折叠屏铰链）在桌面换成"多显示器/窗口分栏"；`WallGeometry`/`WallReflowTable`
是纯数学，直接复用；导出走 `ImageComposeScene` 批量渲染，注意 4K 下的内存（§8）。

---

## 7. 构建与验收

### 7.1 已落地文件

| 文件 | 作用 |
| --- | --- |
| `settings.gradle.kts:25-28` | 新增 `:core`、`:playback-core`、`:playback-vlc`、`:desktop` |
| `build.gradle.kts:9-11` | 根工程声明 `kotlin-jvm`、`compose-multiplatform` 插件（`apply false`） |
| `gradle/libs.versions.toml` | 新增 `composeMultiplatform=1.12.2`、`vlcj=4.12.1`、`jna=5.19.1`、`javet=6.0.4` 及 `coroutines-core/-swing` 条目 |
| `core/build.gradle.kts` | 纯 JVM，jvmTarget 17，api 暴露 serialization/coroutines/okhttp |
| `core/src/main/kotlin/com/mytv/mymusic/core/CoreModule.kt` | 模块身份 + 契约版本 |
| `core/src/main/kotlin/com/mytv/mymusic/core/PlatformPaths.kt` | 目录抽象，替代 `Context.filesDir/cacheDir` |
| `playback-core/build.gradle.kts` + `PlaybackEngine.kt` | 引擎契约（`prepare/play/pause/seekTo/setVolume/release` + `PlaybackSnapshot`） |
| `playback-vlc/build.gradle.kts` + `VlcPlaybackEngine.kt` | vlcj/JNA 骨架，`MediaPlayerFactory` 延迟构造 |
| `desktop/build.gradle.kts` | CMP + `nativeDistributions(Msi, Exe)`，`packageName=myMusic`、`packageVersion=1.1.0` |
| `desktop/src/main/kotlin/com/mytv/mymusic/desktop/Main.kt` | 窗口骨架（只列模块，不含业务） |

### 7.2 验证命令

```powershell
# 环境（CLAUDE.md 的约定；本机 JAVA_HOME 需指到 JDK 根目录）
$env:JAVA_HOME='D:\env\.jdks\temurin-25.0.3'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"

./gradlew :core:compileKotlin          # 新增模块可编译
./gradlew :app:assembleDebug           # Android 侧未受影响
./gradlew test                         # 现有单测保持通过
./gradlew :desktop:run                 # 桌面骨架能起窗（需本机装 VLC 3.0.x 才有声）
./gradlew :desktop:packageMsi          # Windows 安装包
```

环境补充：本机 Android SDK 原先只有 API 35/36，首次构建时 AGP 自动补装了
`platforms/android-37.0`、`build-tools/37.0.0`、`ndk/27.0.12077973`（见 Gradle 日志），
之后构建不再下载。

### 7.2.1 本次实际验证结果（2026-11-26，Windows 11 / JDK 25 / Gradle 9.8.0）

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `./gradlew :core:compileKotlin` | ✅ `> Task :core:compileKotlin` + BUILD SUCCESSFUL | 新增模块可编译 |
| `./gradlew :app:assembleDebug` | ✅ BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/myMusic-1.1.apk`（49.4 MB） | Android 侧零回归 |
| `./gradlew :playback-vlc:compileKotlin :desktop:compileKotlin` | ✅ BUILD SUCCESSFUL | 证明 vlcj 4.12.1 + JNA 5.19.1 + CMP 1.12.2 在 Kotlin 2.4.20 下可编译（`desktop/build/classes/kotlin/main/.../MainKt.class` 已生成） |
| `./gradlew test` | ⚠️ 1724 用例，1723 通过 / 1 失败（`AppleHlsRewriteTest > relative segment and map uris are absolutized`） | **既有失败，与本任务无关**：把本次改动的三个构建文件 `git stash` 掉后单独重跑同一用例，仍在 `app/src/test/java/com/mytv/mymusic/AppleHlsRewriteTest.kt:96` 失败。根因是测试断言用 `split("\n")` + `endsWith(".mp4")`，而本机 `core.autocrlf=true` 且仓库没有 `.gitattributes`，三引号 fixture 带 `\r\n`，`endsWith` 恒为 false。Linux CI 上为 LF，不会触发。修复建议（另开任务）：加 `.gitattributes`（`* text=auto eol=lf`），或把断言里的 `split("\n")` 改成 `split("\r?\n")` |

### 7.3 打包

- `:desktop:packageMsi` 产出 `desktop/build/compose/binaries/main/msi/myMusic-1.1.0.msi`；
- VLC 运行库两种策略：①要求用户装 VLC 3.0.21（当前骨架的假设）；②把 `libvlc.dll` 与 `plugins/`
  打进 `nativeDistributions.appResourcesRootDir`，并用 `-Dvlcj.libvlc.path` 指定（推荐，后续任务）；
- 体积：jlink 裁剪到 `java.base,java.desktop,java.logging,java.naming,java.net.http,jdk.crypto.ec`
  可把运行时砍到 ~40 MB。

---

## 8. 风险与未决

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `modes/**` 21 个可视化依赖 Android Shader/RenderEffect | B3 可能做不完或性能不达标 | 先做 1 个（`Rings52HzVisualizer`）做性能基线；不达标就退回"静态歌词 + 少量动画" |
| 实时频谱要 VLC 音频回调（`RhythmAudioSink` 是 media3 的 TeeAudioProcessor） | 节拍/律动类功能在桌面可能失步 | vlcj 暴露 `libvlc_audio_set_callbacks`，用 `AudioCallbackAdapter` 取 PCM；不行则用"离线分析 + 预计算波形"（`BeatTracker` 已能离线跑） |
| Widevine 不可用 | Apple Music 只能试听 | 明确标注"该平台桌面端仅试听"，或在后续任务评估 CEF+CDM |
| CMP 与 Kotlin 版本强绑定 | 升级 Kotlin 需同步升 CMP | 在 `libs.versions.toml` 注释里写死这层关系（已写） |
| `MusicViewModel` 379 KB | 桌面 Compose 的重组性能无法定位 | B1 先拆成 5 个 ViewModel，再谈 UI 细节 |
| 未决：桌面导航用 `navigation-compose` 还是自持栈 | 影响 B1 的 API 形态 | 倾向自持路由栈（桌面无返回键/深链需求弱），B1 第一周做原型后定 |

---

## 9. 证据索引（速查）

| 结论 | 证据 |
| --- | --- |
| 现有工程结构 | `settings.gradle.kts`（模块）、`app/build.gradle.kts:105-171`（依赖） |
| Compose 已启用 | `app/build.gradle.kts:91` `buildFeatures { compose = true }` |
| Provider 契约无 Android 类型 | `data/native/NativeProvider.kt:42-221` |
| 16 个 Provider 注册 | `data/native/NativeProviders.kt:8-25` |
| 取流可返回本地文件 | `data/native/NativeProvider.kt:62-74`、`data/native/SodaDecrypt.kt` |
| Android 播放器 = media3 | `playback/PlaybackService.kt:54`、`app/build.gradle.kts:125-134` |
| Room 实体 | `data/AppStore.kt:936-1025` |
| DataStore 使用点 | `data/SharePrefs.kt`、`data/AccountProfileStore.kt`、`data/AppStore.kt`、`data/ListenTaskStore.kt`、`data/SwipePrefs.kt` |
| QuickJS 只在 Android | `data/lx/LxScriptEngine.kt:9-12`、`lib-runtime-lx/.../LxQuickJsInterrupt.kt:9`、`lib-runtime-lx/src/main/jni/CMakeLists.txt` |
| Android 专属 UI | `widget/NowPlayingWidget.kt:11-14`（Glance）、`overlay/FloatingLyricsService.kt:3-6`、`recognize/MicRecorder.kt:3-5` |
| 单测可直接复用 | `app/src/test/**`（195 个文件，`data/native/**` 与 `data/webdav/**` 均纯 JVM） |
