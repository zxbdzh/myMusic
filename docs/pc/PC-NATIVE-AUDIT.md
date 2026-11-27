# PC 原生验收审计（t18）

| 项目 | 内容 |
| --- | --- |
| 用途 | 对 t15（密度四页）/ t16（播放侧）/ t17（窗口装饰与交互底座）的逐项核查 |
| 基准视口 | 1440×900 逻辑像素（离屏 `--screenshot`，未开窗、未抢焦点） |
| 核查对象 | t15 `de744c8`、t16 `a48f9c5`、t17 `0b0f719` |
| 核查日期 | 2026-11-27 |
| 结论 | **契约 23 条：20 PASS / 3 FAIL**；另有 8 条非阻塞观察项、3 处证据缺口 |
| 纪律 | 未改任何产品代码；只读代码 + 离屏截图 + 既有单测 |

> 判定依据是 t18 任务契约（A 密度 / B 交互 / C 装饰 / D 播放侧），
> 不是初版清单里我自定的更严数值。契约优先：例如页面标题契约允许 ≤22sp，
> 初版清单写 ≤20sp，本审计按 22sp 判 PASS，并在清单里同步修正。

---

## 一、结论摘要

| 类别 | 契约条数 | PASS | FAIL | 说明 |
| --- | --- | --- | --- | --- |
| A 密度 | 7 | 6 | **1** | 榜单页同屏 15 行 < 18（A-1） |
| B PC 交互 | 7 | 5 | **2** | 细滚动条未接入（B-6）、tooltip 未接入（B-7） |
| C 窗口装饰 | 4 | 4 | 0 | 菜单栏/快捷键提示/动态标题/窗口记忆 |
| D 播放侧 | 5 | 5 | 0 | 窗格拖动折叠/封面/时间气泡/窄窗迷你条 |
| **合计** | **23** | **20** | **3** | 三处都是「基础设施已就绪、页面未接或差 3 行」 |

**三处 FAIL 的共同点**：都不是架构问题，而是最后一公里的接入/尺寸微调，单项工作量都在半天内。

---

## 二、逐项证据

### A. 密度（对照 Android 平板，必须更紧凑）

| 编号 | 标准（契约） | 实测 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| A-1 | 同一屏可见歌曲行数 **≥18 行**（1440×900） | 榜单页：**15 行完整 + 第 16 行被裁**（ui2 报 16，把裁切计入）；我的页：21 行；搜索页：4 行（QQ 对「晴天」实际返回 4 条，属数据量） | `01-charts.png`（列表 y≈347→889，行距 36px）、`04-library.png`、`03-search.png` | **FAIL（差 2–3 行）** |
| A-2 | 正文 ≤13sp | **13sp** | `theme/Metrics.kt:79`（`body`） | PASS |
| A-3 | 区块标题 ≤15sp | **15sp** | `Metrics.kt:84`（`sectionTitle`） | PASS |
| A-4 | 页面标题 ≤22sp | **22sp** | `Metrics.kt:85`（`pageTitle`） | PASS |
| A-5 | 行高 ≤36dp（单行）/ ≤48dp（双行） | **32dp / 48dp** | `Metrics.kt:91`（`row`）、`:93`（`rowCover`） | PASS |
| A-6 | 页面水平内边距 ≤20dp | **20dp**（垂直 16dp） | `Metrics.kt:104`（`pageH`）、`:105`（`pageV`） | PASS |
| A-7 | 卡片圆角 ≤12dp | **卡片 10dp / 封面 8dp** | `Metrics.kt:112`（`cardRadius`）、`:113`（`coverRadius`） | PASS |

### B. PC 交互

