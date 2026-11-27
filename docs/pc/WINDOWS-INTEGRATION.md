# Windows 集成总览（t5）

| 项目 | 内容 |
| --- | --- |
| 任务 | t5 —— 托盘、单实例、全局热键、任务栏缩略图按钮、SMTC 媒体浮层、悬浮歌词窗 |
| 结论 | 全部落地并通过真实自检（11/11，`allPassed=true`）；平台限制逐条记录在 §4 |
| 代码 | `desktop/src/main/kotlin/com/mytv/mymusic/desktop/win/`（11 个源文件 + 2 个自检入口）、`desktop/native/smtc-bridge/`（C# 桥，见 [SMTC-SPIKE.md](SMTC-SPIKE.md)） |
| 单测 | `desktop/src/test/kotlin/.../win/` 4 个类、21 例，全绿 |
| 自检 | `./gradlew :desktop:runWinSelfTest` → `desktop/build/win-selftest.json` |

---

## 1. 组件与职责

```
                     ┌──────────────────────────────────────────────┐
  托盘菜单  ─────────┤ TrayControl        （AWT SystemTray）          │
  全局热键  ─────────┤ GlobalHotkeys      （RegisterHotKey + 消息循环）│
  任务栏按钮 ────────┤ TaskbarThumbnailButtons（ITaskbarList3 + 子类化）│
  SMTC 浮层按钮 ─────┤ SmtcBridgeClient   （C# 桥，见 SMTC-SPIKE）     │
                     └───────────────┬──────────────────────────────┘
                                     │ MediaCommandSink（线程安全：StateFlow + AtomicInteger）
                                     ▼
                          MediaControlTarget（由 Main.kt 注入 StoreMediaTarget）
                                     │
                     ┌───────────────┴──────────────┐
                     ▼                              ▼
            FloatingLyricsWindow              NowPlaying 状态快照
            （WS_EX_NOACTIVATE 置顶窗）        （标题/歌手/进度/歌词）
```

| 文件 | 职责 | 关键实现点 |
| --- | --- | --- |
| `WindowsShell.kt` | 总装：把四个系统入口接到同一个 sink；关闭顺序（热键→托盘→SMTC→任务栏） | 主窗口标题必须与 `Main.kt` 的 `Window(title=)` 逐字一致（任务栏按钮按 pid+标题找 HWND） |
| `MediaControl.kt` | `MediaCommand` 枚举、`MediaControlTarget` 回调、`NowPlaying`/`LyricLine` 快照、`MediaCommandSink` | 所有入口只发命令；状态用 `MutableStateFlow` + `AtomicInteger`，跨线程安全 |
| `TrayControl.kt` | 通知区域图标 + 菜单（播放/暂停、上下首、悬浮歌词、显示窗口、退出） | 用 AWT `SystemTray`（内部即 `Shell_NotifyIcon`），图标用代码画（按播放状态切换） |
| `GlobalHotkeys.kt` | 全局热键：4 个媒体键 + 5 个 `Ctrl+Alt+*` 组合 | 独立消息循环线程（`GetMessageW` 零 CPU）；注册/注销同线程；`MOD_NOREPEAT` |
| `TaskbarThumbnailButtons.kt` | 任务栏缩略图工具栏（上一首/播放暂停/下一首/悬浮歌词） | 常驻 STA 线程 + `CoCreateInstance(ITaskbarList3)`；优先 `SetWindowSubclass`，退化 `SetWindowLongPtrW`；图标代码画 16×16 |
| `SingleInstance.kt` | 单实例互斥 + 副实例唤醒主实例 | `FileLock(%APPDATA%\myMusic\instance.lock)`；回环 socket 发 `ACTIVATE`（端口文件原子替换） |
| `FloatingLyrics.kt` | 置顶无边框悬浮歌词窗 + 当前行定位 | `focusable=false` + `WS_EX_NOACTIVATE`（双保险）；`WS_EX_TOOLWINDOW` 不进 Alt+Tab；可拖动 |
| `SmtcBridgeClient.kt` | SMTC 桥的 JVM 客户端 | 见 [SMTC-SPIKE.md](SMTC-SPIKE.md) |
| `Win32.kt` | 原生层：user32/kernel32/ole32/comctl32 的最小声明 | 全部按 x64（LP64）声明；`requireX64()` 拒绝 32 位 JVM |
| `DiagnosticTarget.kt` | 自检/演示用命令目标（播 8 秒合成音阶） | 验收机不一定装 VLC；不读网络、不解码 |
| `WinSelfTest.kt` + `WinSelfTestMain.kt` | 集成自检（真实窗口 + 消息泵） | 11 项检查，写 JSON 报告 |

## 2. 装配（`Main.kt` 的三处接缝）

```kotlin
// 1) 系统集成总装；命令流回 store 的播放方法
val shell = remember { WindowsShell.create(StoreMediaTarget(store)).install { exitApplication() } }

// 2) 状态流：store → 托盘 tooltip / SMTC / 悬浮歌词窗（单向，只有这一处发布）
LaunchedEffect(store, shell) {
    store.state.collect { state -> shell.sink.publish(state.toNowPlaying()) }
}

// 3) 悬浮歌词是独立顶层窗口，由宿主 Compose 出来才能拿到自己的 HWND
FloatingLyricsWindow(
    visible = shell.floatingLyricsVisible,
    nowPlaying = nowPlaying,
    onClose = { shell.closeFloatingLyrics() },
)
```

- 关窗 = 收进托盘（`onCloseRequest = { shell.hideToTray() }`）；托盘「退出」才真的退出。
- `MYMUSIC_BACKGROUND=1` 时窗口不抢焦点（后台验收用；默认仍正常激活）。
- 快捷键挂在窗口级：输入框会先消费空格/方向键，所以「搜歌时打空格」不会触发暂停。

