# PC 原生验收清单（t18）

| 项目 | 内容 |
| --- | --- |
| 用途 | 把「像不像 PC 程序」变成可量化、可勾选、可复现的验收标准；t18 的核查台账 |
| 判定依据 | **t18 任务契约（A 密度 / B 交互 / C 装饰 / D 播放侧）优先**；本清单只把它展开成可测条目。初版曾自定更严数值（20sp/16dp/160dp/500ms…），已按契约修正，避免误判 |
| 基准视口 | **1440×900 逻辑像素**（Windows 100% 缩放），副视口 1920×1080、2560×1440 |
| 反例基线 | `screenshots/tablet.png`（Android 平板 2560×1600）——凡照抄平板尺寸（大卡 224dp、行高 100dp、竖排导航 80dp、标题 28sp+）的，按「平板放大版」判 FAIL |
| 判定口径 | 每条只有 **PASS / FAIL / N/A / 未验证** 四态；FAIL 必须写「实际值、差值、证据、责任人」 |
| 责任分工 | 密度四页 → ui2（t15）；播放侧 → audio2（t16）；窗口装饰与交互底座 → win2（t17）；核查与台账 → qa2（t18） |
| 纪律 | 核查者**不改产品代码**；发现问题只登记、只提修复建议，交回对应成员 |
| 日期 | 2026-11-27（首版建立基线；同日完成 t15/t16/t17 核查，见 §5） |

> 为什么要有这份清单：前几轮验收只回答「功能有没有实现」，没回答「在 PC 上好不好用」。
> 平板尺寸体系（56dp 行高、32sp 页面标题、96dp 竖排导航、380dp 侧栏）在 1440×900 的桌面上
> 呈现为「平板界面放大版」，被用户否决。本清单把每一处「像平板」的尺寸换成可测数字。
>
> 逐项证据与修复建议见 **`docs/pc/PC-NATIVE-AUDIT.md`**（t18 审计报告）。

---

## 0. 判定规则与测量方法

### 0.1 四态定义

| 状态 | 含义 | 使用条件 |
| --- | --- | --- |
| PASS | 达标 | 有截图测量值或代码位置（含单测）佐证 |
| FAIL | 不达标 | 必须同时给出：实际值、与标准的差值、证据、责任人 |
| N/A | 本轮不适用 | 必须写明理由（如功能未承诺、平台限制），由 captain 确认后生效 |
| 未验证 | 还没测 | 只在核查进行中使用，收尾时不允许残留（证据缺口须单列） |

### 0.2 证据三选一（优先截图，其次代码，最后单测）

1. **截图**：`--screenshot` 离屏渲染（不开窗口、不抢焦点），文件名 + 测量值。
2. **代码位置**：`文件:行`，如 `theme/Metrics.kt:91`（行高定义）。
3. **单测**：`类名#方法`，如 `DockPrefsTest#宽度夹在 240 到 480 之间`。

交互类（hover/右键/多选/快捷键）截图截不到，**必须**用「代码位置 + 单测」组合，并在条目的「验证」里注明。

### 0.3 离屏截图命令（仓库外，固定目录）

```powershell
$env:JAVA_HOME="D:\env\.jdks\jdk-21.0.13.1+2"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
./gradlew :desktop:run --args="--screenshot=F:/tmp/pc-native-final"      # 8 张 1440×900，离屏
./gradlew :desktop:run --args="--window-shot=F:/tmp/pc-native-final-win" # 含菜单栏实拍（PrintWindow）
```

