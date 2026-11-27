# PC 窗口装饰与交互底座（t17）

| 项目 | 内容 |
| --- | --- |
| 任务 | t17 —— 菜单栏、动态标题、窗口几何、hover/右键/tooltip/滚动条/快捷键体系 |
| 代码 | `desktop/src/main/kotlin/.../win/{AppMenuBar,Shortcuts,WindowGeometry,WindowCapture}.kt`、`desktop/.../ui/components/DesktopInteraction.kt`、`Main.kt` 装配 |
| 单测 | `desktop/src/test/kotlin/.../win/ShortcutsTest.kt`（13 例） |
| 截图 | `F:\tmp\mymusic-pc-shots-window\01-window.png`（PrintWindow 实拍，含菜单栏） |
| 验收命令 | `./gradlew :desktop:test`、`./gradlew :desktop:run --args="--window-shot=F:/tmp/mymusic-pc-shots-window"` |

---

## 1. 菜单结构

菜单栏挂在主窗口顶部（Compose Desktop 的 `MenuBar`，`FrameWindowScope` 里调用），
每一项右侧的快捷键由 `KeyShortcut` 自动渲染（不手写 "Ctrl+F" 字符串）。

```
文件(F)                  播放(P)                   视图(V)                帮助(H)
├─ 导入歌单… Ctrl+I      ├─ 播放/暂停 Space          ├─ 刷新 F5             ├─ 快捷键一览 F1
├─ 导出歌单… Ctrl+E      ├─ 上一首 Ctrl+←           ├─ 全屏 F11            ├─ ─────────────
├─ ─────────────        ├─ 下一首 Ctrl+→           ├─ ─────────────       └─ 关于 myMusic
└─ 退出 Ctrl+Q          ├─ 音量 80%（只读显示）     ├─ 显示播放窗格 Ctrl+Shift+P
                         ├─ 音量 + ↑               └─ 显示歌词 Ctrl+L
                         ├─ 音量 − ↓
                         ├─ ─────────────
                         └─ 播放模式 ▸ 顺序/单曲/随机（灰显）
```

### 菜单能力边界（为什么有的项灰显）

| 菜单项 | 状态 | 原因 |
| --- | --- | --- |
| 导入歌单… / 导出歌单… | 灰显 | `MusicStore` 还没有导入/导出能力（歌单文件需要新的 use case），不做"点了没反应"的假入口；接通后只改 `DesktopAction.enabled` 一处 |
| 播放模式 ▸ | 灰显 | 队列层（`:playback-core` 的 QueueStore）还没有 repeat/shuffle 状态；菜单先呈现结构，P2 接队列后启用 |
| 其余项 | 可用 | 全部有真实实现（见 §3 的动作映射） |

> 播放菜单的「播放/暂停」文案随状态变（正在播时显示「暂停」），音量百分比取当前值。

## 2. 快捷键全表

应用内快捷键（**只在窗口聚焦时生效**，由 `Main.kt` 的 `Window.onKeyEvent` →
`Shortcuts.resolve` → `dispatchAction` 分发）：

| 动作 | 快捷键 | 说明 |
| --- | --- | --- |
| 播放/暂停 | `Space` | 输入框聚焦时空格归输入框（Compose 的焦点机制先消费） |
| 播放/暂停 | `Ctrl+Alt+P` | 备选（媒体键被占用时） |
| 上一首 / 下一首 | `Ctrl+←` / `Ctrl+→` | |
| 快退 / 快进 5 秒 | `←` / `→` | 与 t16 播放侧对齐的步长（`SEEK_STEP_MS = 5_000`） |
| 音量 + / − | `↑` / `↓` | 步长 0.05，clamp 到 0..1 |
| 搜索 | `Ctrl+F` | 跳搜索页；输入框聚焦由 SearchPage 的 `FocusRequester` 完成 |
| 设置 | `Ctrl+,` | |
| 关闭覆盖层 | `Esc` | 按「歌词 → 播放页 → 海报墙 → 评论 → 详情」的顺序逐层关 |
| 歌词 | `Ctrl+L` | 打开全屏歌词 |
| 海报墙 | `Ctrl+P` | 保留原行为；与「显示播放窗格」区分 |
| 显示播放窗格 | `Ctrl+Shift+P` | 打开播放页覆盖层 |
| 刷新 | `F5` | 按当前页签分派：榜单 → `refreshCharts`、歌单 → `refreshPlaylists`、搜索 → `retrySearch` |
| 全屏 | `F11` | 窗口最大化/还原（`Frame.MAXIMIZED_BOTH`） |
| 快捷键一览 | `F1` | AWT 原生对话框 |
| 退出 | `Ctrl+Q` | 先 `store.shutdown()` 再退出 |

