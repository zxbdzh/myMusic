# 播放侧 PC 化（t16）

| 项目 | 内容 |
| --- | --- |
| 范围 | `desktop/ui/NowPlayingPane.kt`、`desktop/ui/pages/PlayerPage.kt`、`desktop/ui/pages/LyricsPage.kt`、`desktop/ui/MiniPlayerBar.kt` + 新增 `desktop/ui/components/PcChrome.kt`、`desktop/src/test/.../DockPrefsTest.kt`、本文件 |
| 基准视口 | 1440×900 逻辑像素（`Main.kt` 的 `WINDOW_WIDTH/HEIGHT`），副视口 1920×1080 |
| 对照标准 | `docs/pc/PC-NATIVE-CHECKLIST.md` 第 4 节 P-01～P-06、D-08、D-09 |
| 责任 | audio2（t16）；与 t15（ui2）、t17（win2）文件不重叠 |
| 日期 | 2026-11-27 |

> 为什么要重做：上一版播放侧直接照搬 Android 平板尺寸（窗格 380dp、迷你条 86dp、
> 播放页封面铺满、无任何 hover/右键），在 1440×900 上呈现为「平板界面放大版」，用户否决。
> 本文记录替换后的每一个可测数值，以及两处只能在 PC 上成立的交互（拖动分隔条、hover 气泡）。

---

## 1. 尺寸表（改前 → 改后）

| 对象 | 改前（平板口径） | 改后（PC 口径） | 代码位置 |
| --- | --- | --- | --- |
| 右侧窗格宽度 | 380dp 固定 | **320dp 默认**，拖动 240–480dp | `NowPlayingPane.kt` + `PcChrome.kt: DockPrefs` |
| 窗格折叠态 | 无 | 44dp 竖条 + 展开箭头 | `NowPlayingPane.kt: CollapsedDockStrip` |
| 窗格封面 | 铺满窗格宽（`fillMaxWidth`） | 方形，**窗格宽 − 40dp**（两侧 padding 各 20dp） | `NowPlayingPane.kt` |
| 窗格标题 / 歌手 | M3 `titleLarge` / `bodyMedium`（≈22/14sp） | **15sp 粗体 / 12sp** | `NowPlayingPane.kt` |
| 窗格进度条 | 波浪 26dp | PC 直条 6dp，hover 9dp，时间气泡 | `PcChrome.kt: PcSeekBar` |
| 窗格走带 | 26dp 图标 / 56dp 主键 | **32dp / 44dp** | `NowPlayingPane.kt` |
| 「接下来播放」行高 | 36dp 封面 + 无固定行高 | **32dp**，hover 露播放键 | `NowPlayingPane.kt: UpNextRow` |
| 播放页封面 | 铺满整屏（沉浸式） | **≤320dp**，左侧竖排 | `PlayerPage.kt: CoverMaxSize` |
| 播放页标题 / 歌手 | headlineMedium（≈28sp） | **20sp / 13sp** | `PlayerPage.kt: PlayerStage` |
| 播放页队列 | 46dp 行高、4 行 | **32dp 行高、10 行** | `PlayerPage.kt: UpNextSection` |
| 播放页歌词栏 | 常驻不可收 | **两行常驻 + 可收起** | `PlayerPage.kt: PlayerRightRail` |
| 全屏歌词字号 | 当前行 headlineMedium / 其余 titleLarge | **当前行 26sp / 其余 22sp**，行距 **1.6** | `LyricsPage.kt: LyricSize/LyricActiveSize` |
| 全屏歌词留白 | 40dp 固定 | **两侧各 20% 宽**，歌词区最宽 **900dp** | `LyricsPage.kt: LyricBody` |
| 迷你条高度 | 86dp | **56dp** | `MiniPlayerBar.kt` |
| 迷你条封面 | 52dp | **40dp** | `MiniPlayerBar.kt` |
| 迷你条字号 | bodyLarge / bodySmall（≈16/12sp） | **13sp / 11sp** | `MiniPlayerBar.kt` |
| 迷你条进度条 | 波浪 16dp | **直条 4dp（hover 6dp）** | `MiniPlayerBar.kt` |
| 播放页/歌词走带 | 52–64dp | **32dp 侧键 / 44dp 主键** | `PlayerPage.kt: PlayerBottomBar`、`LyricsPage.kt: LyricsTransport` |

字号口径与 t15 统一：正文 13sp / 次要 11sp / 窗格标题 15sp / 歌词 22sp（当前行 26sp）。

---

## 2. 拖动分隔条的实现方式

结论：**`Modifier.draggable(orientation = Horizontal)` + `rememberDraggableState`，宽度状态放在
`DockPrefs`（Compose 可观察属性），拖动只改内存、松手才落盘。**