| 编号 | 标准（契约） | 实测 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| B-1 | 列表行 hover 变色 | hover 100ms → `surfaceContainerHigh`；选中 → `secondaryContainer` 55%；当前播放 → `primary` 12% | `Metrics.kt:366-375`（`animateColorAsState`），三态可区分 | PASS（代码+可复核单测；截图不可 hover） |
| B-2 | 卡片 hover 显示播放按钮 | `if (hovered) { PlayBadge }` + 1.02 抬升 | `ChartsPage.kt:284-287`、`PlaylistsPage.kt:170-175`、`Metrics.kt:252`（`hoverLift`） | PASS |
| B-3 | 歌曲行右键菜单 **≥5 项** | **6 项**：播放 / 下一首播放 / 加入歌单（禁用）/ 查看歌手 / 喜欢（或取消喜欢）/ 复制链接 | `Metrics.kt:317-330`（`trackMenuActions`）、`:431-457`（DropdownMenu） | PASS |
| B-4 | 双击播放 | 400ms 内两次左键 → `onPlay()`；单击 → 选中 | `Metrics.kt:410-417` | PASS |
| B-5 | Ctrl/Shift 多选 | `selection.click(index, id, allIds, ctrl, shift)`；搜索页显示「已选 N 首」 | `Metrics.kt:416`、`SearchPage.kt`（多选条） | PASS |
| B-6 | 细滚动条（**8dp**） | API 已有：`PcVerticalScrollbar` / `PcHorizontalScrollbar`（8dp→hover 12dp）、应用级 `LocalScrollbarStyle`；**四页与 AppShell 零接入**（grep 无 `Scrollbar` 实例） | `ui/components/DesktopInteraction.kt:338-400`；`ui/pages/*.kt` 无调用 | **FAIL（未接入）** |
| B-7 | 图标按钮 hover **800ms** 出 tooltip | API 已有：`pcTooltip(text, delayMs = 800)` + 窗口级 `TooltipHost`；**页面零接入**（grep 无 `pcTooltip`/`TooltipArea` 调用） | `DesktopInteraction.kt:270`、`:293`（宿主）；`MiniPlayerBar.kt` 等无调用 | **FAIL（未接入）** |

### C. 窗口装饰

| 编号 | 标准（契约） | 实测 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| C-1 | 顶部菜单栏四个菜单（文件/播放/视图/帮助） | 4 项齐：文件（导入/导出灰显、退出 Ctrl+Q）、播放（播放暂停/上下首/音量/播放模式灰显）、视图（刷新/全屏/播放窗格/歌词）、帮助（快捷键一览/关于） | `win/AppMenuBar.kt`（133 行）、`docs/pc/WINDOW-CHROME.md:18-28` | PASS |
| C-2 | 菜单项右侧显示快捷键 | `KeyShortcut` 自动渲染，不手写字符串；菜单与键盘共用 `DesktopAction` | `AppMenuBar.kt`、`win/Shortcuts.kt`、`ShortcutsTest`（13 例） | PASS（展开态截图受 CMP 限制，见证据缺口） |
| C-3 | 窗口标题随播放曲目变化 | 无播放 `myMusic`；播放中 `正在播放 - {歌名} - myMusic`；`Win32.findMainWindow()` 按「等于或 `- myMusic` 结尾」匹配，任务栏按钮不受影响 | `WindowsShell`、`WINDOW-CHROME.md:174-181` | PASS |
| C-4 | 窗口大小/位置被记住 | 关闭写 `%APPDATA%\myMusic\window.json`（位置/大小/最大化），启动恢复；首次居中 | `win/WindowGeometry.kt:22-25`（1440×900 默认）、`WindowGeometryStore` | PASS |

### D. 播放侧

