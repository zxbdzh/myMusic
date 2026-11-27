# 在线听歌分享（Android 端）设计

日期：2026-11-27。
状态：设计已定稿，等待开始实施。
范围：本仓库 `F:/github/myMusic-android` 实现；服务端仓库 `F:/github/oh-mygo-share` 只做模板白名单与类型扩展。

术语见同目录 [GLOSSARY.md](GLOSSARY.md)。本文不重复桌面版的设计理由，只记录 Android 端的取舍。

## 目标

把桌面版 Oh MyGo Music 的「分享卡片 + 在线听歌」搬到 Android：沿用本仓库已有的 10 款卡片，把选中的歌词与一段音频副本发布到使用者自托管的分享服务，得到可转发的听歌链接，并能在应用内管理、撤销。

## 已确认需求

| 编号 | 问题 | 结论 |
| --- | --- | --- |
| Q1 | 移植范围 | 整条链路：发布音频副本 → 听歌链接 → 我的分享（复制/撤销/状态）→ 设置里配服务地址与令牌 |
| Q2 | 服务地址与令牌怎么复用 | 手填服务地址 + 服务端管理令牌，两端连同一个自托管实例；令牌用现有 `SecretStore` 加密保存，按 origin 做键 |
| Q3 | 卡片样式与在线页模板 | 1:1 扩服务端模板：10 款卡片各对应一个模板名，服务端只加白名单与类型，旧的 5 个值保留不删 |
| Q4 | 系统分享面板给什么 | 图片 + 链接文字（`ACTION_SEND` + `EXTRA_TEXT`），另给独立的「复制链接」动作 |
| Q5 | 上传放哪跑 | WorkManager 前台任务（`dataSync` 通知 + 进度），离开分享页继续传，完成后由服务端接管处理 |
| Q6 | 音频范围与音质 | 整曲 / 歌词片段两种模式；音质沿用下载设置，不另做选择面板 |
| Q7 | 片段编辑页形态 | 独立全屏页（时间轴 + 双端手柄 + 试听），从分享页进入、确认后返回 |
| Q8 | 片段范围来源 | 进编辑页时手柄已落在「首句 −1 秒 ~ 末句 +1 秒」上（夹在歌曲长度内），可自由拖两端 |
| Q9 | 试听怎么播 | 独立播放器实例，只播片段范围；主播放器不受影响，离开编辑页即停 |
| Q10 | 卡片要不要二维码 | 首版不加，链接走文字与系统分享 |
| Q11 | 电视怎么降级 | 电视上隐藏「分享图片」，保留「复制链接」 |
| Q12 | 设置入口与我的分享 | 设置新增「在线分享」分区，子页自上而下：服务地址、令牌、我的分享列表 |
| Q13 | 上传前的网络提示 | 显示文件大小；蜂窝网络时二次确认 |
| Q14 | 在线分享默认开还是手动 | 手动：点「生成听歌链接」才上传 |
| Q15 | 点分享时链接还没生成 | 自动先发布，进度走完再弹系统分享面板 |
| Q16 | 同一首歌重复分享 | 先调复用接口，命中就秒回原链接，不重复上传 |
| Q17 | 片段的不可用与精度 | 没有时间戳就禁用「片段」并写明原因；手柄精度到秒；只选 1 句也允许（前后各 1 秒） |
| Q18 | 进度与状态展示 | 通知栏进度 + 分享页一行状态 + 我的分享同一套状态词 |

## 已核实的本仓库现状

| 位置 | 实际行为 | 对本次实现的约束 |
| --- | --- | --- |
| `data/SharePrefs.kt`、`ui/player/share/ShareCards.kt` | 10 款卡片样式（`Spotify/Rushed/Vertical/Couplet/AlbumArt/Hypnotic/Quote/Messy/Brat/Player`）、4 种配色、2 种圆角、21 种封面形状、全屏、副行、文字格式；Compose 绘制，导出靠 `GraphicsLayer` 抓图 | 卡片外观不动，只新增"在线听歌"这一层；样式名与模板名的映射单独维护 |
| `ui/player/share/ShareExport.kt` | 导出 PNG 到 `cache/share/`，走 `FileProvider` + `ACTION_SEND`；存相册走 MediaStore | 系统分享可直接复用；`res/xml/file_paths.xml` 已声明 `share/` 路径 |
| `ui/player/share/ShareLyricsScreen.kt` | 分享页三个动作：复制文字、存相册、分享图片 | 新增发布入口与状态行；「复制链接」是新动作 |
| `ui/player/share/LyricPickLayer.kt` | 点句子增减，最多 8 句；没有 Shift 连选（桌面靠 Shift+click） | 片段范围取"最早选句 ~ 最晚选句"，不依赖连选 |
| `ui/player/share/ShareModel.kt` | `shareRows` 插省略行、`rowsForStyle` 按样式截断、`shareText` 拼文字、`shareLink` 取平台链接 | 发布用的元数据从这一层派生，不重复实现 |
| `ui/MusicViewModel.kt`（`downloadSong`） | `resolvePlayable(forDownload = true)` → 取流 → 写文件 → 落 `DownloadRecord` | 音频准备复用它取整曲；分享用临时工作文件，不产生"已下载"记录 |
| `data/sync/SecretStore.kt` | AES/GCM + AndroidKeyStore，按名字存密文 | 服务令牌直接用它，按 origin 生成名字 |
| `data/local/LocalUploadWorker.kt` | WorkManager 前台任务 + 进度通知（本地上传） | 发布任务的形态照它，不引入新模式 |
| `ui/settings/SettingsCommon.kt` | `SettingsSection` 枚举定义路由、标题、说明、图标、色调 | 新增一个分区即可接入手机/平板/电视三套布局 |
| 现状缺口 | 没有在线发布、没有服务设置、没有我的分享、卡片上没有二维码 | 本次全部新增 |

