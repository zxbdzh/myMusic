# SMTC 媒体浮层：JNA 直调 WinRT vs C# 桥（spike 报告）

| 项目 | 内容 |
| --- | --- |
| 任务 | t5 —— Windows 媒体浮层（SystemMediaTransportControls）的最小 spike |
| 结论 | **选 C# 桥（独立 .NET 进程 + stdin/stdout JSON 行）**；JNA 直调 WinRT 投影在本仓库不可行（成本与稳定性都不划算），仅作为对照记录 |
| 落地 | `desktop/native/smtc-bridge/`（C#，3 个源文件）、`desktop/src/main/kotlin/.../win/SmtcBridgeClient.kt`（JVM 客户端）、`desktop/build.gradle.kts`（`buildSmtcBridge` / `stageSmtcBridge`） |
| 实测日期 | 2026-11-27（Windows 11 build 26100，JDK 25.0.3，JNA 5.19.1，.NET SDK 10） |

---

## 1. 问题：媒体浮层只能从 WinRT 写

Windows 的媒体浮层（音量键弹出的那块「正在播放」）唯一写入口是 WinRT 的
`Windows.Media.SystemMediaTransportControls`。JVM 侧没有官方投影，所有 Java 方案都绕不开
「怎么调 WinRT」这个问题。两条候选路线：

```
路线 A：JNA 直调 WinRT COM（ISystemMediaTransportControlsInterop::GetForWindow）
路线 B：C# 桥进程（.NET 投影）+ 进程间 JSON 行协议
```

---

## 2. 路线 A：JNA 直调 WinRT —— 不可行

### 2.1 为什么看起来可行

`ISystemMediaTransportControlsInterop` 在 C++ 里是标准 COM 接口，JNA 能按虚表下标调
（本仓库的 `ITaskbarList3` 就是这么做的，见 `TaskbarThumbnailButtons.kt`）。理论上：

```kotlin
// 伪代码：按 vtable 下标取 GetForWindow
val interop = /* RoGetActivationFactory("Windows.Media.SystemMediaTransportControls") */
val smtc = interop.GetForWindow(hwnd)   // 返回 ISystemMediaTransportControls*
```

### 2.2 实际拦路的三件事

1. **WinRT 的 ABI 不是纯 COM vtable**。每个 WinRT 接口都额外继承
   `IInspectable`（`GetIids` / `GetRuntimeClassName` / `GetTrustLevel`），
   `RoGetActivationFactory` 返回的是 `IActivationFactory`，还要再 `ActivateInstance` 或
   `QueryInterface` 到目标接口——每一步都是手写 vtable 下标，任何一处错位都是进程级崩溃，
   没有编译期检查。
2. **`GetForWindow` 只是「拿到控制器」**。真正要显示信息还得再走
   `DisplayUpdater` → `MusicProperties` → `Update()`，以及 `UpdateTimelineProperties`；
   这一串投影类型在 Java 侧全部缺失，JNA 要逐个手写 `[ComImport]` 等价声明。
3. **HSTRING / IAsyncOperation 编组**。设置 `MusicProperties.Title` 需要 HSTRING，
   `RequestAsync` 系列返回 `IAsyncOperation<T>`——JNA 没有现成的 `HSTRING` 与完成回调桥，
   得自己造一套。造出来也只是「能跑」，没有可维护性。

### 2.3 结论

路线 A 的**一次性成本**（手写完整 WinRT 投影 + 调试）与**长期成本**（Windows 版本差异、
无类型安全）都不划算。仓库里已有的 JNA 用法（user32/comctl32 的 C 风格导出）不构成
「JNA 能调 WinRT」的证据——两者是完全不同的 ABI 形态。

---

## 3. 路线 B：C# 桥 —— 可行（选它）

### 3.1 形态

独立进程 `myMusic.SmtcBridge.exe`，JVM 通过 stdin/stdout 的 JSON 行通信：

```
JVM ──{"op":"update","status":"playing","title":..,"artist":..,"album":..,
        "durationMs":..,"positionMs":..,"canSeek":true,"canNext":true,"canPrev":true}──> 桥
JVM ──{"op":"probe"}──> 桥
JVM ──{"op":"quit"}──> 桥

桥 ──{"event":"ready"}──> JVM
桥 ──{"event":"error","message":..}──> JVM
桥 ──{"event":"button","button":"play|pause|stop|next|previous"}──> JVM
桥 ──{"event":"probe","source":..,"title":..,"artist":..,"album":..,"status":..}──> JVM
```

