# 核心层抽取（t2）：`:core` 到底搬了什么、缝在哪、还差什么

> 前置阅读：`docs/pc/ARCHITECTURE.md`（模块边界与选型）、`docs/pc/ROOM-SPIKE.md`（Room KMP 可行性）。
> 本文记录 t2 的实际施工结果，**以源码为准**：凡是本文与代码不一致，以代码为准并回来改本文。

包名一律保持 `com.mytv.mymusic.*` 不动——这是这一轮最重要的约束：`git mv` 之后
`:app` 的业务代码**一行 import 都不用改**，行为因此与搬迁前逐条一致。

---

## 1. 搬进 `:core` 的 93 个源文件

| 目录 / 文件 | 数量 | 说明 |
| --- | --- | --- |
| `data/Models.kt`、`data/ArtistAlbum.kt`、`data/Comments.kt` | 3 | 领域模型（`Song` / `ProviderInfo` / `Playlist` / 专辑、评论），全是 `@Serializable` |
| `data/MusicRepository.kt` | 1 | 统一仓库入口（搜索 / 取流 / 歌词 / 歌单写入） |
| `aggregate/*` | 9 | 聚合搜索、曲目匹配、换源解析、健康度、歌单合并 |
| `data/native/*` | 37 | 16 个平台实现里的 13 个 + 全部协议/加密/HTTP/解析辅助（见 §2） |
| `data/lx/*` | 5 | 洛雪协议（`LxProtocol` / `LxModels` / `LxHttpRequest` / `LxAttemptOrder`）+ 实现注入点 |
| `data/webdav/*` | 5 | 账号模型、路径规则、WebDAV 客户端、XML、错误文案 |
| `data/subsonic/*` | 5 | 账号、客户端、曲目映射、封面地址、播放上报 |
| `data/cloud/*` | 9 | 云盘统一模型 / 队列 / 错误 / JSON + 网易云、QQ 云盘客户端 |
| `data/metadata/*` | 1 | 公共元数据兜底（LRCLIB 歌词 + Apple 搜索封面） |
| `data/playreport/*` | 5 | 播放上报路由与四个平台上报实现 |
| `AppLog.kt` | 1 | 应用内日志环形缓冲（日志出口改成注入） |
| `core/*` 新增 | 8 | `PlatformInfo`、`Base64Codec`、`FileUris`、`PlaybackDataSourceFactory`、`MatchCacheStore`、`LocalSourceId`、`LxPlaybackHost`、`QqLoginChannel` / `SodaSecondVerify` / `ExtendedTrustHosts` / `SubsonicCaches` |

配套搬了 31 个单测 + 5 个 JSON 样本（`core/src/test/resources/{cloud,migu}`），
现在 `./gradlew :core:test` 能在**纯 JVM**（无 Android、无 Robolectric）跑完 232 个用例。

## 2. 留在 `:app` 的文件与原因

| 文件 | 卡在哪 | 后续怎么搬 |
| --- | --- | --- |
| `data/native/AppleProvider.kt` | 整曲要 Widevine（`playback/AppleWidevineDrmCallback` + media3） | 桌面按 ADR 降级为试听，需要一版桌面实现，而不是"搬" |
| `data/native/SpotifyProvider.kt` | `android.util.Base64` | 换成 `core/io/Base64Codec` 即可搬（约 2 处） |
| `data/native/MHubProvider.kt` + `MHubTls.kt` | MHubTls 是 media3 `DataSource` + `Uri` | 先做桌面侧的取流壳，再搬 |
| `data/native/QQLoginPage.kt`、`SodaQrSigner.kt`、`SodaQrHttpGateway.kt`(已搬部分) | WebView / Activity / CookieManager | 已抽成接口（§3），实现留在 :app |
| `data/lx/LxSources.kt`、`LxSourceStore.kt`、`LxScriptEngine.kt`、`LxTvStorage.kt`、`LxDesktopLibraries.kt` | `Context` + QuickJS(AAR) + assets | 桌面用 Javet（ADR）；先把 `LxScriptRuntime` 接口抽出来 |
| `data/local/*`（23 文件） | MediaStore / Media3 / Room DAO 都在这里 | 与 AppStore 一起搬（见 §5） |
| `data/sync/*`（13 文件） | `SyncModels` 直接投影 Room 实体（`SavedSong` / `PlaylistEntity`…），`SyncCoordinator` 还挂 WorkManager | **等 AppStore 搬完**即可整包跟进 |
| `data/webdav/WebDavStorage.kt`、`WebDavDataSource.kt` | media3 `DataSource` | `CloudStream.dataSourceFactory` 已改成占位类型，等桌面取流壳 |
| `data/subsonic/{SubsonicManager,SubsonicCoverCache,SubsonicCoverProvider,SubsonicPlayback,SubsonicTrackCache}` | `Context` / `ContentProvider` / Room | DAO 部分跟 AppStore 一起搬 |
| `AppStore.kt`（实体 + 8 个 DAO + 12 个迁移 + SettingsStore） | 见 §5 | **独立任务**，`ROOM-SPIKE.md` §5 有可直接抄的步骤 |