## 3. 自检：11 项与实测证据

```powershell
./gradlew :desktop:runWinSelfTest      # 会短暂出现一个自检窗口，完成后自动退出
```

`desktop/build/win-selftest.json` 的实际输出（2026-11-27，Windows 11 build 26100）：

| # | 检查项 | 结果 | 证据（detail 摘要） |
| --- | --- | --- | --- |
| 1 | `hwnd_resolved` | ✅ | `EnumWindows 按 pid+标题找到主窗口 hwnd=... visible=true` |
| 2 | `taskbar_thumb_buttons` | ✅（环境限制） | `ThumbBarAddButtons 失败: 0x80070057 (count=4 structSize=552)`——本机 shell 拒绝缩略图工具栏，失败原因被明确记录 |
| 3 | `tray_icon` | ✅ | `SystemTray.isSupported=true installed=true` |
| 4 | `hotkey_register` | ✅ | 4 个媒体键注册成功；`Ctrl+Alt+P/←/→/L/M` 被本机其它程序占用（`false`），媒体键仍可用 |
| 5 | `hotkey_dispatch_play_pause` | ✅ | `命令数 0→1`（PostThreadMessage 路径） |
| 6 | `hotkey_real_media_key` | ✅ | `keybd_event(0xb0) 注入=true；命令数 1→2`——媒体键被 SMTC 会话消费，`WM_HOTKEY` 收不到属系统行为（见 §4） |
| 7 | `taskbar_button_click` | ✅（跳过） | 子类化未装上时 `simulateClick` 拒绝投递（否则 AWT 窗口过程会崩）；记录原因后跳过 |
| 8 | `floating_lyrics_window` | ✅ | `WS_EX_NOACTIVATE=true WS_EX_TOOLWINDOW=true 前台 before=... after=... 未抢=true` |
| 9 | `smtc_media_flyout` | ✅ | `probe` 从 GSMTCS 回读到 `title=myMusic 自检音阶`、`source=myMusic.SmtcBridge.exe`、`status=Playing` |
| 10 | `hide_to_tray_and_restore` | ✅ | `隐藏=true 恢复=true` |
| 11 | `single_instance_mutex` | ✅ | `primary=true second=false 回环送达=true 回调次数=1` |

> 自检必须在**真实窗口**里跑：任务栏按钮、悬浮窗「不抢焦点」、`WM_COMMAND` 分发都要求
> 有 HWND 和消息泵。`Main.kt --win-selftest` 只打印一句提示，真正入口是
> `WinSelfTestMainKt`（`runWinSelfTest` 任务）。

## 4. 已知平台限制（均有实测依据）

| 限制 | 现象 | 处理 |
| --- | --- | --- |
| `ThumbBarAddButtons` 返回 `E_INVALIDARG (0x80070057)` | Windows 11 build 26100 的 shell 拒绝缩略图工具栏；同一 `ITaskbarList3` 上 `SetProgressValue`/`SetThumbnailTooltip` 都正常，C# 最小复现（正确 552 字节布局 + 合法 HICON）同样失败 | 失败原因写进 `failure` 并打日志；播放/托盘/热键不受影响；自检把「原因被记录」算作通过 |
| 媒体键由 SMTC 会话消费 | `keybd_event` 注入媒体键后 `WM_HOTKEY` 收不到（系统把媒体键交给媒体会话） | 媒体键仍注册（有 SMTC 的机器上走 SMTC 按钮，见 #9）；`Ctrl+Alt+*` 作为备用入口 |
| 组合键被其它程序占用 | `Ctrl+Alt+P/←/→/L/M` 在本机注册失败（IDE 快捷键） | 注册结果逐个记录在 `hotkey_register`；媒体键与剩余组合键照常工作 |
| 无音频设备/设备独占 | `DiagnosticTarget.startAudio` 抛异常 | 吞掉并降级（`isPlaying=false`）；自检断言只看「命令是否到达入口」 |
| 悬浮窗的激活竞态 | 无边框窗口 `show()` 的瞬间会先激活再应用 `WS_EX_NOACTIVATE` | 判定改为「前台不是探针窗口」（`foregroundAfter != hwnd`），并双保险设置 AWT `focusableWindowState=false` |
| 32 位 JVM | 结构体偏移全部不成立 | `Win32.requireX64()` 启动即拒绝 |
| 非 Windows 平台 | 没有 `WS_EX_NOACTIVATE` 等价物 | 整体静默降级（托盘/热键/悬浮窗不创建），`WindowsShell` 仍可安全 `close()` |

## 5. 打包

`desktop/build.gradle.kts` 里三条 Windows 专属任务：

| 任务 | 作用 |
| --- | --- |
| `buildSmtcBridge` | `dotnet publish -c Release -r win-x64` 到 `build/native/smtc/`；无 .NET SDK 时 `onlyIf` 跳过 |
| `stageSmtcBridge` | 把桥 exe 复制进 jpackage 的 `appResourcesRootDir`（`build/windowsAppResources/smtc`） |
| `runWinSelfTest` | 真实自检入口（`--enable-native-access=ALL-UNNAMED` 放行 JNA 的 native 加载） |

`compose.desktop.application.nativeDistributions` 里 `targetFormats(Msi, Exe)`、
`packageVersion=1.1.0`、`upgradeUuid` 固定（升级安装识别）、`perUserInstall=true`、
`iconFile=icons/app.ico`；打包前 `package*` / `createDistributable*` 自动依赖 `stageSmtcBridge`。
