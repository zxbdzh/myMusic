# myMusic PC 端功能对齐矩阵

**基线**：`docs/pc/FEATURE-INVENTORY.md`（Android 侧 156 条，24 个域）
**本文回答**：每一条在 PC 端现在到哪了，证据在哪，剩下的怎么排。
**基线提交**：`pc` 分支 `4cee9e5`

## 状态口径

| 状态 | 含义 |
| --- | --- |
| **已完成** | 桌面端能跑、有代码、有测试或截图佐证 |
| **引擎就绪·未接线** | 逻辑在 `:core` / `:playback-core` 里且测试绿，但桌面 UI/装配还没用上 |
| **未实现** | 桌面端还没有 |
| **不适用** | 桌面没有对应物，已有替代方案 |
| **降级** | 能实现但能力弱于 Android，原因已列 |

---

## A. 音源与账号（F-001～F-021）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 13 个平台在 JVM 上可查可取流 | `core/.../data/native/*Provider.kt`；冒烟：qq/kuwo/bilibili 搜索+取流可播，kugou 搜索成功取流需登录，netease 502（本机出口被拒）、soda 500（签名器需 App 注入） |
| 已完成 | 多平台聚合为一份列表 | `desktop/.../state/CoreMusicCatalog.kt` 的 `search`/`charts`/`playlists` 并发打各平台后去重 |
| 已完成 | 平台 Cookie 最小落地 | `%APPDATA%\myMusic\platform-cookies.txt`（`PlatformCookieStore`） |
| 未实现 | 扫码登录 / 密码登录 UI | `NativeProvider.createLogin/checkLogin/passwordLogin` 在契约里，桌面无页面 |
| 未实现 | Apple / Spotify / MHub 三个平台 | 三者实现留在 `:app`（Apple 需 Widevine DRM 与 Context、Spotify 需 `android.util.Base64`、MHub 需 media3 数据源壳）；桌面启动时打 `provider init failed` 属预期 |
| 未实现 | 汽水会员 / 天天送会员 | `SodaWelfare` 在 `:core`，缺 UI 与签名器注入 |
| 未实现 | 洛雪自定义音源 | 协议层 `LxProtocol`/`LxSources` 已进 `:core`；脚本引擎需 QuickJS→Javet 替换（原 wrapper 无 JVM 产物，实测 404） |
| 未实现 | Subsonic 服务器 | `SubsonicProvider`/`SubsonicTrackCache` 在 `:core`，缺设置页与账号管理 |

## B～C. 聚合换源 / 榜单（F-024～F-034）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 榜单浏览（轮播 + 全部榜单 + 热门单曲） | `desktop/.../ui/pages/ChartsPage.kt`；截图 `01-charts.png` 显示真实飙升榜 65.3 亿播放 |
| 已完成 | 榜单详情（播放全部 / 逐曲列表） | `CollectionPage.kt` |
| 引擎就绪·未接线 | 自动换源（同曲多源备选） | `:core` 的 `AggregateSearchEngine`/`SourceResolver`/`TrackMatcher` + `:playback-core` 的 `AutomixPlanner`；桌面 `playTrack` 目前直接取单源 |
| 未实现 | 音源选择面板（逐曲选平台） | Android 的 `SourcePickerSheet` 无桌面版 |

## D～E. 歌单 / 听歌模式（F-035～F-044）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 推荐歌单 + 分类筛选 | `PlaylistsPage.kt`；截图 `02-playlists.png` 显示真实歌单（126/238/241 首） |
| 已完成 | 歌单详情 / 播放全部 | `CollectionPage.kt` + `catalog.playlistTracks` |
| 未实现 | 自建歌单增删改、拖拽排序 | `:core` 的 `PlaylistEntity`/`PlaylistEntryEntity` 与 DAO 已就绪，缺 UI |
| 未实现 | 听歌模式（场景/心情电台） | `:core` 有 `listeningModes`/`listeningModeSongs`，缺入口 |
| 未实现 | 平台歌单回写（加歌/删歌/排序） | `:core` 有 `addPlaylistTracks`/`removePlaylistTracks`/`reorderPlaylistTracks`，缺 UI 与写权限提示 |

