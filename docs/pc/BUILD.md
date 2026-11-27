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

- **libVLC**：`:playback-vlc` 通过 vlcj 调用 `libvlc.dll`。**现已随包分发**——打包任务 `fetchVlcRuntime` 会把 VLC 发行包里的播放必需子集（`libvlc.dll` / `libvlccore.dll` / `plugins/` / `lua/`）抽到 `appResourcesRootDir`，运行时 `VlcInstallation.applyBundledRuntime()` 在启动第一行把它塞进 `jna.library.path`（JNA 只读一次）。开发态没随包运行库时，依次找 `VLC_HOME`、三个默认安装目录、工作目录 `vlc/`；都找不到就走模拟输出。
  - 默认从清华镜像拉 VLC 3.0.21；离线/内网构建用 `-PpcVlcZip=<本地 zip>`，或 `-PpcVlcVersion=<版本>` 换版本。`build/vlc-runtime` 已存在时不重复解压 77 MB。
- **SMTC bridge**：`desktop/native/smtc-bridge/` 是 C# 项目，`buildSmtcBridge` 任务在检测到 `dotnet` 时编译 `myMusic.SmtcBridge.exe` 并放进 `windowsAppResources`；没有 dotnet 时该任务 SKIPPED，SMTC 降级为 JNA 路径或不可用。
- **JDK**：jpackage 默认带裁剪过的运行时；`--enable-native-access=ALL-UNNAMED` 已在 desktop 的 JVM 参数里配置（JNA / vlcj / sqlite-bundled 都需要）。

## CI

`.github/workflows/pc-build.yml`（afaaa02）：

- 触发：`pc`/`main` 分支上只有 `desktop/**`、`core/**`、`playback-core/**`、`playback-vlc/**`、`gradle/**`、根构建文件变更时才跑，避免每次 Android 改动白跑 10 分钟；也可 `workflow_dispatch` 手动触发。
- 内容：`windows-latest` 上 jpackage 出 app-image / MSI / EXE，算 SHA-256，上传产物。PR 只做编译与打包验证，不上传 release。

## 已知未完成

1. ~~libVLC 运行库未打进安装包~~ → afaaa02 已做（`fetchVlcRuntime` + `applyBundledRuntime`），但**未在无 VLC 的干净机器上实测**。
2. **MSI 未产出**（WiX 下载受阻，见上）。
3. **安装/卸载未在干净环境验证**（本机要求不抢焦点，GUI 安装会弹窗）。