为什么用 stdin/stdout 而不是命名管道 / 本地 socket：

- 管道随父进程生命周期结束（父进程死 → stdin EOF → 桥退出），不会留孤儿进程；
- 不占端口、不需要防火墙放行、不需要处理 ACL；
- jpackage 打出来的 exe 直接 `ProcessBuilder` 拉起即可。

### 3.2 写入侧：为什么用 `MediaPlayer.SystemMediaTransportControls`

`SmtcHost.cs` 里两个候选：

| 方案 | 结论 |
| --- | --- |
| `ISystemMediaTransportControlsInterop::GetForWindow` | COM interop 接口，C#/WinRT 投影里没有公开类型，要自己声明 `[ComImport]` + vtable 顺序；且需要窗口句柄——桥是独立进程，没有窗口 |
| `MediaPlayer.SystemMediaTransportControls` | 官方投影，解包（无 package identity）的 Win32 应用实测可用，不要求窗口句柄 |

选后者。四个媒体按钮全部 `IsEnabled = true`（SMTC 只显示被启用的按钮，全关就是一条空栏），
按钮事件只转发语义、不在桥上改状态——真正的播放状态由 JVM 侧回推 `update`，
避免「系统浮层和播放器各说各话」的竞态。

### 3.3 坑：NativeAOT 发布拿不到系统会话

同一份代码：

| 发布方式 | 产物大小 | 系统媒体浮层 |
| --- | --- | --- |
| `dotnet publish -p:PublishAot=true` | ~4 MB | **不出现**（`IsEnabled=True` 但 GSMTCS 里没有会话） |
| `dotnet publish -c Release -r win-x64`（JIT，默认） | ~11 MB | **正常出现**，`probe` 能回读到标题 |

结论：桥按 **JIT 发布**（`desktop/build.gradle.kts` 的 `buildSmtcBridge` 任务就是这么调的），
不去追 NativeAOT 的体积收益。11 MB 对一个可选增强进程可以接受。

### 3.4 回读验证：`probe` 是验收证据

`probe` 走的是**只读**的 `GlobalSystemMediaTransportControlsSessionManager`（GSMTCS），
与写入侧的 `MediaPlayer` 无关，因此不会「自证」。自检里回读到的原始 JSON：

```json
{"event":"probe","source":"myMusic.SmtcBridge.exe","title":"myMusic 自检音阶",
 "artist":"Windows 集成自检","album":"smoke","status":"Playing"}
```

`source` 是 `myMusic.SmtcBridge.exe` 说明**系统媒体会话确实是我们的桥进程**，
`title` 与 JVM 侧 `publish` 的标题一致——这就是「Windows 媒体浮层显示曲目信息」的实测证据。

---

## 4. 降级与容错

桥是**可选增强**，任何一步失败都只影响媒体浮层：

| 失败点 | 行为 |
| --- | --- |
| 构建机没有 .NET SDK | `buildSmtcBridge` 任务 `onlyIf` 跳过，`:desktop` 构建不受影响；运行期 `state=DISABLED`，`lastError` 说明原因 |
| 桥 exe 缺失 / 启动失败 | `state=FAILED`，播放/托盘/热键全部照常 |
| 桥中途退出 | 读线程感知 EOF → `state=FAILED`，不重试（下次启动自然重来） |
| 系统版本过老（无 `UpdateTimelineProperties`） | 桥内 `try/catch` 吞掉，进度条缺失、其它信息正常 |

JVM 侧客户端（`SmtcBridgeClient.kt`）的三个健壮性约定：写侧加锁（状态更新来自 Compose
主线程与引擎回调，不能交错半行）、读侧单线程按行解析、`probe` 结果经阻塞队列交给等待方。

---

## 5. 复现

```powershell
# 1) 编译桥（需要 .NET SDK；没有就跳过，安装包只是没有媒体浮层）
./gradlew :desktop:buildSmtcBridge

# 2) 跑真实自检（会短暂出现一个自检窗口，完成后自动退出）
./gradlew :desktop:runWinSelfTest
# 报告：desktop/build/win-selftest.json 里的 smtc_media_flyout 一项

# 3) 单测（桥存在时不会跳过）
./gradlew :desktop:test --tests "*WindowsIntegrationSmokeTest*"
```

报告字段含义：`bridge=` 是找到的 exe 名，`state=READY` 表示握手成功，
`expected=` 是 JVM 侧发布值，`probe=` 是系统侧回读值——两者一致即通过。