### 与系统级 GlobalHotkeys 的分工（不重复注册）

```
桌面系统 ──媒体键（0xB0..0xB3）──> GlobalHotkeys（RegisterHotKey，窗口失焦也生效）
                                        │
                                        ▼
窗口聚焦 ──应用内快捷键（Space/Ctrl+F/…）──> Shortcuts.resolve ──┐
                                                              ├──> MediaCommandSink / dispatchAction
托盘菜单 / 任务栏按钮 / SMTC 按钮 ─────────────────────────────┘
```

- 媒体键（播放暂停/上下首/停止）只由 `desktop/win/GlobalHotkeys.kt` 注册，**应用内不重复绑定**；
- 应用内快捷键全部走 `DesktopAction` 枚举，菜单项与键盘共用同一份定义
  （菜单能点的 == 键盘能按的）；
- Windows 10+ 把媒体键交给 SMTC 会话，`WM_HOTKEY` 收不到属系统行为（t5 自检记录），
  此时由 SMTC 按钮链路兜底。

## 3. 动作映射（菜单/键盘 → store）

`Main.kt` 的 `dispatchAction(action, store)` 是唯一映射点：

| DesktopAction | 调用 |
| --- | --- |
| PLAY_PAUSE | `store.togglePlay()` |
| PREVIOUS / NEXT | `store.previous()` / `store.next()` |
| VOLUME_UP / VOLUME_DOWN | `store.setVolume(volume ± 0.05)` |
| SEEK_BACKWARD / SEEK_FORWARD | `store.seekTo(position ± 5000)` |
| SHOW_LYRICS / SHOW_PLAYER_PANE | `store.openLyrics()` / `store.openPlayer()` |
| TOGGLE_POSTER_WALL | `store.openPosterWall()` |
| FOCUS_SEARCH / OPEN_SETTINGS | `store.navigate(Route.Search/Settings)` |
| CLOSE_OVERLAY | `closeLyrics/closePlayer/closePosterWall/closeComments/closeDetail` 按层关闭 |
| REFRESH | 当前页签的刷新方法 |
| FULLSCREEN / QUIT / SHORTCUT_HELP / ABOUT | 窗口/对话框（`handleMenuAction`） |

## 4. 交互底座 API（给 ui2 / audio2）

文件：`desktop/src/main/kotlin/com/mytv/mymusic/desktop/ui/components/DesktopInteraction.kt`

> 所有 `Modifier.xxx` 都通过 `composed { }` 实现；需要窗口级宿主的两个（tooltip / 右键菜单）
> 已由 `Main.kt` 在窗口内容最外层提供，页面直接调用即可。

### 4.1 hover 高亮

```kotlin
Row(
    Modifier
        .fillMaxWidth()
        .hoverHighlight()            // 100ms 过渡，onSurface 6% 混色
        .clickableRow(onClick = { select(track) }, onDoubleClick = { play(track) })
        .padding(horizontal = 12.dp, vertical = 8.dp)
)
```
可选参数：`enabled = false`（禁用态）、`shape`（默认 8dp 圆角）、`color`（自定义色）。

### 4.2 右键菜单

```kotlin
Box(
    Modifier.contextMenu {
        listOf(
            ContextMenuItem("播放", onClick = { play(track) }),
            ContextMenuItem("下一首播放", onClick = { enqueueNext(track) }),
            ContextMenuItem("收藏", shortcut = ShortcutKey(Key.S), onClick = { favorite(track) }),
            ContextMenuItem("从歌单移除", dividerAfter = true, onClick = { remove(track) }),
        )
    }
)
```
- `ContextMenuItem(label, shortcut, enabled, dividerAfter, onClick)`；
- `shortcut` 用 `ShortcutKey`（import `com.mytv.mymusic.desktop.win.ShortcutKey`），
  右侧显示文本由 `displayText()` 统一生成；
- items 用惰性 lambda：每次右键求值，菜单文案能拿到最新状态。

