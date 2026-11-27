# myMusic PC 端构建与打包

## 环境

```powershell
$env:JAVA_HOME='D:\env\.jdks\temurin-25.0.3'          # JDK 17+ 均可；本机实测 25
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"      # :app 仍要编 Android 端
```

Gradle 由仓库自带 wrapper（9.8.0）。首次构建会下载 CMP / vlcj / JNA 依赖。

## 常用命令

```powershell
# 全量测试（2020 用例）
./gradlew :core:test :app:testDebugUnitTest :desktop:test :playback-core:test :playback-vlc:test

# 桌面端直接运行
./gradlew :desktop:run

# 离屏截图（不开窗口、不抢焦点，验收用）
./gradlew :desktop:run --args="--screenshot=F:/tmp/mymusic-pc-shots"

# Windows 集成自检（托盘/热键/任务栏按钮/SMTC）
./gradlew :desktop:runWinSelfTest

# 打包
./gradlew :desktop:packageExe     # 自解压安装器，不需要额外工具链 ✅
./gradlew :desktop:packageMsi     # MSI，**需要 WiX**（见下）
./gradlew :desktop:createDistributable   # 免安装 app-image
```

## 产物

| 目标 | 路径（相对仓库根） | 本机实测 |
| --- | --- | --- |
| EXE 安装器 | `desktop/build/compose/binaries/main/exe/myMusic-1.1.0.exe` | 81.8 MB ✅ |
| app-image | `desktop/build/compose/binaries/main/app/myMusic/` | `createDistributable` 生成 |
| MSI | `desktop/build/compose/binaries/main/msi/myMusic-1.1.0.msi` | ⚠️ 见「MSI 与 WiX」 |

产物不进版本库（`desktop/build/` 已被 `.gitignore` 覆盖）。

## MSI 与 WiX

`:desktop:packageMsi` 依赖 Compose 插件的 `:downloadWix` 任务，它会从 GitHub 下载 WiX 3.14 工具集。本机实测失败：

```
> Task :downloadWix FAILED
java.net.SocketException: Connection reset
```

这是网络原因（下载源不可达），不是配置问题。两条解法：

1. **用手工装的 WiX**：安装 [WiX Toolset v3.14](https://github.com/wixtoolset/wix3/releases)，把 `candle.exe`/`light.exe` 所在目录加进 `PATH`，然后 `./gradlew :desktop:packageMsi -Pcompose.packaging.wixToolsetPath=<WiX 目录>`。
2. **先发 EXE**：`packageExe` 不需要 WiX，本机已验证可用，是当前推荐的分发形态。

## 运行期依赖

- **libVLC**：`:playback-vlc` 通过 vlcj 调用 `libvlc.dll`。用户机器需装 VLC 3.0.x，或在 `appResourcesRootDir` 里带上 `libvlc.dll` + `plugins/`（尚未做，见下）。
- **SMTC bridge**：`desktop/native/smtc-bridge/` 是 C# 项目，`buildSmtcBridge` 任务在检测到 `dotnet` 时编译 `myMusic.SmtcBridge.exe` 并放进 `windowsAppResources`；没有 dotnet 时该任务 SKIPPED，SMTC 降级为 JNA 路径或不可用。
- **JDK**：jpackage 默认带裁剪过的运行时；`--enable-native-access=ALL-UNNAMED` 已在 desktop 的 JVM 参数里配置（JNA / vlcj / sqlite-bundled 都需要）。

## 已知未完成

1. libVLC 运行库未打进安装包 —— 当前假设用户自装 VLC 3.0.x。后续把 `libvlc.dll` + `plugins/` 放进 `nativeDistributions.appResourcesRootDir` 并用 `-Dvlcj.libvlc.path` 指向它。
2. MSI 未产出（WiX 下载受阻）。
3. CI 未接入 —— `.github/workflows/` 里还没有 PC 端构建任务。