| 编号 | 标准（契约） | 实测 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| D-1 | 右侧窗格可拖动改宽（**240–480dp**） | `DockPrefs.MIN_WIDTH_DP=240` / `MAX_WIDTH_DP=480`；`DockDivider` 7dp 命中区 + E_RESIZE 光标 + 拖动 commit；`clampDockWidth` 单测 4 例 | `ui/components/PcChrome.kt:73-141`、`DockPrefsTest#宽度夹在 240 到 480 之间`、`#拖动累加后仍然不越界` | PASS |
| D-2 | 窗格可折叠 | 折叠为 44dp 竖条；宽度/折叠/播放模式落 `%APPDATA%\myMusic\dock.properties` | `PcChrome.kt`（DockPrefs 持久化）、t16 报告 | PASS |
| D-3 | 播放页封面不铺满整屏（**≤480dp**） | **320dp**（`CoverMaxSize = 320.dp`；`05-player.png` 实测 320px @1x） | `ui/pages/PlayerPage.kt`、`05-player.png`（封面 x=30→350） | PASS |
| D-4 | 进度条 hover 显示时间气泡 | `PcSeekBar`：hover 时间气泡 + 拖动 ×1.5 + 圆点 | `ui/components/PcChrome.kt`（PcSeekBar）、t16 报告 | PASS（代码+单测；截图不可 hover） |
| D-5 | 窄窗口（<1200dp）出现迷你条且高度 ≤56dp | `wide = maxWidth >= 1200.dp`；`if (!wide) MiniPlayerBar`；高度 **56dp**、封面 40dp、进度 4dp（hover 6dp） | `ui/AppShell.kt:65,77`、`ui/MiniPlayerBar.kt:51` | PASS |

---

## 三、未通过项清单（按优先级）

| 优先级 | 编号 | 问题 | 实际值 vs 标准 | 证据 | 责任人 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **P0** | A-1 | 榜单页同屏行数不足 | 15 行 vs ≥18 行（差 3 行） | `01-charts.png` | ui2 | ①轮播 320×180 → 320×120（省 60px）；②列表行 gap 4dp → 0（18 行省 72px）；③页面标题区与 chips 间距压缩 6px。合计省 138px，列表可达 648px/36=18 行。改完补 `01-charts.png` 复测 |
| **P1** | B-6 | 细滚动条未接入 | 0 处使用 vs 契约要求 | `DesktopInteraction.kt:338` 有 API；四页/AppShell 无调用 | ui2（四页）、audio2（队列列表） | 每个 `LazyColumn` 外套 `Box` + `PcVerticalScrollbar(rememberScrollbarAdapter(listState))`；AppShell 不拦截 |
| **P1** | B-7 | tooltip 未接入 | 0 处使用 vs 契约要求（800ms） | `DesktopInteraction.kt:270` 有 API；图标按钮无调用 | ui2（四页图标按钮）、audio2（迷你条/播放页按钮） | 纯图标按钮加 `Modifier.pcTooltip("播放/暂停")`；默认 800ms 已符合契约，不用改参数 |
| P2 | 观察项 1 | 左侧导航仍是 96dp 竖排（图标上、文字下） | 96dp 竖排 vs PC 惯例 180–200dp 横排 | `ui/AppNavRail.kt:44-54`（注释自述「逐字照搬 TabletNavRail」） | ui2（下一批） | 改 `NavigationRailItem` 为自绘「图标+文字」横排行，宽 180–200dp；契约未规定，故不阻塞本轮 |
| P2 | 观察项 2 | 网格封面 168dp | 168dp vs 建议 ≤160dp | `Metrics.kt:96` | ui2 | 168→160 或随列数自适应；契约未规定 |
| P2 | 观察项 3 | 右键菜单项高 48dp（M3 默认） | 48dp vs PC 惯例 28–32dp | `Metrics.kt:437`（DropdownMenuItem 未压高度） | ui2 | `Modifier.heightIn(min = 30.dp)` + 12sp |
| P2 | 观察项 4 | 列表内键盘导航与焦点环缺失 | 无 ↑↓/Enter/Delete；无 Tab 焦点环 | `Metrics.kt` 的 PcRow 无 `onKeyEvent`/`focusable` | ui2+win2 | PcRow 加 `focusRequester` + `onKeyEvent`（↑↓ 移动、Enter 播放）；`Modifier.focusable()` + 2dp primary 焦点环 |
| P2 | 观察项 5 | `UI-METRICS.md` 自相矛盾 | §2「8→18 行」vs §4「16 行」 | `docs/pc/UI-METRICS.md:53,97` | ui2 | 统一为实测值（15 完整/16 含裁切） |
| P2 | 观察项 6 | `AppShell.kt` 注释过期 | 注释「常驻 380dp 播放窗格」vs 实际 320dp | `ui/AppShell.kt:62` | audio2 | 改注释为 240–480dp 可拖动、默认 320dp |
| P2 | 观察项 7 | 未接的契约外能力无入口 | 均衡器/交叉淡化/定时关闭 UI 入口 | 设置页 | audio2（P2） | 按 t3 引擎能力补面板；不在本轮契约内 |
| P2 | 观察项 8 | 播放模式菜单只显示不生效 | 记住选择并显示 ✓，队列行为未接 | `PlaybackUseCase`/`MusicStore` | audio2（P2） | MusicStore 补 setRepeatMode/setShuffle 后接队列层 |