```
Row {
    DockDivider(onDrag = { DockPrefs.resizeBy(it) }, onDragStop = { DockPrefs.commit() })   // 7dp 命中区
    Column(Modifier.width(DockPrefs.widthDp.dp)) { ...窗格内容... }
}
```

要点（都在 `desktop/ui/components/PcChrome.kt`）：

1. **命中区与视觉分离**：拖拽区 7dp 宽，只画 1dp 的线；hover/拖动时线变 2dp 且换成主色。
   PC 上鼠标要容易抓到，但线本身不该抢内容。
2. **dp 换算**：`draggable` 给的是像素增量，`DockPrefs.resizeBy` 前先 `density.toDp()`，
   否则 125%/150% 缩放下拖动手感会偏。
3. **夹取**：`clampDockWidth()` 把值夹在 240–480dp（`MIN/MAX_WIDTH_DP`），
   单测 `DockPrefsTest#宽度夹在 240 到 480 之间` 锁住；拖动累加同样走它（`resizeBy`）。
4. **持久化**：`%APPDATA%\myMusic\dock.properties` 存 `dockWidth` / `collapsed` / `playMode` 三项。
   只在拖动结束、折叠切换、模式切换时写一次（`commit()`）——每拖一像素写文件没有意义。
   > 桌面版目前还没有把 `:core` 的 `SettingsStore`（DataStore）接进窗口，所以这里先按
   > `CoreMusicCatalog` 缓存 Cookie 的同一套「用户目录纯文本」办法；等 store 侧开放
   > `dockWidth` 字段后整块搬过去，调用点（`DockPrefs.widthDp` 等）不用改。这是本任务的
   > 已知偏差，已登记在 §5。
5. **折叠**：`DockPrefs.collapsed` 为真时整块替换成 44dp 竖条（`CollapsedDockStrip`），
   竖条上只有一个展开箭头；展开/收起都写回 `dock.properties`，重开窗口原样恢复。
6. **AppShell 零改动**：组件自己带分隔条、自己算总宽（7dp + 窗格宽），
   `AppShell.kt` 里仍是 `if (wide) NowPlayingPane(state, store)`。

---

## 3. 交互清单

### 3.1 进度条 / 音量条（`PcChrome.kt: PcSeekBar` / `PcVolumeBar`）

| 交互 | 行为 | 实现 |
| --- | --- | --- |
| hover | 条体高度 ×1.5（600ms 内 120ms 动画），光标位置换算数值显示在上方气泡 | `onPointerEvent(Move/Exit)` + `hoverFraction` |
| 拖动 | 按下即 seek，持续跟手；条体保持放大并露出 10dp 圆点 | `detectDragGestures` → `onSeekFraction` |
| 单击 | 跳到点击位置 | `detectTapGestures` |
| 滚轮（仅音量） | ±5%（向下 = 减） | `onPointerEvent(Scroll)` + `wheelStep = 0.05f` |
| 气泡内容 | 进度条：精确时间（`mm:ss`）；音量条：百分比 | 调用方传 `bubble` lambda |

气泡层固定占 22dp，hover 出现/消失不会让布局跳动。

### 3.2 封面

| 位置 | 交互 |
| --- | --- |
| 窗格封面 | hover 出现「打开播放页」遮罩 → 点击进播放页 |
| 播放页封面 | hover 出现「打开全屏歌词」→ 点击进歌词页 |
| 迷你条封面 | 点击进播放页 |

### 3.3 右键

| 位置 | 菜单 |
| --- | --- |
| 播放窗格（整块） | 播放模式：顺序 / 随机 / 单曲 / 心动（当前项带 ✓） |
| 播放页「更多」 | 查看专辑 / 查看歌手 / 分享（复制「曲名 - 歌手」到剪贴板）/ 音质说明 |

实现：`Modifier.onSecondaryClick` = `onPointerEvent(Press)` + `buttons.isSecondaryPressed`，
`DropdownMenu` 挂在窗格根 `Box` 上。

### 3.4 迷你条（<1200dp）

左：封面 40dp + 两行 13sp/11sp；中：28dp 上/下首 + 32dp 播放 + 进度条 4dp + 11sp 时间；
右：28dp 静音（muted 时描边色）+ 72dp 音量条 + 队列 + 全屏。

### 3.5 全屏歌词

| 交互 | 行为 |
| --- | --- |
| 点击某行 | 跳到该行开始时间（`store.seekTo(line.startMs)`） |
| 滚轮 | LazyColumn 原生滚动 |
| 自动跟随 | 用户滚动中（`isScrollInProgress`）不抢位置，停下后跟随当前行 |
| Esc | 由 win2 的 `Shortcuts`（t17）统一处理 |
| 底部走带 | 常驻，可暂停 / 切歌 / 拖进度 |