### 4.3 tooltip

```kotlin
IconButton(onClick = { store.togglePlay() }, modifier = Modifier.pcTooltip("播放/暂停")) {
    Icon(Icons.Filled.PlayArrow, null)
}
```
hover 800ms 出现、移开立即消失；`pcTooltip(text, delayMs = 800)` 可调节延迟。

### 4.4 细滚动条

`Main.kt` 已注册应用级 `LocalScrollbarStyle`（8dp、圆角 4dp、半透明中性灰）。
页面里想用「hover 变 12dp」的版本：

```kotlin
val listState = rememberLazyListState()
Box {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) { /* rows */ }
    PcVerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}
```
水平版：`PcHorizontalScrollbar`。需要自定义配色时用 `appScrollbarStyle(unhoverColor, hoverColor)`。

### 4.5 单击选中 / 双击播放

```kotlin
Modifier.clickableRow(
    onClick = { store.selectRow(id) },       // 单击：选中（延迟 300ms 等双击窗口）
    onDoubleClick = { store.playTrack(track) },
)
```
自动带手型光标；不传 `onDoubleClick` 时双击等价于一次单击。

## 5. 窗口标题与几何

### 动态标题

- 无播放：`myMusic`
- 有播放：`正在播放 - {歌名} - myMusic`

与 `Win32.findMainWindow()` 的匹配规则一一对应（等于 `myMusic` 或以 ` - myMusic` 结尾）。
任务栏缩略图按钮不再按逐字标题找窗口——t5 的实现已改为模式匹配，
`Main.kt` 调 `shell.attachMainWindow(window)` 不再传标题。

### 窗口几何

- 默认 1440×900，最小 960×640；
- 关闭/退出时保存位置、大小、最大化状态到 `%APPDATA%\myMusic\window.json`
  （`WindowGeometryStore`）；启动时恢复（首次或坐标缺失则居中）。
- **为什么不用 SettingsStore**：那是 DataStore 键值设置，每次写都会让 `state` 流发新快照、
  整个界面重组；拖动窗口时 geometry 每秒变几十次。独立小文件只在关闭时写一次，
  两套状态互不干扰。

## 6. 截图（含菜单栏）

`--screenshot` 是 `ImageComposeScene` 离屏渲染，没有窗口装饰（菜单栏是
`FrameWindowScope` 的东西，离屏场景不存在）。所以新增 `--window-shot`：

```powershell
./gradlew :desktop:run --args="--window-shot=F:/tmp/mymusic-pc-shots-window"
# 产出：F:/tmp/mymusic-pc-shots-window/01-window.png（PrintWindow 实拍，含菜单栏）
```

- 用 `PrintWindow(PW_RENDERFULLCONTENT=2)` + GDI 截窗口（`win/WindowCapture.kt`），
  **不抢焦点、不需要窗口在前台**；第一次实现用 `java.awt.Robot` 截屏时，
  后台进程被 Windows 前台锁挡住，拍到了用户桌面上的其它窗口——
  已改为「确认前台窗口是我们才截屏」的保护（`runWindowShot` 里保留）。
- **菜单展开态**：CMP 的菜单展开依赖真实 pointer 事件，程序化 `PostMessage(WM_LBUTTONDOWN)`
  到窗口不会被 AWT 路由到 ComposeView，所以自动截图拿不到展开态。手动方式：
  1. `./gradlew :desktop:run`
  2. 点顶部「文件」菜单，等下拉展开
  3. `Win+Shift+S` 或任意截图工具截窗口区域
  展开后的菜单项顺序与 §1 的结构图一致。

## 7. 已知限制

| 限制 | 处理 |
| --- | --- |
| 后台进程抢不到前台 | `--window-shot` 用 PrintWindow 而不是 Robot；产品窗口的 `toFront` 走 Win32 兜底（t5 的 showMainWindow） |
| 导入/导出/播放模式未接能力 | 菜单灰显 + 本文档记录，接通只改 `DesktopAction.enabled` 与 `dispatchAction` 各一处 |
| `Ctrl+Alt+*` 组合键可能被 IDE 占用 | 媒体键优先；占用情况由 `runWinSelfTest` 的 `hotkey_register` 项记录 |
| Compose MenuBar 展开态无法自动截图 | 手动截图步骤见 §6 |