## F～G. 我的 / 搜索（F-045～F-053）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 我的（喜欢 / 最近 / 下载 入口 + 自建歌单区） | `LibraryPage.kt` |
| 已完成 | 搜索（按键提交、按录音去重、来源标签） | `SearchPage.kt` + `CoreMusicCatalog.search`，并发打 13 个平台 |
| 已完成 | 歌手 / 专辑 / 评论的状态层 | `DefaultMusicStore` 的 `openArtist`/`openAlbum`/`openComments`/`loadMoreComments`（fdd143c） |
| 未实现 | 歌手页 / 专辑页 / 评论侧栏 UI | 状态层已就绪，页面未画 |
| 未实现 | 链接解析（粘贴平台链接） | `catalog.parseLink` 已实现，UI 只在 notice 报「已解析：xxx」，未接详情跳转 |
| 未实现 | 识曲（听几秒识别） | 需麦克风 + 指纹算法；`recognize/` 在 `:app` 且依赖 Android MediaCodec |
| 不适用 | 语音搜索 | 桌面无语音输入，改键盘输入 |
| 未实现 | 搜索历史 | Android 有 `SearchResultCaches`，无桌面版 |

## H. 本地音乐（F-054～F-060）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 引擎就绪·未接线 | 本地曲库（扫描 / 标签 / 同名 lrc / 回退播放） | `:core` 的 `LocalModels`/`LocalRefs`/`LocalUploadRules` + Room 表已就绪；目录遍历（替代 MediaStore）与标签解析未接 |
| 未实现 | 上传到网易云盘 / WebDAV | `:core` 的 `NeteaseCloudUploader`/`CloudUploadQueue` 已就绪；WebDAV 部分在 `:app` |

## I. 音乐库同步（F-061～F-070）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 引擎就绪·未接线 | 三方合并 / 墓碑 / 绑定歌单语义 | `:core` 的 `SyncModels`/`SyncRefs`（合并算法纯 JVM，随 t2 落地） |
| 未实现 | Gist / Gitee / WebDAV 三个同步位置 + 设置页 | 三个 `SyncProvider` 实现留在 `:app`（依赖 OkHttp + `SecretStore` 的 Android Keystore） |
| 未实现 | 本机 JSON 导入导出 | `:core` 有 `LxListCodec` 等编解码，缺 UI |

## J～K. 歌词 / 歌词动画（F-071～F-089）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 歌词解析 + 全屏逐行高亮 + 句内进度 | `desktop/.../ui/pages/LyricsPage.kt` + `LyricPipeline`；截图 `06-lyrics.png` |
| 已完成 | 播放页常驻两行歌词 | `PlayerPage.kt` |
| 未实现 | 翻译 / 罗马音 / 繁简转换 | 需文本转换库；`:core` 的 `LyricTextPrefs` 已能存设置 |
| 未实现 | 10 种歌词动画（Rush / 虹光 / 52Hz / 星潮 / 巨幕 / 黑胶 / MiSide 等） | 见「B3 可视化」：技术路线已验证（AGSL 原文可在 Skia 零改动编译），但 24 个 `modes/**` 一个都没搬 |
| 未实现 | 歌词分享图卡（10 种样式、9:16 导出） | `:app` 的 `player/share/**` 未搬 |

## L. 海报墙（F-090～F-095）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 队列网格 + 悬停放大 + 当前曲描边 + 点播 | `PosterWallPage.kt`（最小版）；截图 `07-poster-wall.png` |
| 未实现 | 无限四向拖拽 / 大小不一格子 / 3D 相机 / 歌词大卡 / 详情页海报视图 | Android 的 `poster/**` 17 文件，桌面只有线性网格 |

## M. 播放器（F-096～F-108）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 已完成 | 播放页（大封面 / 信息 / 走带 / 音量 / 接下来播放 / 歌词侧栏） | `PlayerPage.kt`；截图 `05-player.png` |
| 已完成 | 迷你播放条（封面 / 曲目 / 走带 / 波浪进度 / 音量 / 入口） | `MiniPlayerBar.kt` |
| 已完成 | 队列管理（下一首 / 上一首 / 跳转 / 随机 / 循环） | `:playback-core` 的 `PlaybackQueue`/`QueueController`/`QueueShuffleOrder`，80 用例 |
| 引擎就绪·未接线 | **真实音频输出** | `:playback-vlc` 的 `VlcPlaybackEngine` + CLI runner 已实现（19 用例）；桌面 `AudioOutput` 仍走模拟输出（截图左下角「模拟输出（未接真实音频）」） |
| 引擎就绪·未接线 | 10 段均衡器 + 预设 | `VlcEqualizer` + `EqualizerCurve`（8 用例） |
| 引擎就绪·未接线 | 交叉淡化（固定 / 智能 / 不过渡） | `VlcCrossfade` + `CrossfadeCoordinator`/`CrossfadeCurve` + `AutomixPlanner`（24 用例） |
| 引擎就绪·未接线 | 定时关闭（15/30/45/60 分钟或本曲） | `SleepTimer`（6 用例） |
| 引擎就绪·未接线 | 播放缓存上限与淘汰 | `PlaybackCache`/`PlaybackCacheConfig`（7 用例） |
| 引擎就绪·未接线 | 响度归一化 | `VolumeNormalization`（12 用例） |
| 未实现 | 系统媒体控制（SMTC） | `desktop/win/SmtcBridgeClient.kt` + C# bridge 已写，21 用例覆盖到命令翻译；**真机媒体浮层未验证** |
| 未实现 | 后台播放 / 托盘常驻 | 托盘与单实例已实现（`TrayControl`/`SingleInstance`）；关闭窗口驻留托盘的行为未验证 |