---

## 四、证据缺口与已知限制（不判 FAIL，但需要补证）

| 项 | 说明 | 处理 |
| --- | --- | --- |
| 榜单详情 / 歌单详情（CollectionPage）无 1440×900 截图 | `ScreenshotRunner` 8 个 Shot 无 collection 场景（`Main.kt:449-510`），A-1 对这两页只能「未验证」 | 请 ui2 在截图 runner 补 `09-collection` / `10-playlist-detail` 两个 Shot |
| 搜索页 ≥18 行无法验证 | QQ 对「晴天」只回 4 条（数据量，非布局）；结构上 32dp 行可承载 20+ 行 | 用能回 ≥18 条的查询词（如「爱」）补一张图，或注明「结构上限」 |
| 菜单展开态截图 | CMP 菜单展开依赖真实 pointer 事件，`PostMessage` 不路由到 ComposeView；`--window-shot` 只能拍收起态 | 已知限制，手动截图步骤见 `WINDOW-CHROME.md:206-211`；C-2 以 `AppMenuBar` + `ShortcutsTest` 代码为准 |
| 搜索框中文豆腐块 | `ImageComposeScene` 离屏下 `BasicTextField` 缺中文 fallback；同页普通 `Text` 正常；已试显式 `TextStyle` 无效 | 属离屏限制；真实窗口需一次人工确认（不开窗验收无法覆盖） |
| hover / 拖动 / 滚轮类交互 | 离屏截图无法触发 hover | 以代码位置 + 单测为准（`DockPrefsTest` 4 例、`ShortcutsTest` 13 例、`:desktop:test` 44 用例） |
| 高 DPI 125%/150% | 未测 | 需在缩放环境下出图；本轮未覆盖，记入下轮 |

---

## 五、核查方法与可复现命令

```powershell
# 环境（本机 JAVA_HOME 指向 temurin-25\bin 无效，需显式指 JDK 21）
$env:JAVA_HOME="D:\env\.jdks\jdk-21.0.13.1+2"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"

# 1) 全套离屏截图（1440×900，8 张，不开窗）
./gradlew :desktop:run --args="--screenshot=F:/tmp/pc-native-final"

# 2) 带菜单栏的窗口实拍（PrintWindow，不抢焦点）
./gradlew :desktop:run --args="--window-shot=F:/tmp/pc-native-final-window"

# 3) 交互/播放侧回归
./gradlew :desktop:test
```