- 截图落 `F:\tmp\pc-native-final\`，不进仓库；每个核查批次用新目录，不覆盖。
- 视口固定 **1440×900**（`Main.kt:438-439`），density 1f。

### 0.4 尺寸测法

- Composable 尺寸以代码常量（`.height(32.dp)`）为准；截图测量用于验证「实际渲染 = 常量」。
- 100% 缩放 1dp = 1px；125% 缩放 1dp = 1.25px（高 DPI 项另测）。
- 行数断言以**完整可见行**计：被迷你条 / 窗口边缘裁掉一半的行不算。

---

## 1. 密度类（A）

> 契约硬指标。1440×900 垂直预算（客户区）：
> 菜单栏 24 + 页面标题区 32 + 工具行 32 + 列表表头 24 + 迷你条 64 + 页边距 24 = **200dp**，
> 可用 700dp；行高 ≤36dp → **19.4 行 ≥ 18 行**。任何一项预算被突破，18 行都必须重算。
>
> 现状（t15 `de744c8`）：榜单页 15 行完整（+1 裁切），未达 18 —— 详见 AUDIT §三 P0。

- [ ] **A-1 同一屏可见歌曲行数 ≥18 行**（1440×900）
  - 标准：列表页（榜单/歌单/搜索/我的）可见完整歌曲行 ≥18。
  - 实测：榜单 **15 行完整 + 第 16 行被裁**（`01-charts.png`，行距 36px）；我的 **21 行**（`04-library.png`）；搜索 4 行（QQ 数据量）。
  - 判定：**FAIL（榜单差 3 行）**；我的页 PASS；详情页未验证（无截图场景）。
  - 责任人：ui2

- [x] **A-2 正文 ≤13sp**
  - 实测 **13sp**（`theme/Metrics.kt:79`）。判定：**PASS**

- [x] **A-3 区块标题 ≤15sp**
  - 实测 **15sp**（`Metrics.kt:84`）。判定：**PASS**

- [x] **A-4 页面标题 ≤22sp**
  - 实测 **22sp**（`Metrics.kt:85`）。判定：**PASS**（初版清单写 ≤20sp，按契约修正为 22sp）

- [x] **A-5 列表行高 ≤36dp（单行）/ ≤48dp（双行）**
  - 实测 **32dp / 48dp**（`Metrics.kt:91,93`）。判定：**PASS**

- [x] **A-6 页面水平内边距 ≤20dp**
  - 实测 **20dp**（垂直 16dp；`Metrics.kt:104-105`）。判定：**PASS**（初版写 ≤16dp，按契约修正）

- [x] **A-7 卡片圆角 ≤12dp**
  - 实测 **卡片 10dp / 封面 8dp**（`Metrics.kt:112-113`）。判定：**PASS**

- [ ] 观察项（不阻塞）：网格封面 168dp（`Metrics.kt:96`，建议 ≤160dp）、轮播 320×180、左侧导航 96dp 竖排（`AppNavRail.kt:44-54`，PC 惯例 180–200dp 横排）

---

## 2. PC 交互类（B）

> PC 程序的「手感」不在截图里，在 hover / 右键 / 多选 / 键盘 / 滚动条 / tooltip。
> 每条以「代码 + 单测」为准（截图不可 hover）。

- [x] **B-1 列表行 hover 变色**
  - 实测：hover 100ms → `surfaceContainerHigh`；选中 `secondaryContainer` 55%；当前播放 `primary` 12%。
  - 证据：`Metrics.kt:366-375`。判定：**PASS**

- [x] **B-2 卡片 hover 显示播放按钮**
  - 实测：`if (hovered) { PlayBadge }` + 1.02 抬升。
  - 证据：`ChartsPage.kt:284-287`、`PlaylistsPage.kt:170-175`、`Metrics.kt:252`。判定：**PASS**

- [x] **B-3 歌曲行右键菜单 ≥5 项**
  - 实测 **6 项**：播放 / 下一首播放 / 加入歌单（禁用）/ 查看歌手 / 喜欢 / 复制链接。
  - 证据：`Metrics.kt:317-330,431-457`。判定：**PASS**（菜单项高 48dp 为观察项）

- [x] **B-4 双击播放**
  - 实测：400ms 内两次左键 → `onPlay()`；单击 → 选中。
  - 证据：`Metrics.kt:410-417`。判定：**PASS**

- [x] **B-5 Ctrl/Shift 多选**
  - 实测：`selection.click(index, id, allIds, ctrl, shift)`；搜索页「已选 N 首」。
  - 证据：`Metrics.kt:416`、`SearchPage.kt`。判定：**PASS**

- [ ] **B-6 细滚动条（8dp）**
  - 标准：列表/页面容器显示 ≤8dp 细滚动条，可拖动。
  - 实测：API 已有（`PcVerticalScrollbar` 8→hover 12dp、`LocalScrollbarStyle`），**四页与 AppShell 零接入**。
  - 证据：`DesktopInteraction.kt:338-400`；`ui/pages/*.kt` 无 `Scrollbar` 实例。判定：**FAIL（未接入）**
  - 责任人：ui2（四页）、audio2（队列列表）

- [ ] **B-7 图标按钮 hover 800ms 出 tooltip**
  - 标准：纯图标按钮 800ms 出中文 tooltip。
  - 实测：API 已有（`pcTooltip(text, delayMs = 800)` + 窗口级 `TooltipHost`），**页面零接入**。
  - 证据：`DesktopInteraction.kt:270,293`；`MiniPlayerBar.kt` 等无调用。判定：**FAIL（未接入）**
  - 责任人：ui2、audio2

- [x] 补充：行内手势（单击选中/双击播放/Ctrl/Shift）由 `theme/Metrics.kt` 的 `PcRow` 实现，与 t17 的 `clickableRow` 暂存两套（TODO(t17)），本轮以实际生效的 `PcRow` 为准。
- [ ] 观察项（不阻塞）：列表内键盘导航 ↑↓/Enter（I-05）、Tab 焦点环（I-08）、Shift+F10 右键菜单键（I-14）、滚轮步进。

---

## 3. 窗口装饰类（C）

- [x] **C-1 顶部菜单栏四个菜单（文件/播放/视图/帮助）**
  - 实测：4 项齐；导入/导出/播放模式灰显并写明原因。
  - 证据：`win/AppMenuBar.kt`、`docs/pc/WINDOW-CHROME.md:18-28`。判定：**PASS**

- [x] **C-2 菜单项右侧显示快捷键**
  - 实测：`KeyShortcut` 自动渲染；菜单与键盘共用 `DesktopAction`；`ShortcutsTest` 13 例。
  - 证据：`win/Shortcuts.kt`。判定：**PASS**（展开态截图受 CMP 限制，以代码+单测为准）

- [x] **C-3 窗口标题随播放曲目变化**
  - 实测：无播放 `myMusic`；播放中 `正在播放 - {歌名} - myMusic`；`Win32.findMainWindow()` 模式匹配保证任务栏按钮不受影响。
  - 证据：`WindowsShell`、`WINDOW-CHROME.md:174-181`。判定：**PASS**

- [x] **C-4 窗口大小/位置被记住**
  - 实测：关闭写 `%APPDATA%\myMusic\window.json`（位置/大小/最大化），启动恢复。
  - 证据：`win/WindowGeometry.kt`（默认 1440×900）、`WindowGeometryStore`。判定：**PASS**

- [ ] 观察项（不阻塞）：最小尺寸 960×640（契约未规定；`WindowGeometry.MIN_WIDTH/HEIGHT`）、高 DPI 125%/150% 未测、Alt+Tab 切回未测。

---

## 4. 播放侧类（D）

- [x] **D-1 右侧窗格可拖动改宽（240–480dp）**
  - 实测：`MIN=240` / `MAX=480`，7dp 命中区 + E_RESIZE 光标 + 拖动 commit；`DockPrefsTest` 4 例。
  - 证据：`ui/components/PcChrome.kt:73-141`。判定：**PASS**

- [x] **D-2 窗格可折叠**
  - 实测：折叠为 44dp 竖条；宽度/折叠/播放模式落 `%APPDATA%\myMusic\dock.properties`。
  - 证据：`PcChrome.kt`（DockPrefs）。判定：**PASS**

- [x] **D-3 播放页封面不铺满整屏（≤480dp）**
  - 实测 **320dp**（`CoverMaxSize = 320.dp`；`05-player.png` 实测 320px）。
  - 证据：`ui/pages/PlayerPage.kt`。判定：**PASS**

- [x] **D-4 进度条 hover 显示时间气泡**
  - 实测：`PcSeekBar` hover 时间气泡 + 拖动 ×1.5。
  - 证据：`ui/components/PcChrome.kt`。判定：**PASS**（代码+单测；截图不可 hover）

- [x] **D-5 窄窗口（<1200dp）出现迷你条且高度 ≤56dp**
  - 实测：`wide = maxWidth >= 1200.dp`；`if (!wide) MiniPlayerBar`；高度 **56dp**、封面 40dp、进度 4dp（hover 6dp）。
  - 证据：`ui/AppShell.kt:65,77`、`ui/MiniPlayerBar.kt:51`。判定：**PASS**

- [ ] 观察项（不阻塞）：歌词 22sp / 当前 26sp（`LyricsPage.kt:56-57`，`06-lyrics.png` 因取流失败显示「暂无歌词」占位）、播放模式只显示不生效（队列层未接）、均衡器/交叉淡化/定时关闭 UI 入口未接（P2）。

---

## 5. 核查台账（t15 / t16 / t17 本轮结果）

> 详细证据与修复建议见 `docs/pc/PC-NATIVE-AUDIT.md`。

| 编号 | 项目 | 标准 | 实测 | 判定 | 证据 | 责任人 |
| --- | --- | --- | --- | --- | --- | --- |
| A-1 | 同屏行数 | ≥18 行 | 榜单 15 完整（+1 裁切）；我的 21；搜索 4（数据） | **FAIL（榜单）** | `01-charts.png`、`04-library.png` | ui2 |
| A-2 | 正文 | ≤13sp | 13sp | PASS | `Metrics.kt:79` | ui2 |
| A-3 | 区块标题 | ≤15sp | 15sp | PASS | `Metrics.kt:84` | ui2 |
| A-4 | 页面标题 | ≤22sp | 22sp | PASS | `Metrics.kt:85` | ui2 |
| A-5 | 行高 | ≤36 / ≤48dp | 32 / 48dp | PASS | `Metrics.kt:91,93` | ui2 |
| A-6 | 水平边距 | ≤20dp | 20dp | PASS | `Metrics.kt:104` | ui2 |
| A-7 | 卡片圆角 | ≤12dp | 10 / 8dp | PASS | `Metrics.kt:112-113` | ui2 |
| B-1 | 行 hover | 变色 | 100ms 三态 | PASS | `Metrics.kt:366-375` | ui2 |
| B-2 | 卡片 hover 播放键 | 显示 | `if(hovered)` 角标 | PASS | `ChartsPage.kt:284` | ui2 |
| B-3 | 右键菜单 | ≥5 项 | 6 项 | PASS | `Metrics.kt:317-330` | ui2 |
| B-4 | 双击播放 | 有 | 400ms 双击 | PASS | `Metrics.kt:410-417` | ui2 |
| B-5 | Ctrl/Shift 多选 | 有 | 有 + 已选 N | PASS | `Metrics.kt:416` | ui2 |
| B-6 | 细滚动条 | 8dp 可见 | API 有、**零接入** | **FAIL** | `DesktopInteraction.kt:338` | ui2/audio2 |
| B-7 | tooltip | 800ms | API 有、**零接入** | **FAIL** | `DesktopInteraction.kt:270` | ui2/audio2 |
| C-1 | 菜单栏 4 项 | 4 项 | 4 项 | PASS | `AppMenuBar.kt` | win2 |
| C-2 | 菜单快捷键提示 | 有 | KeyShortcut | PASS | `Shortcuts.kt` | win2 |
| C-3 | 动态标题 | 随曲目 | `正在播放 - 歌名 - myMusic` | PASS | `WindowsShell` | win2 |
| C-4 | 窗口记忆 | 位置/大小 | window.json | PASS | `WindowGeometry.kt` | win2 |
| D-1 | 窗格拖动 | 240–480dp | 240–480 + 4 单测 | PASS | `PcChrome.kt:73-141` | audio2 |
| D-2 | 窗格折叠 | 可折叠 | 44dp 竖条 | PASS | `PcChrome.kt` | audio2 |
| D-3 | 播放页封面 | ≤480dp | 320dp | PASS | `PlayerPage.kt`、`05-player.png` | audio2 |
| D-4 | 进度条气泡 | hover 时间 | PcSeekBar | PASS | `PcChrome.kt` | audio2 |
| D-5 | 窄窗迷你条 | <1200dp / ≤56dp | 1200 断点 / 56dp | PASS | `AppShell.kt:65,77` | audio2 |

**统计：契约 23 条 → 20 PASS / 3 FAIL。** 三处 FAIL 的修复建议（按优先级）见 AUDIT §三。

### 非阻塞观察项（8 条）

导航 96dp 竖排、网格封面 168dp、右键菜单项高 48dp、列表内键盘导航/焦点环、`UI-METRICS.md` 行数自相矛盾、
`AppShell.kt:62` 注释过期（380dp）、播放模式只显示不生效、均衡器/淡化/定时入口未接。

### 证据缺口（3 处）

榜单/歌单详情页无 1440×900 截图（截图 runner 缺 collection 场景）；搜索 ≥18 行需多结果查询词；
菜单展开态无法自动截图（CMP 限制）；高 DPI 与 Alt+Tab 未测。

---

## 6. 核查批次记录

| 批次 | 日期 | 覆盖提交 | 截图目录 | PASS / FAIL | 结论 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 2026-11-27 | t4（134425d） | `F:\tmp\mymusic-pc-shots-final` | 见初版台账 | 「平板放大版」，被用户否决 |
| 第 1 轮 | 2026-11-27 | t15 `de744c8` / t16 `a48f9c5` / t17 `0b0f719` | `F:\tmp\mymusic-pc-shots-window`（8 张 1440×900 + 实拍）、`F:\tmp\pc-player-final`（播放侧 3 张） | **20 PASS / 3 FAIL** | 密度已 PC 化；剩 1 项硬指标 + 2 项接入 |

---

## 附录 A：Android 平板 → PC 数值对照（反例基线）

| 元素 | 平板（`screenshots/tablet.png`，2x） | PC 契约标准 | 本轮实测 | 结论 |
| --- | --- | --- | --- | --- |
| 页面标题 | 56px@2x = 28sp | ≤22sp | 22sp | 达标 |
| 列表行高 | ~200px@2x = 100dp | ≤36dp | 32dp | 达标 |
| 主推卡 | 448×312px@2x = 224×156dp | 卡片圆角 ≤12dp | 10dp | 达标（尺寸 168dp 为观察项） |
| 导航栏 | 80dp 竖排（图标上文字下） | 契约未规定 | 96dp 竖排 | 观察项 |
| 正文 | 28px@2x = 14sp | ≤13sp | 13sp | 达标 |
| 迷你条 | —（平板无独立迷你条） | ≤56dp | 56dp | 达标 |
| 播放窗格 | — | 240–480dp 可拖动 | 240–480（默认 320） | 达标 |

## 附录 B：与功能口径的关系

- 本清单只管「PC 原生手感」，功能覆盖度以 `docs/pc/FEATURE-INVENTORY.md`（24 域 156 条）为准；
- 两者交集在播放侧（系统集成、错误横幅、后端标识），核查时两边都记；
- 本清单不评价「功能是否该做」，只评价「做了的那部分像不像 PC 程序」。

---

## 变更记录

| 日期 | 变更 | 作者 |
| --- | --- | --- |
| 2026-11-27 | 首版：四类 55 条（D15 / I14 / W14 / P11）+ 基线预填 + 核查流程 | qa2 |
| 2026-11-27 | 按 t18 契约修正标准数值（页面标题 22sp、水平边距 20dp、迷你条 56dp、窗格 240–480dp、封面 480dp、tooltip 800ms、右键 ≥5 项、卡片圆角 ≤12dp）；完成 t15/t16/t17 核查并填台账（20 PASS / 3 FAIL） | qa2 |