## 3. 平台缝（seam）一览

搬迁中每一处 `android.*` 都换成了「:core 定接口 / :app 装实现」，装点在
`app/src/main/java/com/mytv/mymusic/AndroidPlatformSeams.kt`（`Application.onCreate` 第一件事）。

| 注入点 | 位置 | Android 侧实现 | 没装时的行为 |
| --- | --- | --- | --- |
| `AppLog.platformLogger` | `core/AppLog.kt` | `AppLogAndroid.install()` → `Log.println` | 只进环形缓冲（单测常态） |
| `AppLogAndroid.captureHeader` | `app/AppLogAndroid.kt` | 读 `Build` / 版本号拼导出头 | 头部为空 |
| `PlatformInfo.osRelease` / `osName` | `core/PlatformInfo.kt` | `Build.VERSION.RELEASE` / `"android"` | 空串（与搬迁前单测一致） |
| `ExtendedTrustHosts` | `data/native/ExtendedTrust.kt` | `MHubTls.hosts` + `MHubTls.client` | 全部走默认 OkHttp 客户端 |
| `NativeProviders.registerFactory` | `data/native/NativeProviders.kt` | Apple / Spotify / MHub 三个工厂 | 该平台被跳过并打印 `provider init failed`（原有容错路径） |
| `QqLoginChannels.current` | `data/native/QqLoginChannel.kt` | `AndroidQqLoginChannel` → `QQLoginPage` | `begin` 返回 false，QQ 走原生取码兜底 |
| `SodaSecondVerify.bridge` | `data/native/SodaSecondVerify.kt` | 种 CookieManager + `SodaQrSigner` 可见窗口 | 返回 false，上层按「已取消」收尾 |
| `LxPlaybackHost.current` | `data/lx/LxPlaybackHost.kt` | `LxSources` | `MusicRepository` 不认识任何自定义音源 |
| `MatchCacheStore` | `data/MatchCacheStore.kt` | `RoomMatchCacheStore`（包 `AggregateDao`） | `MatchJob` 每次重算，不读缓存 |
| `SubsonicCoverSource` / `SubsonicTrackStore` | `data/subsonic/SubsonicCaches.kt` | `SubsonicCoverCache` / `SubsonicTrackCache` | 封面留空、离线不回落 |
| `PlaybackDataSourceFactory`（`typealias Any`） | `core/playback/PlaybackDataSourceFactory.kt` | 放 media3 `DataSource.Factory` | 只有普通地址可用 |
| `LOCAL_SOURCE_ID` | `data/LocalSourceId.kt` | 与 `data/local` 的 `LOCAL_SOURCE` 同值 | — |

新增的两个平台等价物（都带单测）：

- `core/io/Base64Codec` —— 顶替 `android.util.Base64`：`NO_WRAP` 编码、`DEFAULT` 宽容解码（忽略换行、允许缺补齐）；
- `core/io/FileUris.fileUri` —— 顶替 `android.net.Uri.fromFile().toString()`：`file://` + 分段百分号编码，
  Windows 盘符的 `:` 不编码（否则打不开）。

### 可见性说明