### 3.6 窗口级快捷键（归属 t17，非本任务文件）

t16 要求「空格播放暂停 / ←→ seek ±5s / ↑↓ 调音量」，实际注册在 `Main.kt` 的 `handleShortcut`，
win2 的 t17 已改为 `Shortcuts.resolve`：Space 播放暂停、←/→ ±5s、↑/↓ 音量 ±0.05。
本任务不碰 `Main.kt`，只在文档登记口径，避免两处各写一份快捷键。

---

## 4. 与 Android 的差异

| 项 | Android（平板/手机） | PC（本实现） | 原因 |
| --- | --- | --- | --- |
| 窗格宽度 | 固定 380dp（≥1200dp 时出现） | 320dp 默认、可拖 240–480、可折叠、记忆 | 窗口宽度由用户决定，固定侧栏在 1600 宽下占 24% |
| 播放页结构 | 上下滑切换的沉浸式大封面 | 左封面（≤320dp）+ 右信息/歌词两栏 | 横屏 + 鼠标：两栏信息密度更高，封面铺满浪费面积 |
| 封面点击 | 手势（上下滑） | hover 遮罩提示 + 点击 | PC 没有可发现的手势暗示 |
| 进度条 | 波浪点阵（Expressive） | 直条 + hover 时间气泡 + 拖动放大 | 鼠标悬停能精确到秒，波浪样式读不出具体时间 |
| 队列行高 | 56dp（平板） | 32dp | 同屏可看 10+ 行 |
| 歌词字号 | 随播放页缩放（平板更大） | 22sp / 当前 26sp、行距 1.6 | 桌面阅读距离 + 宽行，行距不足中文会糊 |
| 歌词点击 | 无（只有滚动） | 点击任意行跳播 | 鼠标点哪句唱哪句，比"滚到某句"直接 |
| 播放模式 | 播放页底部四态按钮 | 窗格右键菜单 + 播放页走路带旁按钮 | 右键是 PC 的"更多操作"惯用入口 |
| 分享 | 系统分享面板 | 复制「曲名 - 歌手」到剪贴板 | Windows 没有统一分享面板 |
| 迷你条 | 平板无独立迷你条（手机有） | <1200dp 时 56dp 迷你条 | 窄窗口下的常驻控制 |

---

## 5. 已知偏差与待接线（如实登记）

1. **窗格偏好存储位置**：写在 `%APPDATA%\myMusic\dock.properties`，未进 `:core` 的
   `SettingsStore`（桌面窗口尚未注入 DataStore）。偏差原因见 §2 第 4 点；
   store 侧接入 `dockWidth` 后迁过去即可，调用点不变。
2. **播放模式的落地**：`DockPrefs.playMode` 记住选择并显示 ✓，但桌面 `MusicStore` 还没有
   `setRepeatMode/setShuffle`（`:playback-core` 的 `PlaybackQueue` 有实现，`PlaybackUseCase`
   未接）。右键菜单的选择暂不改变 `next()` 行为——已在 `PcChrome.kt` 标 `TODO(t3/P2)`。
3. **「查看专辑 / 查看歌手」**：`Track` 只带名字、没带平台侧 `artistId/albumId`，
   先用名字当 key 尝试（部分平台可按名字反查），拿不到会走 store 的错误提示；
   `CoreMusicCatalog.toUi` 补 id 后改为真 id。
4. **音质**：播放侧还没有"实际档位"（`:core` `PlayableStream.level` 未进 `Track`），
   「更多」菜单只说明"由平台解析"，不编造档位标签。
5. **P-08（均衡器/交叉淡化/定时/缓存入口）**：引擎侧已由 t3 交付（`:playback-core` 99 用例），
   UI 接线不在本任务四文件范围内，仍是 P2 待办。
6. **hover 高亮 / tooltip 底座**：win2 的 `DesktopInteraction.kt`（t17）落地后，
   `PcChrome.kt` 的 hover 效果可改用它；当前自包含实现保证 t16 独立可验。

---

## 6. 验收与复现

```powershell
# 离屏截图（不开窗口、不抢焦点）
$env:JAVA_HOME="D:\env\.jdks\temurin-25.0.3"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
./gradlew :desktop:run --args="--screenshot=F:/tmp/pc-player-20261127"

# 单测（含本任务新增 DockPrefsTest）
./gradlew :desktop:test
```

产物：`05-player.png`（播放页两栏 + 底部走带）、`06-lyrics.png`（全屏歌词）、
`01-charts.png`（含右侧 320dp 窗格）。尺寸核对用 §1 表格逐项对照
`docs/pc/PC-NATIVE-CHECKLIST.md` 的 P-01～P-06。
