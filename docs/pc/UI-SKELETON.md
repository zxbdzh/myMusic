# 桌面 UI 骨架（t4）

> 状态：可运行的窗口骨架。视觉沿用 Android 的 Material 3 Expressive 语言，
> 页面按「主导航 → 迷你播放条 → 播放页 → 全屏歌词 → 海报墙 → 设置」的顺序落地。
> 数据来自示例音源（`DemoMusicCatalog`），接 t2 后只需换一个实现，页面代码不动。

## 1. 运行与截图

```bash
# 正常启动（需要 JDK 17+；本机 Gradle 用 JAVA_HOME 指向 JDK 根目录）
./gradlew :desktop:run

# 截图模式：不开窗口，离屏渲染 8 张 PNG（不抢焦点，CI 可用）
./gradlew :desktop:run --args="--screenshot=F:/tmp/mymusic-pc-shots"

# Windows 集成自检（t5 提供宿主 main）
./gradlew :desktop:runWinSelfTest
```

截图产物：`01-charts.png` / `02-playlists.png` / `03-search.png` / `04-library.png` /
`05-player.png` / `06-lyrics.png` / `07-poster-wall.png` / `08-settings.png`（1600×1000）。

## 2. 代码地图（`desktop/src/main/kotlin/com/mytv/mymusic/desktop/`）

| 路径 | 作用 | 对照 Android |
| --- | --- | --- |
| `Main.kt` | 窗口装配、全局快捷键、`--screenshot` / `--win-selftest` 分发、Windows 接缝（`WindowsShell` / 悬浮歌词） | `MainActivity` + `MyMusicApp` 的壳 |
| `ui/AppShell.kt` | 导航栏 + 内容区 + 迷你播放条 + 三层覆盖（播放页/歌词/海报墙） | `ui/AppNavigation.kt` |
| `ui/AppNavRail.kt` | 左侧导航（四入口 + 设置 + 播放后端状态点） | 平板 `TabletNavRail` |
| `ui/MiniPlayerBar.kt` | 底部迷你播放条（封面、走带、波浪进度、音量、入口） | `BottomPlayerBar` / `MiniPlayer` |
| `ui/pages/ChartsPage.kt` | 榜单：主推榜 + 热门单曲（两列）+ 全部榜单网格 | `charts/ChartsScreen.kt` |
| `ui/pages/PlaylistsPage.kt` | 歌单：推荐 + 分类切换 + 卡片网格 | `playlists/PlaylistsScreen.kt` |
| `ui/pages/SearchPage.kt` | 搜索：回车即搜、类型切换、历史与热门词、结果列表 | `search/SearchScreen.kt` |
| `ui/pages/LibraryPage.kt` | 我的：统计 bento + 自建歌单 + 最近播放 + 收藏 | `library/LibraryScreen.kt` |
| `ui/pages/CollectionPage.kt` | 榜单/歌单详情（页内子视图，未引导航库） | `collection/{key}` 路由 |
| `ui/pages/PlayerPage.kt` | 播放页：大封面、信息、走带、音量、接下来播放、歌词侧栏 | 平板 `PlayerScreen` |
| `ui/pages/LyricsPage.kt` | 全屏歌词：当前行高亮 + 句内进度 + 自动居中 | `player/stage` 的歌词视图 |
| `ui/pages/PosterWallPage.kt` | 海报墙最小版：队列网格、悬停放大、点封面切歌 | `poster/PosterWallScreen.kt` |
| `ui/pages/SettingsPage.kt` | 设置：外观（主题/配色/种子色/纯黑/对比度/动效）、后端、快捷键、关于 | `settings/SettingsScreen.kt` |
| `ui/components/AppIcons.kt` | 自绘图标集（24×24 网格，Canvas 原语） | `material-icons-extended` 的等价物 |
| `ui/components/Expressive.kt` | Expressive 自绘控件：弹性导航项、播放/暂停形变按钮、波浪进度条、胶囊 chip | `WavySeekBar` / M3 Expressive 组件 |
| `ui/components/Content.kt` | 封面（程序化生成）、单曲行、卡片、空态/错误条、骨架 | `components/*` |
| `state/MusicStore.kt` | 状态契约：`MusicUiState` + 全部 intent | `MusicViewModel` 的对外接口 |
| `state/DefaultMusicStore.kt` | 状态实现：把 4 个取数用例 + 播放用例合成一份快照 | `MusicViewModel`（7101 行） |
| `state/usecase/*.kt` | `ChartsUseCase` / `PlaylistsUseCase` / `SearchUseCase` / `LibraryUseCase` / `PlaybackUseCase` | ViewModel 里按域拆出的 load/play 方法 |
| `state/MusicCatalog.kt` | 数据入口（唯一的接缝：以后换成 `CoreMusicCatalog`） | `MusicRepository` + `NativeProviders` |
| `state/audio/*.kt` | `AudioOutput` 契约 + 模拟输出 + libVLC 适配（反射构造，t3 冻结后改直连） | `PlaybackService` + ExoPlayer |
| `state/lyric/LyricsParser.kt` | LRC 解析（时间轴 / offset / 一行多时间戳） | `ui/LyricsParser.kt` 的最小集 |
| `theme/*.kt` | material-kolor 动态配色、Expressive 动效常量、系统深浅色 | `ui/theme/Theme.kt` |

## 3. 关键取舍

1. **Expressive 组件**：CMP 的 `material3-desktop 1.9.0` 把 `MaterialExpressiveTheme` / `MotionScheme`
   标成 internal，`WavyProgressIndicator`、`ShortNavigationBar` 也没有。所以主题退回 `MaterialTheme`，
   三处"手感"（导航指示器弹簧、播放按钮形变、波浪进度）在 `ui/components/Expressive.kt` 里自绘，
   动效参数集中在 `ExpressiveMotion`。
2. **封面**：骨架期没有 Coil，也不让截图依赖网络，`CoverArt` 用曲目 id 的哈希生成稳定渐变 + 首字。
   接 :core 后改成"URL 非空用 Coil、否则回退程序化封面"即可，调用点不变。
3. **播放后端**：本机没有 libVLC，`AudioOutputs.create()` 退回 `SimulatedAudioOutput`（定时器推进进度，
   界面如实显示"模拟输出"）。装了 libVLC 时走 `VlcAudioOutput`：按类名反射构造 `VlcPlaybackEngine`
   （t3 的构造参数还会变），只对 `PlaybackEngine` 的七个方法做静态调用。
4. **状态层**：`MusicUiState` 是一个不可变快照（Android 侧摊成十几个 StateFlow），
   播放域由 `PlaybackUseCase` 单独持有再单向汇入，避免同一字段两处可写。
5. **接 t2**：把 `Main.kt` 里 `DefaultMusicStore()` 的默认 catalog 换成 `CoreMusicCatalog`（待写），
   `MusicCatalog` 的方法签名不动；搜索、榜单、歌单、歌词、取流全部走这一条通路。

## 4. 已知边界（P2 / 其他任务）

- 队列持久化、均衡器、交叉淡化、定时关闭面板：t3 + P2；
- 听歌模式、识曲、链接解析、同步/WebDAV/本地音乐：P2（需要登录态与平台载体）；
- 海报墙的 3D 相机与听歌模式"带子"：B4 批次；
- 多语言、无障碍朗读细节、窗口记忆位置：P2。