- 本轮核查所用截图：`F:\tmp\mymusic-pc-shots-window\`（1440×900 八张 + 01-window.png 实拍，t15 批次）、
  `F:\tmp\pc-player-final\`（05-player / 06-lyrics / 01-charts，t16 批次）。
- 尺寸以 `theme/Metrics.kt` 常量为准，截图只用于验证渲染值与常量一致。

---

## 六、结论

1. **三块重做的方向正确**：行高 56→32dp、正文 16→13sp、页边距 32→20dp、五列自适应网格、
   菜单栏/动态标题/窗口记忆、窗格 380dp 固定→240–480dp 可拖动、迷你条 86→56dp —— 已从「平板放大版」
   变为 PC 密度。
2. **剩余 3 个 FAIL 都是最后一公里**：榜单页差 3 行（调整 3 个尺寸即可）、滚动条与 tooltip 有 API
   没接入（每页加 1–2 行调用）。
3. **契约已基本达成（20/23）**；建议按 P0 → P1 顺序修完后再做一次复测，即可进入「PC 原生达标」判定。

---

## 七、修复进展（WIP，未提交，2026-11-27 12:54 更新）

> captain 已叫停并行构建（由他串行跑），以下修复在**工作区**完成、**未提交**，
> 证据是 ui2 用同一 `--screenshot` 命令重跑的新图（`F:/tmp/pc-native-ui/`）。
> 本节不改写 §二/§三 的历史判定；最终 PASS 以 captain 串行构建后的复测为准。

| 编号 | 历史判定 | 修复内容 | 新证据 | 当前状态 |
| --- | --- | --- | --- | --- |
| A-1 榜单首页 | FAIL（15 行） | 轮播 180→120dp、行距 4→0、取数 18→24 | `01-charts.png`（新版）实测 **19 行完整**（第 20 行裁切） | **已达标（WIP）** |
| A-1 榜单详情 | 未验证 | 详情页按 PC 重做（64dp 封面 + 32dp 行 + 列头 + 右键/hover）；12dp 行距→0（未编译） | `09-collection.png` 实测 **17 行**（改动前） | 已修待复测（预计 23 行） |
| A-1 歌单详情 | 未验证 | 同上 | `10-playlist-detail.png` 实测 **17 行**（改动前） | 已修待复测（预计 23 行） |
| A-1 搜索页 | 数据限制 | QQ 对「爱」只回 1 条、「晴天」4 条；结构 32dp 行 + LazyColumn 支持 20+ | `11-search-dense.png` | N/A（数据限制，非布局） |
| A-4 页面标题 | PASS（22sp） | 进一步压到 **20sp**（比契约 ≤22 更严） | 代码（未重跑） | 已改（WIP） |
| 观察项：网格封面 | 168dp | 改为 **160dp** | 代码（未重跑） | 已改（WIP） |
| 观察项：轮播高 | 180dp | 改为 **120dp**（01-charts 已见效果） | `01-charts.png` | 已达标（WIP） |
| A-6 水平边距 | PASS（20dp） | 进一步压到 **16dp** | 代码（未重跑） | 已改（WIP） |
| I-03 菜单项高 | 48dp（观察项） | 改为 **30dp** | 代码（未重跑） | 已改（WIP） |
| B-6 细滚动条 | FAIL | **未做**，转 Sol（t17 收尾） | — | 仍 FAIL |
| B-7 tooltip | FAIL | **未做**，转 Sol（t17 收尾） | — | 仍 FAIL |
| 观察项：键盘导航/焦点环 | 缺失 | **未做**，转 Sol（t17 收尾） | — | 仍缺 |

**证据缺口变化**：详情页两页已补图（09/10）；搜索页改为「数据限制」结论（11）；
菜单展开态、高 DPI、Alt+Tab 仍待测。

**复测条件**（建议 captain 串行执行）：
1. `./gradlew :desktop:run --args="--screenshot=F:/tmp/pc-native-final"`（1440×900 全套 8+ 张）；
2. `./gradlew :desktop:test`（期望：44+ 用例 0 失败）；
3. 复测后把本表状态改为「已修（提交号）」，并把 §二/§三 的 FAIL 逐条关闭。
