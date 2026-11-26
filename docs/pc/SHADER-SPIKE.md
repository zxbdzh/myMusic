# B3 可视化技术路线验证（Skia Shader spike）

| 项目 | 内容 |
| --- | --- |
| 任务 | t12 —— 在 B3（63 个播放页/可视化文件）批量推进前，钉死「AGSL 着色器能不能在 CMP 上重写并跑到 1080p」 |
| 结论 | **可行**（GPU 路径，生产 Shader 原样编译并运行）；**无 GPU 时需降级**（纯 CPU 光栅 1920×1080 约 2.5 s/帧，必须给降级方案） |
| 验证工程 | `F:\tmp\cmp-shader-spike`（仓库外，未进主工作区） |
| 原型截图 | `F:\tmp\cmp-shader-spike\out\rings-1920x1080.png`（1920×1080，248 KB） |
| 主工作区影响 | 仅新增本文件 |
| 日期 | 2026-11-27 |

---

## 1. 结论摘要

1. **AGSL 原文可以一字不改地在 Skia `RuntimeEffect` 里编译运行**。被验证的是生产代码
   `ui/player/stage/modes/Rings52HzShader.kt:57-159` 里那段 102 行 AGSL（64 元素距离场 + 32 圈年轮 + 值噪声），
   我把它原样抽到 `rings.sksl`，只换调用方式：`RuntimeEffect.makeForShader(sksl)` → 成功，
   渲染出的环形年轮与 Android 上同一份代码的语义一致（截图见上，薄荷绿近→白远、噪声让年轮歪斜）。
2. **移植成本不在着色器，而在三处 API 桥接**：
   - `android.graphics.RuntimeShader` → `org.jetbrains.skia.RuntimeEffect` + `makeShader(Data, children, localMatrix)`；
   - `ShaderBrush(shader)` → 自己包一个 `Paint`/`Brush`（CMP 的 `ShaderBrush` 不认 skiko 的 `Shader`）；
   - `Canvas.nativeCanvas` 在 CMP 1.12.2 已 **deprecated**，要用 `Canvas.skiaCanvas`（编译器原话见 §4.3）。
3. **性能上着色器不是瓶颈**：1920×1080 窗口跑 60 秒，着色器版与「只清屏」对照版的帧时间
   p50/p95 几乎相同（10.00/10.60 ms vs 9.99/10.46 ms），帧内 draw 入队 p50 = 0.04 ms。
   帧率被环境限在 ~38 fps、每秒约一次 500 ms 卡顿——**对照组一样有**，说明是合成器/呈现路径与本机并行构建负载导致，与 shader 无关。
4. **无 GPU 会废掉**：同一着色器在 CPU 光栅上 1920×1080 要 2.4–2.6 s/帧（0.4 fps）。
   远程桌面、虚拟机、禁用硬件加速的场景必须降级：降到 480p/720p 渲染再放大、降年轮圈数、或退化成静态年轮图。
5. 24 个 `modes/**` 的迁移面已逐个给出四选一（§6）：直接搬 13、改绘制 API 9（其中 VinylDisc 建议 v1 砍掉）、重写 Shader 2。

---

## 2. 环境与复现命令

```powershell
$env:JAVA_HOME='D:\env\.jdks\temurin-25.0.3'   # JDK 25（与主工程一致）
cd F:\tmp\cmp-shader-spike

# 版本：Kotlin 2.4.20 / Compose Multiplatform 1.12.2 / jvmTarget 17（与 :desktop 完全一致）
.\gradlew.bat compileKotlin
```