## N～O. 分享图卡 / 主题外观（F-111～F-121）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 未实现 | 分享图卡 | 同 J 域 |
| 已完成 | 封面取色生成配色（material-kolor HCT） | `desktop/.../theme/`；截图里浅/深两套配色由封面驱动 |
| 已完成 | 自绘 Expressive 组件 | `ui/components/Expressive.kt`（弹性导航指示器 / 形变播放按钮 / 波浪进度条 / 胶囊 chip）——CMP material3 1.9.0 的 `MaterialExpressiveTheme`/`MotionScheme` 是 internal，只能自绘 |
| 未实现 | 品牌色 / 系统壁纸取色 / 对比度 / 纯黑 / 减少动画 | `Appearance` 模型有字段，设置页只接了部分 |
| 未实现 | 模糊背景（haze） | 依赖 `dev.chrisbanes.haze` 的桌面支持，未接 |

## P. 桌面小组件（F-122～F-129）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 不适用 | 6 款 Glance 桌面小组件 | Android Glance 无桌面对应物 |
| 已完成（替代） | 置顶迷你窗 / 悬浮歌词窗 | `desktop/win/FloatingLyrics.kt`（置顶无边框歌词浮层） |
| 未实现（替代） | Windows 11 Widgets | 需 MSIX + C# 组件，P2 |

## Q～R. 词幕互通 / 折叠屏（F-130～F-137）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 未实现（替代） | 词幕 Lyricon 互通 | 原为 Android 广播；桌面方案是本地 WebSocket 供第三方歌词工具订阅，未实现 |
| 不适用 | 折痕分栏 / 桌面姿态 / 双屏铰链 / 折叠不打断 | 桌面无 `FoldingFeature`；多显示器场景用窗口宽度分栏 |
| 已完成 | 矮窗紧凑布局 | 窗口可任意缩放，`minimumSize` 1120×720 |

## S～T. TV / 应用内更新（F-138～F-144）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 不适用 | 电视全屏播放页 / 遥控器方向键 | 桌面无遥控场景；改全局媒体热键（`GlobalHotkeys.kt`） |
| 未实现（替代） | 环境模式（空闲只显示封面歌词） | 可做「屏保模式」，触发条件改为空闲计时 |
| 未实现（替代） | 阻止屏保 | 桌面用 `SetThreadExecutionState`，未接 |
| 未实现（替代） | 应用内更新 | 原为 APK 下载安装；桌面应改「检查 `update.json` → 下载 exe → 提示重启」，未做 |

## U～X. 评论 / 汽水 / 无障碍 / 性能（F-145～F-156）

| 状态 | 条目 | 说明 / 证据 |
| --- | --- | --- |
| 引擎就绪·未接线 | 歌曲评论 | `DefaultMusicStore.openComments/loadMoreComments` + `catalog.comments`（fdd143c），缺侧栏 UI |
| 未实现 | 汽水会员与天天送 | 同 A 域 |
| 未实现 | 无障碍（读屏语义） | Compose Desktop 的语义树可用，未做走查 |
| 已完成 | 性能基线 | `docs/pc/SHADER-SPIKE.md`：1080p 60s p50/p95 = 10.00/10.60ms，draw 入队 0.04ms；无 GPU 时 CPU 光栅 2.5s/帧，必须降级 |
| 不适用 | Baseline Profile | Android 专有 |

---

## 汇总