## 与分享服务的契约

管理接口都需要 `Authorization: Bearer <服务令牌>`，并带上 `Origin: <服务地址>`。服务地址必须是 origin（无路径、无账号、无查询），只允许 HTTPS，`localhost` / 回环地址例外允许 HTTP。

| 动作 | 请求 | 成功响应 |
| --- | --- | --- |
| 创建分享 | `POST /api/shares`，头带 `Idempotency-Key`（`^[\w-]{8,128}$`，16 字节随机 hex），体为元数据快照 | `{id, status, url?, expiresAt?, error?}`，`status ∈ awaiting_upload / processing / ready / failed / revoked / expired` |
| 上传封面 | `PUT /api/shares/{id}/cover`，`Content-Type ∈ image/jpeg, image/png, image/webp` | `201` / 同内容重复 `200`，`{id, status:"cover_uploaded"}` |
| 上传音频 | `PUT /api/shares/{id}/audio`，`Content-Type ∈ audio/aac, audio/flac, audio/mp4, audio/mpeg, audio/ogg, audio/wav, audio/x-wav` | `202 {id, status:"processing"}` |
| 查询 | `GET /api/shares/{id}` | 同上 |
| 列表 | `GET /api/shares` | `{shares:[{id,title,artists,mode,template,status,url?,expiresAt?,lastOpenedAt?,error?}]}` |
| 撤销 | `DELETE /api/shares/{id}` | `204`；已撤销 `409 already_revoked` |
| 复用 | `POST /api/shares/reuse`（同一份元数据快照） | `200` 详情 / `204` 无 |

只有 `awaiting_upload` 与 `failed` 状态能上传；音频上传会被并发与存储配额拦（`429 processing_busy`、`507 storage_limit_exceeded`）。上传大小上限默认 512 MB，封面 10 MB，元数据 1 MB。

元数据快照字段（服务端逐项校验，缺一即 400）：

- 必填：`title`(≤1000)、`artists`(≤1000)、`sourceId`(≤128)、`trackId`(≤512)、`quality`(`standard|high|lossless`)、`template`、`cardSquare`(bool)、`mode`(`full|clip`)、`durationMs`、`lyrics[]`、`selectedLineIndexes[]`
- 可选：`accountId`、`playbackId`、`defaultView`(`lyrics|stage`)、`hotComments`（≤10 条）、`sourceUrl`（http/https，不带账号）、`coverHash`（sha256 hex）
- 片段模式：`selectedLineIndexes` 不能为空，`0 ≤ clipStartMs < clipEndMs ≤ durationMs`；整曲模式**不允许**带这两个字段
- 每句歌词：`text` 非空 ≤4000、`atMs ≥ 0`、`atMs < endMs ≤ durationMs`，可选 `translation`、`words[]`、`stageEndMs`

音质取值映射：应用内的 `AudioQuality` 要落到上面三档（`standard / high / lossless`），映射关系在实现时按现有音质枚举定，不在本设计里拍死。

## 模板命名

服务端白名单加 10 个值（kebab-case），旧的 5 个（`minimal/cinema/vinyl/polaroid/ticket`）保留：

| 卡片样式 | 模板名 |
| --- | --- |
| `Spotify` 色块 | `spotify` |
| `Rushed` 叠卡 | `rushed` |
| `Vertical` 竖排 | `vertical` |
| `Couplet` 对句 | `couplet` |
| `AlbumArt` 封面 | `album-art` |
| `Hypnotic` 流光 | `hypnotic` |
| `Quote` 引言 | `quote` |
| `Messy` 乱排 | `messy` |
| `Brat` 粗糙 | `brat` |
| `Player` 播放页 | `player` |