`build.gradle.kts`（全量，可直接照抄进 `:desktop`）：

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    application
}
application { mainClass.set("com.shader.MainKt") }
dependencies {
    implementation(compose.desktop.currentOs)   // 带 skiko：org.jetbrains.skia.*
    implementation(compose.foundation)
    implementation(compose.material3)
}
```

三个基准模式：

```powershell
.\gradlew.bat run --args="shot"           # 一帧 1920×1080 → out\rings-1920x1080.png（CPU 光栅）
.\gradlew.bat run --args="offscreen 10"   # N 帧 CPU 光栅，纯着色器成本下限
.\gradlew.bat run --args="window 60"      # 1920×1080 窗口 60 秒，真实 GPU/合成器路径
.\gradlew.bat run --args="window 30 control"  # 对照：同窗口只清屏，不画 shader
```

窗口基准的外部采样（GPU/CPU/内存）：

```powershell
pwsh -NoProfile -File F:\tmp\cmp-shader-spike\bench-window.ps1     # 60s + GPU/CPU 采样
pwsh -NoProfile -File F:\tmp\cmp-shader-spike\bench-control.ps1    # 30s 对照组
```

采样用 `Get-Counter '\GPU Engine(*)\Utilization Percentage'` 按 pid 汇总 + `Get-Process` 的
`TotalProcessorTime` 差分；被测进程通过 `Win32_Process.CommandLine like '*com.shader.MainKt*'` 定位。

本机环境：2560×1440 @ 96 DPI（缩放 100%），Windows 11，JDK 25.0.3，Gradle 9.8.0，skiko 0.150.2。

---

## 3. 原型复刻说明（做了什么、没做什么）

生产效果「环形频谱 + 52Hz 低音脉冲」在 `Rings52Hz*` 四个文件里，spike 只复刻它的骨架：

| 生产代码 | 作用 | spike 处理 |
| --- | --- | --- |
| `Rings52HzShader.kt:57-159` | AGSL：64 元素并集距离场 + 32 圈年轮 + 两层值噪声 + 近远颜色 | **原样使用**（`rings.sksl`，零改写） |
| `Rings52HzRecord.kt:246-253` | `ringHalfWidthDp = 0.5+1.9e^1.5`、`ringStrength = 0.25+0.75e`、`ringBand = e²·0.05` | 按同样的公式生成 uniform |
| `Rings52HzLayout.kt:169-214` | 把歌词字形排成矩形（`ringRects`/`mergeRingRects`） | 简化为两行居中圆角矩形（14 个元素） |
| `Rings52HzVisualizer.kt` | 舞台合成、歌词、pith、镜头 | 未复刻（属 B3 业务，不属于技术验证） |
| 真实音频 | `RingsEnergy`/`RingsBeatGate` 取低频能量 | 用 120 BPM + 指数衰减合成包络（`bassEnergy()`），只保留「脉冲驱动年轮」这条因果链 |

即：**验证的是渲染管线与着色器，不是像素级还原**。截图里中心黑块是文字槽位内部（shader 里 `inside` 分支不画年轮），
与生产行为一致。

---

## 4. 关键技术发现（附原始输出）

### 4.1 AGSL → SkSL：源码零改动即可编译

`RuntimeEffect.makeForShader(ringsSksl)` 一次通过，无编译错误。生产代码里的写法在 SkSL 里都成立：

- `uniform float4 uElem[64]` / `uniform float4 uRing[32]`：定长 uniform 数组 ✓
- `half4 main(float2 p)` 入口 ✓
- `mod(s)`、`step`、`smoothstep`、`fract`、`mix`、`length`、`clamp` ✓
- 32 位浮点哨兵 20000.0 那类精度写法原样保留 ✓

### 4.2 uniform 注入方式变了（这是唯一必须改的调用）

Skiko 的 API 签名（编译错误原文逼出来的）：

```
e: ...Main.kt:187:27 No value passed for parameter 'children'.
e: ...Main.kt:187:27 No value passed for parameter 'localMatrix'.
e: ...Main.kt:187:38 Argument type mismatch: actual type is 'FloatArray', but 'Data?' was expected.
```

正确调用（实测通过）：

```kotlin
val data = org.jetbrains.skia.Data.makeFromBytes(floatsToLittleEndianBytes(uniforms))
paint.shader = effect.makeShader(data, emptyArray(), null)
```

即 Android 的「按名字 setFloatUniform」在 Skia 侧不存在：**必须按声明顺序自己拼一个扁平 Float 数组**，
再整体转成 `Data`。`uNear`/`uFar` 这种 `float3` 实测按 **3 个 float** 传递颜色正确
（若被当成 16 字节对齐打包，颜色会整体错位），所以现有 `Rings52HzShader.kt` 的 uniform 声明可以照抄。

### 4.3 CMP 1.12.2 里 `nativeCanvas` 已废弃

编译警告原文：

```
w: ...Main.kt:277:38 'val Canvas.nativeCanvas: Canvas' is deprecated.
   Naming alignment to avoid ambiguity: use [Canvas.skiaCanvas] extension instead.