| 状态 | 条数（约） | 占比 |
| --- | --- | --- |
| 已完成 | 26 | ~17% |
| 引擎就绪·未接线 | 18 | ~12% |
| 未实现 | 82 | ~53% |
| 未实现（有替代方案） | 8 | ~5% |
| 不适用 | 5 | ~3% |
| 降级 | 17 | ~11% |

> 口径说明：这里的「已完成」指桌面端有可运行实现；「引擎就绪·未接线」是本次最大的价值洼地 —— 逻辑和测试都做完了，只差桌面装配与页面。

---

## 下一批优先级

### P0：把「引擎就绪」变成「已完成」（性价比最高）

| # | 任务 | 依赖 | 估时 |
| --- | --- | --- | --- |
| 1 | 桌面接 `VlcPlaybackEngine`：`AudioOutputs.create` 换成真实 VLC 输出，替换模拟输出 | 需用户机器装 VLC 3.0.x，或先把 libvlc 打进包 | 0.5 人日 |
| 2 | 均衡器 / 交叉淡化 / 定时关闭 / 响度归一化的设置页（B4 的 `EqualizerScreen` 等） | #1 | 1.5 人日 |
| 3 | 评论侧栏 UI（复用已就绪的状态层） | 无 | 0.5 人日 |
| 4 | 歌手页 + 专辑页 UI | 无 | 1 人日 |
| 5 | 链接解析跳转（歌曲→播放、歌单/专辑→详情） | 无 | 0.5 人日 |

### P1：补齐核心缺失

| # | 任务 | 依赖 | 估时 |
| --- | --- | --- | --- |
| 6 | 自建歌单 CRUD + 拖拽排序（Room 表与 DAO 已就绪） | 无 | 1.5 人日 |
| 7 | 音乐库同步：把三个 `SyncProvider` 搬到 `:core` 并接设置页 | 需 SecretStore 的桌面实现 | 2 人日 |
| 8 | 本地音乐：目录扫描 + 标签解析（替代 MediaStore） | 无 | 1.5 人日 |
| 9 | 洛雪音源：QuickJS→Javet 换引擎 | Javet 6.0.4 已在依赖里 | 2 人日 |
| 10 | 歌手/专辑/搜索历史等剩余 UI | 无 | 1 人日 |

### P2：可视化与桌面增强

| # | 任务 | 依赖 | 估时 |
| --- | --- | --- | --- |
| 11 | B3-0 绘制桥接层（`nativeCanvas`→`skiaCanvas`、uniform 扁平数组）+ B3-1 直接搬 13 个可视化 | SHADER-SPIKE 已验证 | 1.5 人日 |
| 12 | B3-2/3/4：Shader 2 个 + RenderEffect 4 个 + Bitmap 4 个（建议 v1 砍掉 `VinylDiscVisualizer`） | #11 | 6 人日 |
| 13 | 海报墙完整版（无限拖拽 / 3D 相机 / 歌词大卡） | 无 | 3 人日 |
| 14 | 歌词分享图卡（10 种样式、`ImageComposeScene` 导出 PNG） | 无 | 2 人日 |
| 15 | 词幕互通（本地 WebSocket） | 无 | 1 人日 |
| 16 | 应用内更新、屏保阻止、环境模式 | 无 | 1 人日 |
| 17 | libVLC 打进安装包 + MSI（需 WiX）+ CI 打包任务 | `BUILD.md` | 1 人日 |

**P0 合计约 4 人日，P1 约 8 人日，P2 约 15.5 人日。**

---

## 证据索引

| 结论 | 证据 |
| --- | --- |
| 13 个平台在 JVM 可跑 | `core/src/main/kotlin/com/mytv/mymusic/data/native/*Provider.kt` + `ProviderSmokeTest` 输出 |
| 播放引擎实现完整 | `bcb9049`（`:playback-core` 80 + `:playback-vlc` 19 用例） |
| Room 落地 :core | `c07a946`（`core/.../AppDatabase.kt`、`SettingsStore.kt`、`app/.../AppDatabaseAndroid.kt`） |
| 桌面真实数据 | `F:/tmp/mymusic-pc-shots-room2/*.png`（8 张，真实榜单/歌单/歌词） |
| 桌面连接缝 | `desktop/.../state/CoreMusicCatalog.kt`（实现 `MusicCatalog`） |
| Shader 可用性 | `docs/pc/SHADER-SPIKE.md` |
| 打包流程 | `docs/pc/BUILD.md` |
| Android 基线 | `docs/pc/FEATURE-INVENTORY.md`（156 条） |