服务端目前只把 `template` 写进听歌页的 `data-theme` 属性，没有对应主题样式——也就是说这 10 个名字现阶段只是标识，视觉实现属于服务端后续单独一轮，不在本次范围。

## 交互流程

1. 长按歌词进选句层，点选句子（最多 8 句），点「分享」进分享页。
2. 分享页原有选项照旧；新增「在线听歌」区块：
   - 未发布：显示「生成听歌链接」按钮、模式切换（整曲 / 片段）、音质摘要、预计大小；片段模式下显示「片段 00:36 – 03:48 · 0:52」和「调整片段」。
   - 发布中：状态行显示「上传中 42% · 12/28 MB」→「服务端准备中」；通知栏同一套进度。
   - 已就绪：显示链接、「复制链接」「撤销」，文字格式里的「链接」开始有值。
3. 点「调整片段」进片段编辑页：时间轴（选中句子按时间画成段）+ 双端手柄 + 播放头 + 试听（播放/暂停/停止）+ 范围时间与时长；确认后回分享页。
4. 点「分享」：链接未就绪就先发布，就绪后弹系统分享面板（图片 + 文字）；「复制链接」独立可用。
5. 电视上不显示「分享图片」，只留「复制链接」。

## 默认行为与边界

- **服务地址与令牌**：地址按 origin 规范化后保存；令牌存 `SecretStore`，键由 origin 的哈希派生，换地址互不覆盖；未配置服务时整个「在线听歌」区块不出现。
- **音频准备**：先查已有下载记录（同一首、同一音质）复用文件；否则走 `resolvePlayable(forDownload = true)` 取流到临时文件。分享用临时文件，发布完成后清理，不写「已下载」记录；试听片段不落成整曲。
- **片段范围**：取最早与最晚选句的真实时间，前后各留 1 秒并夹在歌曲长度内；只选 1 句时同样前后各 1 秒。歌词没有时间戳（未同步）时禁用片段模式并说明原因，不自动改成整曲。
- **试听**：独立播放器实例，只播范围；离开编辑页停止并释放；不改变主播放器的播放位置与状态。
- **发布与幂等**：每次发布生成一个 `Idempotency-Key`；重试沿用同一个键，避免超时后重复建分享。发布前先调复用接口，同一份内容命中就复用原链接。
- **失败与重试**：准备、上传、处理三段分别失败；失败保留选句、范围与样式，可重试；部分上传不呈现为「可收听」。
- **分享文字**：发布成功后文字格式里的链接取听歌链接；未发布时该行为空（不退回平台歌曲页，避免预览和最终分享含义不一致）。
- **分享过期与撤销**：状态由服务端给出；过期/撤销后新的播放被拒，已经开始的一次收听可以听完（浏览器侧行为，应用只展示状态）。
- **电视降级**：整个分享入口按 `hasRealActivity` 判断是否存在接收方，没有就隐藏分享图片动作。

## 实施顺序

1. 服务端仓库：模板白名单与类型加 10 个值（保留旧的 5 个）。
2. 本仓库：元数据模型 + 分享服务客户端（OkHttp）+ 地址规范化（MockWebServer 单测覆盖创建/上传/复用/列表/撤销/错误码）。
3. 设置新增「在线分享」分区：地址、令牌、我的分享列表。
4. 音频准备 + WorkManager 发布任务 + 进度/状态（含蜂窝网络确认）。
5. 分享页「在线听歌」区 + 片段编辑页（范围计算纯函数单测）。
6. 系统分享/复制链接接线、电视降级、文案与状态收尾。
7. 文档与外发说明。

## 验收范围

1. 未配置服务时分享页没有「在线听歌」区块；配置后能发布整曲并拿到链接。
2. 同一份内容再次发布命中复用，不重复上传；重试不产生重复分享。
3. 片段模式的范围与选句一致，前后余量受歌曲长度限制；无时间戳时片段不可用且说明原因。
4. 上传中断/失败可重试，界面不出现「可收听」的假状态；进度在通知栏与应用内一致。
5. 我的分享能列出、复制、撤销；撤销后状态更新。
6. 系统分享面板拿到的是图片 + 链接文字；电视上没有分享图片动作。
7. 离开分享页后任务继续，完成后状态落回「我的分享」。

## 本轮未做 / 风险

- 服务端 10 套听歌页主题样式未实现，模板名目前只是标识；需要服务端仓库单独一轮。
- 未实现二维码上卡片（Q10 明确首版不做）。
- 未做音质选择面板，音质完全跟随下载设置。
- 未在真机上验证移动网络上传大文件的耗时与断点续传；服务端不支持断点续传，中断即重传。