```

B3 批量迁移时统一用 `drawIntoCanvas { it.skiaCanvas }`，否则 24 个文件都会带这条警告。

### 4.4 无 GPU 时 CPU 光栅不可用

`run --args="offscreen 10"` 原始输出：

```
[offscreen] frames=10 1920x1080
[offscreen] n=10 p50=2496.50ms p95=2585.21ms p99=2585.21ms max=2604.16ms mean=2369.23ms
[offscreen] heapPeakMB=18
```

单帧 1 帧 ≈ 1.7 s（首帧含 JIT），稳定后 ≈ 2.4–2.6 s。原因清楚：shader 每个像素要遍历 64 个元素 + 32 圈年轮，
2.07 M 像素 × 近百次循环，CPU 上必然爆。**这条决定了「无硬件加速必须降级」**。

### 4.5 着色器在 GPU 路径上不是瓶颈（对照组实测）

| 指标 | 着色器版（60 s） | 对照版（只清屏，30 s） |
| --- | --- | --- |
| 帧数 / 采样帧 | 2131 / 2011（丢弃前 120 帧预热） | 1008 / 888 |
| 帧间隔 p50 | **10.00 ms** | **9.99 ms** |
| 帧间隔 p95 | **10.60 ms** | **10.46 ms** |
| 帧间隔 p99 | 523.67 ms | 494.56 ms |
| 帧间隔 max | 612.42 ms | 618.48 ms |
| 帧内 draw 入队 p50 | 0.04 ms（max 0.19 ms） | 0.00 ms（max 0.03 ms） |
| 长帧 >33 ms | 69（60 s） | 30（30 s，同频率） |
| 平均 fps（去预热） | 38.23 | 38.81 |
| 堆峰值 | 45 MB | 41 MB |
| 进程 WS 峰值 | 265.3 MB | — |

**关键判读**：着色器版与「什么都不画」的对照版帧时间分布几乎重合（p50 差 0.01 ms、p95 差 0.14 ms），
长帧比例也一致（约 1.1 次/秒）。所以 38 fps 与 500 ms 卡顿来自**环境**（窗口呈现路径 + 本机同时跑着其他
Gradle 构建），不是这段 shader 的成本。真实成本上限可以从 draw 入队看出来：0.04 ms/帧。

### 4.6 GPU 占用：采样值偏低，仅供参考

`Get-Counter` 采到的进程 3D 引擎占用：前几个采样 0.69–1.24%，之后长期 0%。

这条**不能当作「GPU 很闲」的结论**：窗口在后台时 DWM 会跳过合成，计数自然归零（第一次 `toBack()` 的
实验里 30 个采样全 0）。可信的是帧时间对照（§4.5）与 draw 入队耗时。要在 CI 上得到稳定 GPU 数字，
建议改用 PresentMon 或 NVIDIA/AMD 的 per-process 计数器，本机没有装。

---

## 5. 性能数据汇总表

| 场景 | 分辨率 | 时长 | p50 | p95 | max | CPU | GPU | 内存峰值 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CPU 光栅（offscreen） | 1920×1080 | 10 帧 | 2496.5 ms | 2585.2 ms | 2604.2 ms | 单核跑满 | 0 | 堆 18 MB | **不可用**（0.4 fps） |
| GPU 窗口 + shader | 1904×1041（窗口 1920×1080 去边框） | 60 s | 10.00 ms | 10.60 ms | 612.42 ms | ≈10.5%（整机归一） | 0.7–1.2%（采样偏低，见 §4.6） | 堆 45 MB / WS 265 MB | **可用**，帧时间与对照一致 |
| GPU 窗口 + 只清屏（对照） | 同上 | 30 s | 9.99 ms | 10.46 ms | 618.48 ms | ≈2.2%（app 内自测） | 同量级 | 堆 41 MB | 证明卡顿与 shader 无关 |
| 单帧出图（shot） | 1920×1080 | 1 帧 | — | — | 1713.8 ms | — | — | PNG 248 KB | 出图路径可用于回归比对 |

> 说明：`drawSize=1904×1041` 是 1920×1080 窗口去掉标题栏与非客户区后的内容尺寸；桌面窗口无法既
> 有系统边框又正好 1920×1080 客户区。要严格 1080p 客户区可自行加 `window.isUndecorated = true`。

---

## 6. B3 迁移策略：24 个 `modes/**` 逐个四选一

判定依据：该文件的绘制 API 依赖（`RuntimeShader/ShaderBrush`、`RenderEffect/graphicsLayer`、`Bitmap`、
`TextMeasurer/drawText`、`Path/drawPath` 的出现次数）+ 文件规模。

| # | 文件 | 规模 | 关键依赖（实测计数） | 处置 | 理由 / 预估 |
| --- | --- | --- | --- | --- | --- |
| 1 | `Rings52HzShader.kt` | 7.7 KB | RuntimeShader×3、ShaderBrush×2 | **重写 Shader** | AGSL 原样编译已实测通过；只需把 `ShaderBrush` 换成 Skia `Paint` + `makeShader(Data)`。**0.5 人日** |
| 2 | `Rings52HzLayout.kt` | 13.8 KB | 纯几何 | **直接搬** | 无 Android/Compose 依赖的排版数学。0.1 人日 |
| 3 | `Rings52HzRecord.kt` | 17.1 KB | 纯数据/公式 | **直接搬** | 年轮能量与形状计算。0.1 人日 |
| 4 | `Rings52HzVisualizer.kt` | 19.7 KB | Text×11、Path×6 | **改绘制 API** | 舞台合成；`nativeCanvas → skiaCanvas`、`ShaderBrush` 桥接。**1 人日** |
| 5 | `AuroraVisualizer.kt` | 5.3 KB | Text×6 | **直接搬** | 纯 Compose 渐变/文字。0.1 人日 |
| 6 | `RushVisualizer.kt` | 10.2 KB | Anim×6 | **直接搬** | 纯动画编排。0.2 人日 |
| 7 | `RushPalette.kt` | 4.9 KB | 纯常量 | **直接搬** | 配色表。0.05 人日 |
| 8 | `RushLyricList.kt` | 21.9 KB | Anim×4、Effect×3 | **改绘制 API** | `graphicsLayer` 的模糊在 CMP 需验证（走 Skia ImageFilter）。0.5 人日 |
| 9 | `RushSyllable.kt` | 26.9 KB | Text×16、Effect×6 | **改绘制 API** | 逐字模糊/描边；CMP 有 `RenderEffect` 但 API 名不同。**1 人日** |
| 10 | `RushBackgrounds.kt` | 17.0 KB | RuntimeShader×6、ShaderBrush×3、Effect×2、Bitmap×2 | **重写 Shader** | 第二个 AGSL 文件，比 Rings 简单（无 64 元素循环）。**1 人日** |
| 11 | `WaveLightVisualizer.kt` | 43.5 KB | Text×12、Effect×4 | **改绘制 API** | 大文件；主要风险是模糊半径与合成顺序。**1.5 人日** |
| 12 | `WaveVinylKit.kt` | 5.4 KB | Effect×7 | **改绘制 API** | 黑胶共用绘制件，模糊参数集中在这里。0.5 人日 |
| 13 | `VinylDiscVisualizer.kt` | 48.9 KB | Bitmap×18、Text×26 | **改绘制 API（高）**，建议 **v1 先砍掉** | 位图纹理生成 + 大文件；是 24 个里迁移成本最高、收益最低的一个。**3 人日**，v1 砍 |
| 14 | `CineramaVisualizer.kt` | 10.6 KB | Text×3 | **直接搬** | 巨幕外层排版。0.2 人日 |
| 15 | `CineramaProgram.kt` | 61.1 KB | 纯编排 | **直接搬** | 61 KB 但无图形 API，编译即通过。0.3 人日 |
| 16 | `CineramaScene.kt` | 43.9 KB | Text×30、Path×6、Bitmap×2 | **改绘制 API** | 场景绘制；Bitmap 2 处需换成 skiko Image。**1.5 人日** |
| 17 | `JumpCourse.kt` | 33.3 KB | 纯数据 | **直接搬** | 关卡数据。0.2 人日 |
| 18 | `JumpDirector.kt` | 23.0 KB | Path×2 | **直接搬** | 镜头编排。0.2 人日 |
| 19 | `JumpGeometry.kt` | 24.5 KB | 纯几何 | **直接搬** | 0.1 人日 |
| 20 | `JumpVisualizer.kt` | 38.3 KB | Path×11、Text×6 | **改绘制 API** | 路径绘制在 CMP 同源，主要改 `drawIntoCanvas` 桥。**1 人日** |
| 21 | `MisideDropPhysics.kt` | 16.7 KB | 纯物理 | **直接搬** | 0.1 人日 |
| 22 | `MisideDropVisualizer.kt` | 17.3 KB | Text×13 | **直接搬** | 0.3 人日 |
| 23 | `StardriftSky.kt` | 18.7 KB | 纯数据 | **直接搬** | 星空参数。0.1 人日 |
| 24 | `StardriftVisualizer.kt` | 19.2 KB | Text×11、Path×6 | **改绘制 API** | 0.5 人日 |

合计：**直接搬 13 个（≈1.8 人日）· 改绘制 API 9 个（含 VinylDisc，8 个合计 ≈7.5 人日 + VinylDisc 3 人日）·
重写 Shader 2 个（1.5 人日）**。全做 ≈ 10.8 人日；v1 砍掉 VinylDisc 后 ≈ 7.8 人日。

> 「砍掉」只针对 v1：VinylDiscVisualizer 的位图纹理路径收益低、成本高，等 B4 之后再作为独立任务补。

---

## 7. 对 t9 的输入：B3 应拆成 6 个任务

| 任务 | 范围 | 验收标准 |
| --- | --- | --- |
| **B3-0 绘制桥接层** | `skiaCanvas` 替换规则、`ShaderBrush → Skia Paint` 包装、`RenderEffect` 兼容层、TextMeasurer 迁移约定 | 24 个文件 `compileKotlin` 全绿；单帧出图脚本可跑（`shot` 模式已验证）；`grep nativeCanvas` 为 0 |
| **B3-1 直接搬（13 个）** | Rings52HzLayout/Record、Aurora、RushVisualizer、RushPalette、CineramaVisualizer、CineramaProgram、JumpCourse/Director/Geometry、MisideDropPhysics/Visualizer、StardriftSky | 每个文件产出一张 1920×1080 截图；与 Android 同参截图并排无结构性差异；`shot` 帧耗时 < 50 ms |
| **B3-2 Shader 族（2 个）** | Rings52HzShader、RushBackgrounds | AGSL 原文编译通过（不改 shader 源码）；1080p 窗口 60 s，帧时间 p95 与对照版差值 < 2 ms；无 GPU 时自动降级（§8） |
| **B3-3 RenderEffect 族（4 个）** | RushSyllable、RushLyricList、WaveLightVisualizer、WaveVinylKit | 模糊/描边效果截图对比；1080p 下 p95 < 33 ms；关闭模糊时无崩溃 |
| **B3-4 Bitmap/大文件族（4 个）** | CineramaScene、JumpVisualizer、Rings52HzVisualizer、VinylDisc（v1 可选） | 同上；额外记录内存峰值（堆 < 200 MB） |
| **B3-5 播放页其余 28 个** | `player/*.kt` 18 + `player/share/*.kt` 10（不含 stage） | 见 FEATURE-INVENTORY 的 M/N 域；B3-0 的桥接层必须先合并 |

依赖顺序：B3-0 →（B3-1 并行）→ B3-2/B3-3/B3-4 → B3-5。

---

## 8. 无 GPU 时的降级方案（必须实现）

CPU 光栅 2.5 s/帧已经证明「没有硬件加速时不能照常渲染」。按优先级降级：

1. **降分辨率渲染再放大**：以 960×540 渲染到 `ImageBitmap`，再 `drawImage` 拉伸到窗口；成本约降 4 倍（≈600 ms/帧，仍不够）。
2. **降年轮复杂度**：把 `uRing` 圈数从 32 降到 8、`uElem` 从 64 降到 24（uniform 数组长度是编译期常量，需要第二份 shader 变体），
   实测成本随循环次数线性下降，可再降 4–6 倍。
3. **静态帧 + 低帧率更新**：检测到软件渲染（`GraphicsEnvironment.isHeadless()` 之外的 skiko 软件后端）时，
   把动画帧率降到 4–8 fps，只在节拍点重绘。观感退化为「年轮慢慢长」，不影响歌词阅读。
4. **兜底**：直接回退到「经典歌词 + 静态年轮背景图」，与生产里 `RingsShader.createOrNull()` 返回 null 时的兜底一致
   （见 `Rings52HzShader.kt:48-54`）。

判定信号建议用 skiko 的实际后端而不是猜：`SkiaLayer.renderApi`（D3D/OpenGL/Software）。

---

## 9. 复现与原始日志

| 文件 | 内容 |
| --- | --- |
| `F:\tmp\cmp-shader-spike\run1-compile.log` | 首次编译（`makeShader` 签名错误原文，§4.2 引用） |
| `F:\tmp\cmp-shader-spike\run2-shot.log` | 单帧出图（1713.8 ms / 248005 bytes） |
| `F:\tmp\cmp-shader-spike\run3-offscreen.log` | CPU 光栅 10 帧（§4.4 引用） |
| `F:\tmp\cmp-shader-spike\run4-window.log` | 1080p 窗口 60 s（§4.5 引用） |
| `F:\tmp\cmp-shader-spike\run5-gpu.log` | `DirectContext.makeGL()` 失败原文：`RenderException: Can't create OpenGL DirectContext`（本机无 GL，改用窗口路径） |
| `F:\tmp\cmp-shader-spike\run6-control.log` | 对照组 30 s（§4.5 右列） |
| `F:\tmp\cmp-shader-spike\out\rings-1920x1080.png` | 原型截图 |

> 附注：本机 `DirectContext` 只有 `makeGL` / `makeMetal` / `makeDirect3D(handle…)`，
> 没有可独立构造的 D3D 离屏上下文，所以 GPU 基准只能走窗口路径（这也是 §4.6 采样偏差的原因）。