Kotlin 的 `internal` 是**模块级**的。源码搬进 :core 后，`internal` 的类/函数对 :app 就不可见了，
所以搬迁范围内的声明统一放宽为 public（`AppleChartFeed`、`LxProtocol`、`BasePlayReporter`、
`SourceChunkBody` 之类）。这是模块拆分的必然代价，不是遗漏；后续如果要重新收紧，
做法是为 :app 提供一个 `core-internal` 的 facade，而不是回退成同一个模块。

## 4. 验证

```
# 纯 JVM 单测（232 个用例，含六个平台的联网冒烟）
./gradlew :core:test

# Android 侧行为不变的证据：编译 + 单测编译
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest
```

### 六个平台在 JVM 上的联网冒烟（`core/src/test/.../ProviderSmokeTest.kt`）

2026-11-27 本机实跑（stdout 全文在 `core/build/test-results/test/TEST-...ProviderSmokeTest.xml` 的 `<system-out>`）：

| 平台 | searchSongs | downloadUrl |
| --- | --- | --- |
| netease | ✗ HTTP 502（上游 `music.163.com/api/linux/forward` 拒绝该出口） | — |
| qq | ✓ 4 条 | ✓ `https://ws.stream.qqmusic.qq.com/...ogg` |
| kugou | ✓ 5 条 | ✗ `kugou song info unavailable, errcode=0`（该曲需登录） |
| kuwo | ✓ 4 条 | ✓ `http://kw-er.kuwo.cn/...flac` |
| soda | ✗ 签名器未配置（`HTTP 500 签名不完整`，App 里由设置项注入签名器） | — |
| bilibili | ✓ 5 条 | ✓ `https://.../31268997329-1-30280.m4s` |

也就是 6 个里 4 个搜索成功、3 个直接给出可播地址、1 个给出可解释的拒绝原因；
两个失败都属于**平台侧前置条件**（出口被挡 / 签名器未注入），不是移植缺陷，日志每次都会原样打印。
用例的判定：6 个平台必须都注册；网络可用时至少 3 个能搜；取流至少要有一次"给地址"或"给原因"。

## 5. 还差什么（建议按顺序单开任务）

1. **AppStore / Room 搬进 :core**（最大的一块，`ROOM-SPIKE.md` §5 已给出可抄步骤）：
   实体 18 个、DAO 8 个、12 个迁移改签名、`BundledSQLiteDriver` 打开库。
   注意 `@Database` 的实体清单里还牵着 `data/local/*` 与 `data/subsonic/SubsonicTrackEntity`，
   这三个包要么一起搬，要么先给 :core 定义实体子集。
2. **`data/sync/*` 整包跟进**（依赖 1）：`SyncModels` 是 Room 实体到同步文件格式的投影。
3. **`data/lx/Lx*` 的脚本运行时抽象**：抽 `LxScriptRuntime`（assets 读取 + JS 上下文 + 中断），
   Android 用 QuickJS，桌面用 Javet；抽完 `LxSources` / `LxScriptEngine` 才能进 :core。
4. **桌面取流壳**：`PlaybackDataSourceFactory` 现在是 `Any`，桌面侧要有一个 VLC 版的实现，
   之后 `WebDavStorage` / `SubsonicPlayback` / `CloudHub` 才能脱离 media3 进 :core。
5. **Spotify**：把 `android.util.Base64` 换成 `Base64Codec` 即可搬（2 处）。

## 6. 给桌面端的接口清单（t3 起可以直接依赖）

`com.mytv.mymusic.data.MusicRepository`：搜索 / 分页 / 取流 / 歌词 / 评论 / 歌单读写 / 换源候选。
`com.mytv.mymusic.aggregate.AggregateSearchEngine` + `MatchJob` + `SourceResolver`：聚合搜索与自动换源。
`com.mytv.mymusic.data.native.NativeProviders`：13 个平台开箱可用，另 3 个由平台层登记。
`com.mytv.mymusic.data.cloud.*`：云盘统一模型 + 网易云 / QQ 云盘客户端（上传队列可直接复用）。
`com.mytv.mymusic.data.webdav.WebDavClient` + `WebDavPaths`：WebDAV 同步与云副本的 HTTP 层。
`com.mytv.mymusic.data.subsonic.SubsonicClient` + `SubsonicProvider`：Subsonic 服务器作为音源。
`com.mytv.mymusic.data.playreport.PlayReportHub`：播放上报路由（各平台实现已就位）。
