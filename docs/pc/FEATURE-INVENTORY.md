# myMusic 功能对齐基线（Android 侧清单）

| 项目 | 内容 |
| --- | --- |
| 用途 | t9 的前置标尺：把 Android 侧**用户能感知**的功能逐条列出，t9 再往「PC 实现状态」列填进度 |
| 范围 | 只做 Android 侧清单与「PC 可行性判定」，不含 PC 实现状态、不含排期 |
| 证据口径 | 每行给出仓库内 `文件:行`（源码）或 `README.md:行`（文档段落）；路径均可在仓库直接 grep |
| 判定口径 | `一比一` 桌面可原样实现 · `降级` 能实现但弱于 Android（写明弱在哪）· `需替代` 桌面无对应物，给替代方案 · `不适用` 桌面无意义 |
| 统计 | 24 个域，共 156 条 |
| 日期 | 2026-11-27（源码当时布局：多数 Provider 已搬进 `core/`，见 t2 结果） |

> 说明：判定只回答「这件事在 Windows 桌面上能不能做、能做到几分」，不回答「我们做不做」。
> 涉及系统能力的（小组件、词幕、折叠屏、TV 遥控、识曲、系统安装器）判定为「需替代/不适用」，
> 替代方案写在理由列，t9 据此决定是否落 PC 版。

---

## A. 音源与账号（F-001 ～ F-021）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-001 | 网易云 | 搜索、播放、看评论、收藏到网易云账号 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NetEaseProvider.kt:77` | 一比一 | 纯 OkHttp + 签名，无 Android API；桌面可直接用 |
| F-002 | QQ 音乐 | 搜索、歌单、歌词、登录 Cookie | `core/src/main/kotlin/com/mytv/mymusic/data/native/QQProvider.kt:74` | 一比一 | 同上；扫码登录走 WebView 的部分见 F-147 |
| F-003 | 酷狗音乐 | 搜索、榜单、云盘歌单、福利页 | `core/src/main/kotlin/com/mytv/mymusic/data/native/KugouProvider.kt:75` | 一比一 | 纯 JVM，已零改动搬进 `:core` |
| F-004 | 酷我音乐 | 搜索、无损取流、歌单 | `core/src/main/kotlin/com/mytv/mymusic/data/native/KuwoProvider.kt:57` | 一比一 | 加密用 `NativeCrypto`（javax.crypto），跨平台 |
| F-005 | 咪咕音乐 | 搜索、试听/完整音源判定 | `core/src/main/kotlin/com/mytv/mymusic/data/native/MiguProvider.kt:62` | 一比一 | 纯 JVM |
| F-006 | Apple Music 浏览与试听 | 未登录也能搜、能试听片段 | `app/src/main/java/com/mytv/mymusic/data/native/AppleProvider.kt:56` | 降级 | 试听片段可用；整曲要 Widevine（F-007），桌面拿不到 CDM |
| F-007 | Apple Music 会员整曲（Widevine） | 登录后 Apple 从试听变成整曲 | `app/src/main/java/com/mytv/mymusic/playback/AppleWidevineDrm.kt:48` | 需替代 | Windows 上的 VLC/ExoPlayer 都没有可用 Widevine CDM；只能 CEF+CDM 或维持试听 |
| F-008 | Joox | 搜索与播放 | `core/src/main/kotlin/com/mytv/mymusic/data/native/JooxProvider.kt:39` | 一比一 | 纯 JVM |
| F-009 | 千千音乐 | 搜索与播放 | `core/src/main/kotlin/com/mytv/mymusic/data/native/QianqianProvider.kt:37` | 一比一 | 纯 JVM |
| F-010 | B 站音源 | 用视频音轨当音乐源 | `core/src/main/kotlin/com/mytv/mymusic/data/native/BilibiliProvider.kt:70` | 一比一 | 纯 JVM，CDN 选路由 `BilibiliCdn` 处理 |
| F-011 | 五sing | 搜索与播放 | `core/src/main/kotlin/com/mytv/mymusic/data/native/FivesingProvider.kt:64` | 一比一 | 纯 JVM |
| F-012 | Jamendo | 版权开放曲库搜索播放 | `core/src/main/kotlin/com/mytv/mymusic/data/native/JamendoProvider.kt:55` | 一比一 | 纯 JVM |
| F-013 | Spotify | 搜索、歌单、播放地址解析 | `app/src/main/java/com/mytv/mymusic/data/native/SpotifyProvider.kt:71` | 一比一 | 唯一 Android 依赖是 `android.util.Base64`，换 `java.util.Base64` 即可（4 行改动） |
| F-014 | YouTube Music | 直连搜索播放、公开歌单 | `core/src/main/kotlin/com/mytv/mymusic/data/native/YouTubeMusicProvider.kt:64` | 一比一 | 纯 JVM |
| F-015 | music.op.wiki 聚合源 | 匿名搜多平台并按需准备音频 | `app/src/main/java/com/mytv/mymusic/data/native/MHubProvider.kt:62` | 一比一 | 纯 JVM；证书绕过逻辑要换成 OkHttp 信任配置（见 F-146 备注） |
| F-016 | 汽水音乐 | 搜索、播放、扫码登录、逐首解密 | `core/src/main/kotlin/com/mytv/mymusic/data/native/SodaProvider.kt:80` | 降级 | 取流与解密可用；扫码登录依赖隐藏 WebView（F-148），桌面要换载体 |
| F-017 | 云听 | 电台/直播流播放 | `core/src/main/kotlin/com/mytv/mymusic/data/native/YuntingProvider.kt:42` | 一比一 | 纯 JVM |
| F-018 | 洛雪自定义音源 | 导入脚本后多出一个统一「洛雪」源 | `app/src/main/java/com/mytv/mymusic/data/lx/LxSources.kt:32` | 需替代 | 执行器是 QuickJS-Android（`lib-runtime-lx` 的 JNI），桌面无对应产物，要换 Javet（ADR §4.2 已定） |
| F-019 | Subsonic 音乐服务器 | 添加 Navidrome/Airsonic，独立音源 | `core/src/main/kotlin/com/mytv/mymusic/data/subsonic/SubsonicProvider.kt:30` | 一比一 | 纯 OkHttp + 自写 XML 解析 |
| F-020 | 本地音乐作为音源 | 扫描本机音频，参与播放与歌单 | `app/src/main/java/com/mytv/mymusic/data/local/LocalLibrary.kt:333` | 需替代 | 桌面没有 MediaStore，改 `Files.walk` + 元数据解析（见 H 域） |
| F-021 | 云盘上传音源（网易云盘 / WebDAV） | 本地歌上传后换设备也能播 | `core/src/main/kotlin/com/mytv/mymusic/data/cloud/CloudUploadQueue.kt:62` | 一比一 | 队列与上传是纯 JVM + OkHttp |
| F-022 | QQ 音乐云盘（只读） | 查看/播放已匹配的云盘歌，不能上传 | `core/src/main/kotlin/com/mytv/mymusic/data/cloud/qq/QqCloudApi.kt:255` | 一比一 | 只读接口，无平台依赖 |
| F-023 | 平台注册与排序 | 音源启停、优先级决定换源顺序 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProviders.kt:32` | 一比一 | 注册表是纯 Kotlin，桌面与 Android 共用同一份 |

---

## B. 聚合搜索与换源（F-024 ～ F-030）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-024 | 聚合搜索 | 一次搜索同时查多个平台 | `core/src/main/kotlin/com/mytv/mymusic/aggregate/AggregateSearchEngine.kt:18` | 一比一 | 纯协程并发 + OkHttp |
| F-025 | 同一录音合并 | 各平台同一首歌合成一条结果 | `core/src/main/kotlin/com/mytv/mymusic/aggregate/TrackMatcher.kt:21` | 一比一 | 纯算法（归一化 + 相似度） |
| F-026 | 可用性标注 | 每条结果标完整/试听/VIP/无音源 | `core/src/main/kotlin/com/mytv/mymusic/aggregate/Availability.kt:15` | 一比一 | 纯判定逻辑 |
| F-027 | 换源面板 | 播放页点开逐平台挑音源 | `app/src/main/java/com/mytv/mymusic/ui/SourcePickerSheet.kt:75` | 一比一 | Compose 面板，桌面同源 |
| F-028 | 换源候选与回退 | 取流失败自动换下一个平台 | `core/src/main/kotlin/com/mytv/mymusic/aggregate/SourceResolver.kt:13` | 一比一 | 纯逻辑 |
| F-029 | 平台健康度 | 连续失败的平台在列表里降权/灰掉 | `core/src/main/kotlin/com/mytv/mymusic/aggregate/ProviderHealth.kt:5` | 一比一 | 纯状态机 |
| F-030 | 洛雪内部尝试顺序 | 洛雪源内部按脚本列表逐个试 | `core/src/main/kotlin/com/mytv/mymusic/data/lx/LxAttemptOrder.kt:6` | 一比一 | 顺序计算纯 Kotlin；执行器替换见 F-018 |

---

## C. 榜单（F-031 ～ F-034）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-031 | 榜单页 | 平台榜轮播 + 飙升榜第一名 | `app/src/main/java/com/mytv/mymusic/ui/charts/ChartsScreen.kt:137` | 一比一 | Compose 布局，桌面直接复用 |
| F-032 | 榜单详情 | 播放全部、排序、逐曲音源状态 | `README.md:38` | 一比一 | 复用同一套媒体列表组件 |
| F-033 | 榜单评论 | 打开榜单里的歌曲评论 | `app/src/main/java/com/mytv/mymusic/ui/charts/CommentsSheet.kt:57` | 一比一 | 纯 UI + 接口 |
| F-034 | 榜单来源胶囊 | 切换榜单内容但播放仍走聚合 | `README.md:37` | 一比一 | 交互与数据同源 |

---

## D. 歌单（F-035 ～ F-041）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-035 | 歌单首页 | 推荐与分类共用一个浏览上下文 | `app/src/main/java/com/mytv/mymusic/ui/playlists/PlaylistsScreen.kt:113` | 一比一 | Compose |
| F-036 | 平台歌单浏览 | 浏览各平台推荐/分类歌单 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProvider.kt:104` | 一比一 | 接口 + OkHttp |
| F-037 | 自建歌单 | 新建、加歌、编辑排序 | `app/src/main/java/com/mytv/mymusic/ui/library/LibraryScreen.kt:186` | 一比一 | Room + Compose，桌面同源 |
| F-038 | 写回平台歌单 | 在 app 里改平台歌单的曲目与顺序 | `core/src/main/kotlin/com/mytv/mymusic/data/native/PlaylistWrite.kt:1` | 一比一 | 接口与错误枚举在 `:core`，无平台依赖 |
| F-039 | 绑定平台歌单 | 自建歌单跟随平台内容更新 | `app/src/main/java/com/mytv/mymusic/data/BoundPlaylists.kt:8` | 一比一 | 纯 diff 计算 |
| F-040 | 绑定歌单的编辑保护 | 编辑后转手动维护，不被平台冲掉 | `app/src/main/java/com/mytv/mymusic/data/BoundPlaylists.kt:18` | 一比一 | 纯规则函数 |
| F-041 | 歌单详情海报视图 | 详情页一键切封面墙 | `README.md:129` | 一比一 | 复用 L 域的海报墙组件 |

---

## E. 听歌模式（F-042 ～ F-044）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-042 | 听歌模式入口 | 场景/心情/风格驱动的推荐流 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProvider.kt:108` | 一比一 | 接口层面无 Android 依赖 |
| F-043 | 模式歌曲批次 | 同一模式每次拉一批新歌 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProvider.kt:112` | 一比一 | 同上 |
| F-044 | 模式状态刷新 | 切换模式时刷新列表与封面 | `app/src/main/java/com/mytv/mymusic/ui/AppUi.kt:131` | 一比一 | Compose 状态机 |

---

## F. 我的（F-045 ～ F-048）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-045 | 喜欢 | 一键收藏到本机（并可同步到平台账号） | `README.md:99` | 一比一 | Room + 平台接口 |
| F-046 | 最近播放 | 记录并展示最近播放列表 | `README.md:99` | 一比一 | Room |
| F-047 | 下载管理 | 我的 → 下载，可删除本地文件 | `README.md:175` | 一比一 | 文件管理换 `java.nio.file` 即可 |
| F-048 | 可撤销操作 | 移出歌单/取消收藏/清记录可撤销 | `README.md:99` | 一比一 | 纯 UI 状态 + Snackbar |

---

## G. 搜索（F-049 ～ F-053）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-049 | 提交才请求 | 输入不触发搜索，回车才查 | `app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:303` | 一比一 | Compose 输入事件；桌面还能加 Ctrl+Enter |
| F-050 | 搜索类型 | 单曲 / 歌单 / 专辑三个页签 | `README.md:98` | 一比一 | UI + 接口 |
| F-051 | 链接解析 | 粘贴平台链接显示「解析链接」 | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProvider.kt:52` | 一比一 | 纯解析 |
| F-052 | 识曲（听几秒识别） | 录音几秒识别出歌名 | `app/src/main/java/com/mytv/mymusic/recognize/NeteaseFingerprinter.kt:22` | 需替代 | 录音走 `AudioRecord`；桌面改 `TargetDataLine` 可取音，但需要麦克风权限与设备，功能面缩到「有麦可用」 |
| F-053 | 语音搜索 | 语音输入关键词 | `app/src/main/java/com/mytv/mymusic/ui/search/VoiceSearch.kt:64` | 需替代 | 依赖系统语音识别 Intent；桌面可接系统听写或直接砍掉，改键盘输入 |

---

## H. 本地音乐（F-054 ～ F-060）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-054 | 扫描本机音乐 | 扫手机里的音频入库 | `app/src/main/java/com/mytv/mymusic/data/local/LocalLibrary.kt:333` | 需替代 | MediaStore 无桌面对应物，改目录遍历（可保留「只扫某几个文件夹」的语义） |
| F-055 | 只添加文件夹 | 只把选中的目录加入曲库 | `README.md:100` | 一比一 | 桌面文件对话框选目录更自然 |
| F-056 | 自动配封面与歌词 | 有标签按标签找，无标签听几秒识别 | `app/src/main/java/com/mytv/mymusic/data/local/LocalMatcher.kt:33` | 降级 | 标签匹配可做；「听几秒识别」受 F-052 限制 |
| F-057 | 同名 .lrc 优先 | 同目录 `.lrc` 优先于平台歌词 | `app/src/main/java/com/mytv/mymusic/data/local/LocalLrc.kt:12` | 一比一 | 纯文件读取 + 解析 |
| F-058 | 只改应用内显示 | 补全的标题/歌手不写回文件 | `app/src/main/java/com/mytv/mymusic/data/local/LocalTags.kt:13` | 一比一 | 写库不写文件 |
| F-059 | 本地歌回退播放 | 本机文件→云盘副本→平台版本依次尝试 | `app/src/main/java/com/mytv/mymusic/data/local/LocalPlayback.kt:10` | 一比一 | 纯解析顺序 |
| F-060 | 本地歌上传云盘 | 逐首上传、可取消、仅 Wi-Fi 默认 | `app/src/main/java/com/mytv/mymusic/data/local/LocalUploads.kt:50` | 降级 | 上传可做；「仅 Wi-Fi」在桌面要换成「仅不限量网络」或直接去掉 |

---

## I. 音乐库同步（F-061 ～ F-070）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-061 | GitHub Gist 同步 | 用私密 Gist 当同步位置 | `app/src/main/java/com/mytv/mymusic/data/sync/PlaylistSyncProvider.kt:86` | 一比一 | OkHttp |
| F-062 | Gitee 代码片段同步 | 用 Gitee 片段当同步位置 | `app/src/main/java/com/mytv/mymusic/data/sync/PlaylistSyncProvider.kt:173` | 一比一 | OkHttp |
| F-063 | WebDAV 同步 | 坚果云/Nextcloud/群晖 | `app/src/main/java/com/mytv/mymusic/data/sync/WebDavSyncProvider.kt:22` | 一比一 | OkHttp + 自写 XML |
| F-064 | 导入导出 JSON | 导出本机库到文件、再导入 | `app/src/main/java/com/mytv/mymusic/data/transfer/LxListCodec.kt:62` | 一比一 | 纯序列化 |
| F-065 | 三方合并 | 基线/本机/云端逐歌单合并 | `app/src/main/java/com/mytv/mymusic/data/sync/SyncMerge.kt:27` | 一比一 | 纯算法 |
| F-066 | 墓碑删除 | 删掉的歌单不会被旧数据复活 | `README.md:183` | 一比一 | Room 墓碑表 |
| F-067 | 防抖推送与重试 | 改完约 1 秒推送，离线自动重试 | `app/src/main/java/com/mytv/mymusic/data/sync/SyncCoordinator.kt:133` | 一比一 | 协程 + WorkManager 可换桌面调度器 |
| F-068 | 绑定歌单同步 | 一台设备解绑，其他设备转手动 | `README.md:185` | 一比一 | 纯同步字段 |
| F-069 | 令牌加密保存 | 令牌/密码只在本机，不入库 | `app/src/main/java/com/mytv/mymusic/data/sync/SecretStore.kt:13` | 降级 | Android 用 Keystore；桌面可换 DPAPI/凭据管理器，安全性依赖平台实现 |
| F-070 | TV 扫码配置同步 | 电视出二维码，手机网页填配置 | `app/src/main/java/com/mytv/mymusic/data/sync/TvInputPanelServer.kt:364` | 需替代 | 桌面不需要「手机帮电视填」；可保留局域网网页配置作为可选通道，或直接去掉 |

---

## J. 歌词（F-071 ～ F-077）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-071 | 歌词解析 | 支持 LRC / TTML（含逐字） | `app/src/main/java/com/mytv/mymusic/ui/LyricsParser.kt:27` | 一比一 | 纯解析 |
| F-072 | 歌词流水线 | 拉取→解析→对齐播放进度 | `app/src/main/java/com/mytv/mymusic/ui/LyricPipeline.kt:15` | 一比一 | 纯逻辑 |
| F-073 | 原文/译文分两行 | 一行塞两语的歌词拆成两行 | `app/src/main/java/com/mytv/mymusic/data/AppStore.kt:104` | 一比一 | 纯文本处理 |
| F-074 | 简体转繁体 | 播放页可切换繁体 | `app/src/main/java/com/mytv/mymusic/data/AppStore.kt:105` | 一比一 | 转换表纯 Kotlin |
| F-075 | 日韩歌词注音 | 给假名/韩文补注音 | `README.md:113` | 一比一 | Android 上受系统版本限制，桌面反而无此限制 |
| F-076 | 歌词行副显示切换 | 下面一行显示翻译/注音/都显示 | `app/src/main/java/com/mytv/mymusic/data/AppStore.kt:80` | 一比一 | 纯 UI 选项 |
| F-077 | 歌词偏移与手动搜索 | 歌词对不齐时可调、可换一版 | `README.md:110` | 一比一 | 纯交互 |

---

## K. 歌词动画（F-078 ～ F-089）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-078 | 经典整页歌词 | 逐行高亮、当前句保持视觉中心 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:38` | 一比一 | 纯 Compose 列表 |
| F-079 | Rush 动画 | 逐字推进的歌词排版 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:19` | 一比一 | 纯 Compose 布局 + 动画 |
| F-080 | 虹光（Aurora） | 背景光晕随节拍流动 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:20` | 一比一 | Skia 渐变，CMP 支持 |
| F-081 | 52Hz | 环形声波随逐字进度推进 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:21` | 降级 | Shader 在桌面要走 Skia `RuntimeEffect` 重写，性能待实测 |
| F-082 | MiSide 掉落 | 歌词块带物理掉落 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:22` | 降级 | 纯物理可移植，但帧率与手感需重调 |
| F-083 | 动感波光 | 波形随频谱起伏 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:23` | 降级 | 依赖实时频谱（VLC 回调，见 F-107） |
| F-084 | 黑胶唱片 | 旋转唱片 + 歌词环绕 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:24` | 一比一 | 纯图形变换 |
| F-085 | 巨幕（Cinerama） | 电影画幅的歌词排布 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:25` | 降级 | 大量自绘与转场，需重建时间轴 |
| F-086 | 星潮·银河 | 星空粒子随歌词漂移 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:26` | 降级 | 粒子多，桌面 GPU 路径不同，需性能验证 |
| F-087 | 跳一跳 | 歌词按节奏跳跃 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/LyricVisualizers.kt:27` | 一比一 | 纯动画 |
| F-088 | 随机 / 按速度选 | 每首歌随机换，或慢歌快歌各用哪种 | `app/src/main/java/com/mytv/mymusic/ui/player/stage/StageChoices.kt:22` | 一比一 | 纯选择逻辑 |
| F-089 | 减少动画回退 | 系统「减少动画」时显示静止画面 | `README.md:111` | 需替代 | 桌面没有系统级减少动画开关，改读 app 内设置或 Windows 辅助功能标志 |

---

## L. 海报墙（F-090 ～ F-095）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-090 | 队列海报墙 | 播放队列铺成可四向拖动的封面墙 | `app/src/main/java/com/mytv/mymusic/ui/poster/PosterWall.kt:109` | 一比一 | 纯 Compose 布局与手势，桌面换成鼠标拖拽 |
| F-091 | 详情页海报视图 | 歌单/专辑/榜单详情一键切墙 | `app/src/main/java/com/mytv/mymusic/ui/poster/PosterWallScreen.kt:109` | 一比一 | 同上 |
| F-092 | 听歌模式带状排布 | 推荐流排成一条往前长的带子 | `README.md:130` | 一比一 | 纯布局算法 |
| F-093 | 墙内镜头跟随 | 切歌时镜头移到大卡 | `README.md:128` | 一比一 | 纯动画 |
| F-094 | 墙内设置共享 | 染色/关灯/序号/翻译等墙内共用 | `README.md:132` | 一比一 | 本机偏好存储 |
| F-095 | 按格取图 | 大卡清晰、小格不下原图 | `README.md:133` | 一比一 | 图片 URL 带尺寸参数 |

---

## M. 播放器（F-096 ～ F-108）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-096 | 切歌过渡三种模式 | 智能过渡 / 交叉淡化 / 不过渡 | `app/src/main/java/com/mytv/mymusic/data/AppStore.kt:187` | 一比一 | 状态机在 `:playback-core`，桌面用 VLC 双实例实现 |
| F-097 | 智能过渡分析 | 先分析节拍、首尾再挑接法 | `app/src/main/java/com/mytv/mymusic/playback/AutomixPlanner.kt:38` | 降级 | 分析算法可复用，但需要拿到解码后的 PCM（VLC 回调，见 F-107） |
| F-098 | 过渡进度环 | 播放页画一圈过渡进度 | `README.md:115` | 一比一 | 纯 UI |
| F-099 | 定时关闭 | 15/30/45/60 分钟或播完当前曲 | `app/src/main/java/com/mytv/mymusic/playback/SleepTimer.kt:56` | 一比一 | 协程实现，与平台无关 |
| F-100 | 定时结束淡出 | 结束前 30 秒线性降低音量 | `app/src/main/java/com/mytv/mymusic/playback/SleepTimer.kt:90` | 一比一 | 纯音量插值 |
| F-101 | 均衡器 | 五段以上滑杆与六种预设 | `app/src/main/java/com/mytv/mymusic/playback/EqualizerController.kt:49` | 降级 | VLC 有均衡器 API，但频段/预设要与设备对齐，效果弱于 Android 的系统级 EQ |
| F-102 | 音量归一化 | 不同来源响度拉平 | `app/src/main/java/com/mytv/mymusic/playback/VolumeNormalization.kt:12` | 降级 | 算法可移植，但要在桌面音频管线里插入增益（VLC 需 filter 回调） |
| F-103 | 播放缓存上限 | 设置里 128 MB–1 GB 可调 | `app/src/main/java/com/mytv/mymusic/MyMusicApp.kt:153` | 一比一 | LRU 淘汰纯逻辑，缓存目录换 PlatformPaths |
| F-104 | 后台播放 | 切后台/锁屏继续放 | `app/src/main/java/com/mytv/mymusic/playback/PlaybackService.kt:40` | 需替代 | 桌面没有前台服务概念，改窗口后台/托盘常驻；系统媒体控制换 SMTC |
| F-105 | 系统媒体控制 | 通知栏、锁屏、蓝牙耳机控制 | `README.md:169` | 需替代 | 桌面走 Windows SMTC（System Media Transport Controls），能力接近但 API 不同 |
| F-106 | 队列持久化与恢复 | 进程被杀后从上次位置继续 | `app/src/main/java/com/mytv/mymusic/playback/QueueStore.kt:21` | 一比一 | Room 持久化，桌面同源 |
| F-107 | 实时频谱/节拍数据 | 律动类动画跟着音乐节拍 | `app/src/main/java/com/mytv/mymusic/playback/RhythmAudioSink.kt:1` | 需替代 | 现有实现挂在 media3 的 `TeeAudioProcessor`；桌面要接 `libvlc_audio_set_callbacks` |
| F-108 | 迷你播放器 | 手机底部悬浮播放器，滑动手势 | `app/src/main/java/com/mytv/mymusic/ui/adaptive/PhoneBottomBar.kt:203` | 一比一 | Compose 同源；手势改鼠标拖拽/滚轮 |
| F-109 | 平板播放条与播放窗格 | 840dp 底部条，≥1200dp 右侧窗格 | `app/src/main/java/com/mytv/mymusic/ui/adaptive/LargeScreen.kt:134` | 一比一 | 桌面窗口宽度即「大屏」，布局直接复用 |
| F-110 | 队列面板 | 长按迷你播放器打开队列 | `app/src/main/java/com/mytv/mymusic/ui/player/PlayerSheets.kt:107` | 一比一 | Compose |

---

## N. 分享图卡（F-111 ～ F-114）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-111 | 长按选句 | 全屏歌词里长按挑几句做图 | `README.md:112` | 一比一 | Compose 手势 |
| F-112 | 十种卡片样式 | 配色/圆角/封面形状可换 | `app/src/main/java/com/mytv/mymusic/ui/player/share/ShareCardShapes.kt:87` | 一比一 | 纯 Compose 绘制 |
| F-113 | 9:16 竖图导出 | 铺成竖图导出 | `README.md:112` | 一比一 | 桌面用 `ImageComposeScene` 渲染成 PNG |
| F-114 | 存相册 / 分享 / 复制文字 | 保存、分享或只复制歌词文本 | `README.md:112` | 需替代 | 桌面没有相册与系统分享面板，改「另存为 PNG」+ 剪贴板 |

---

## O. 主题与外观（F-115 ～ F-121）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-115 | 配色来源 | 跟随封面 / 系统壁纸 / 品牌松绿 / 自选 | `app/src/main/java/com/mytv/mymusic/ui/theme/Appearance.kt:12` | 降级 | 封面与自选色可用；「系统壁纸取色」桌面取不到壁纸主色，需退化为固定色板 |
| F-116 | 模糊开关 | 模糊背景自动/开/关 | `app/src/main/java/com/mytv/mymusic/ui/theme/Appearance.kt:26` | 一比一 | haze 在 CMP 有对应实现 |
| F-117 | 主题套用 | 全局 Material 3 主题与封面取色 | `app/src/main/java/com/mytv/mymusic/ui/theme/Theme.kt:97` | 一比一 | Compose 同源 |
| F-118 | 播放页固定深色 | 播放页与状态栏一体化深色 | `app/src/main/java/com/mytv/mymusic/ui/theme/Theme.kt:145` | 一比一 | 纯配色 |
| F-119 | 文字大小与启动页 | 设置里调字号、选启动页 | `README.md:105` | 一比一 | 纯设置项 |
| F-120 | 内置 Roboto Flex | 拉丁字母与数字用内置字体 | `README.md:145` | 一比一 | 字体随包分发 |
| F-121 | 共享元素飞入 | 从迷你播放器打开播放页时封面飞入 | `README.md:145` | 一比一 | Compose `SharedTransitionLayout` 在 CMP 可用 |

---

## P. 桌面小组件（F-122 ～ F-129）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-122 | 正在播放组件 | 1×1 到 4×3 七档，带进度与歌词 | `app/src/main/java/com/mytv/mymusic/widget/NowPlayingWidget.kt:71` | 需替代 | Glance/RemoteViews 无桌面对应物；替代：置顶迷你窗或托盘弹窗 |
| F-123 | 歌词组件 | 当前句放大、下一句变淡 | `app/src/main/java/com/mytv/mymusic/widget/LyricsWidget.kt:43` | 需替代 | 同上，可用桌面歌词浮窗替代 |
| F-124 | 听歌模式组件 | 一键开始私人漫游/场景电台 | `app/src/main/java/com/mytv/mymusic/widget/ModeWidget.kt:48` | 需替代 | 改托盘菜单或全局快捷键 |
| F-125 | 榜单组件 | 任选一榜显示前几名与排名变化 | `app/src/main/java/com/mytv/mymusic/widget/ChartWidget.kt:38` | 需替代 | 改托盘子菜单/迷你窗标签 |
| F-126 | 歌单组件 | 我喜欢/最近/自建歌单一键播 | `app/src/main/java/com/mytv/mymusic/widget/ListWidget.kt:42` | 需替代 | 同上 |
| F-127 | 快捷入口组件 | 识曲、搜索、我喜欢、定时关闭 | `app/src/main/java/com/mytv/mymusic/widget/ToolsWidget.kt:41` | 需替代 | 改全局快捷键 + 托盘菜单 |
| F-128 | 组件设置页 | 每个组件有可跳过的配置页 | `app/src/main/java/com/mytv/mymusic/widget/WidgetConfigActivity.kt:77` | 需替代 | 桌面无组件配置概念，设置并入应用设置页 |
| F-129 | 组件跟随应用配色 | 颜色跟 App 外观而非系统动态色 | `README.md:165` | 需替代 | 仅在实现替代物时才有意义 |

---

## Q. 词幕 Lyricon 互通（F-130 ～ F-132）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-130 | 发送歌词给词幕 | 把当前歌曲与逐字歌词发给 Lyricon | `app/src/main/java/com/mytv/mymusic/lyricon/LyriconController.kt:15` | 需替代 | 词幕是 Android 应用；桌面可暴露本地 WebSocket 供第三方歌词工具订阅 |
| F-131 | 接收他源歌词 | 接收别的播放器歌词并预览 | `app/src/main/java/com/mytv/mymusic/lyricon/LyriconSubscriptionStore.kt:32` | 需替代 | 同上，反向订阅走同一 WebSocket 协议 |
| F-132 | 悬浮歌词窗 | 桌面上的滚动歌词浮层 | `app/src/main/java/com/mytv/mymusic/overlay/FloatingLyricsService.kt:45` | 需替代 | `Service` + 悬浮窗权限，桌面改置顶无边框窗口 |

---

## R. 折叠屏与双屏（F-133 ～ F-137）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-133 | 折痕分栏 | 以折痕为界做双栏 | `app/src/main/java/com/mytv/mymusic/ui/adaptive/FoldSplit.kt:61` | 不适用 | 桌面没有 FoldingFeature；多显示器场景改用窗口宽度分栏（可在 B1 顺带做） |
| F-134 | 桌面姿态（半折立起） | 上半屏歌词、下半屏控制 | `README.md:120` | 不适用 | 同上 |
| F-135 | 双屏铰链分块 | 铰链处不放内容 | `README.md:122` | 不适用 | 同上 |
| F-136 | 矮窗紧凑播放页 | 高度 <480dp 用紧凑布局 | `README.md:123` | 一比一 | 桌面窗口可任意缩放，这条反而更有用 |
| F-137 | 折叠不打断浏览 | 折叠展开保持当前页面 | `README.md:124` | 不适用 | 折叠事件不存在 |

---

## S. TV（F-138 ～ F-141）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-138 | 电视全屏播放页 | 全屏封面 + 歌词 | `app/src/main/java/com/mytv/mymusic/ui/player/TvPlayer.kt:121` | 不适用 | PC 端没有遥控器场景；「大屏 10 尺布局」可由用户全屏窗口自行达成 |
| F-139 | 环境模式 | 空闲后只显示封面与歌词 | `README.md:117` | 需替代 | 桌面可做「屏保模式」，但触发条件（空闲/遥控）不同 |
| F-140 | 方向键控制播放 | 上下切歌、左右快退快进 | `README.md:117` | 需替代 | 桌面改全局快捷键（媒体键/自定义） |
| F-141 | 播放时阻止屏保 | 播放期间保持屏幕常亮 | `README.md:172` | 需替代 | 桌面用 `SetThreadExecutionState` 阻止休眠 |

---

## T. 应用内更新（F-142 ～ F-144）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-142 | 检查更新 | 比对 GitHub Release 的 `update.json` | `app/src/main/java/com/mytv/mymusic/data/update/UpdateRepository.kt:167` | 一比一 | 纯 HTTP + 版本比较 |
| F-143 | 安装包校验 | 下载后校验 SHA-256、包名、版本、签名 | `app/src/main/java/com/mytv/mymusic/data/update/UpdateRepository.kt:63` | 降级 | SHA-256 与版本可校验；Windows 安装包签名验证需换 WinVerifyTrust/签名证书链 |
| F-144 | 打开系统安装器 | 交给系统安装 | `README.md:193` | 需替代 | 桌面改为静默下载 MSI 后提示用户运行（或 Sparkle 类增量更新器） |

---

## U. 评论（F-145 ～ F-146）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-145 | 歌曲评论 | 播放页看这首的评论，分页加载 | `app/src/main/java/com/mytv/mymusic/ui/AppUi.kt:222` | 一比一 | 接口 + Compose 列表 |
| F-146 | 评论接口 | 平台侧 comments(page, limit) | `core/src/main/kotlin/com/mytv/mymusic/data/native/NativeProvider.kt:92` | 一比一 | 接口层无 Android 依赖 |

---

## V. 汽水会员与天天送（F-147 ～ F-149）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-147 | 汽水整曲签名 | 会员曲走签名服务拿整曲 | `README.md:8` | 降级 | 签名服务是外部依赖（SodaM），桌面能否用取决于服务端是否放行，非客户端能力 |
| F-148 | 汽水扫码登录 | 扫码登录汽水账号 | `app/src/main/java/com/mytv/mymusic/data/native/QQLoginPage.kt:1` | 需替代 | 隐藏 WebView 取 Cookie；桌面改系统浏览器 + 回环回调（ADR §5 已排） |
| F-149 | 天天送 VIP | 看活动状态并一键领取 | `core/src/main/kotlin/com/mytv/mymusic/data/native/SodaWelfare.kt:25` | 一比一 | 纯 HTTP 接口 |

---

## W. 无障碍（F-150 ～ F-152）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-150 | TalkBack 走查 | 读屏可完整操作主要流程 | `docs/accessibility-audit.md:1` | 需替代 | 桌面读屏是 Narrator/UIA；Compose 的语义树能映射到 UIA，但要重新走查 |
| F-151 | 迷你播放器读屏备用操作 | 读屏用户有按钮与自定义操作 | `README.md:109` | 需替代 | 同上 |
| F-152 | 焦点顺序 | 触摸/鼠标/键盘/遥控同一套焦点 | `README.md:11` | 降级 | 桌面以鼠标+键盘为主，遥控语义去掉；焦点系统需重做 |

---

## X. 性能与构建（F-153 ～ F-156）

| 编号 | 功能 | 用户可见行为（一句话） | Android 证据（文件:行） | PC 判定 | 理由 |
| --- | --- | --- | --- | --- | --- |
| F-153 | Baseline Profile | 启动与滚动更顺 | `README.md:151` | 降级 | Android 专属产物；桌面用 CDS/AOT 替代，效果不等价 |
| F-154 | 帧时间基线 | 有公开的掉帧测量方法与结果 | `docs/performance.md:1` | 需替代 | 桌面要重新建一套测量（Compose Desktop + 帧时间） |
| F-155 | 缓存 LRU 淘汰 | 调低上限立即按最近使用淘汰 | `app/src/main/java/com/mytv/mymusic/playback/AdjustableLruEvictor.kt:14` | 一比一 | 纯逻辑，media3 CacheEvictor 换成自管目录 |
| F-156 | 体积/内存约束 | 低内存设备关实时模糊 | `README.md:143` | 降级 | 桌面内存不紧张，但 JVM 包体大，需另做体积预算 |

---

## 判定分布（供 t9 排期参考）

| 判定 | 条数 | 说明 |
| --- | --- | --- |
| 一比一 | 101 | 主体功能；桌面同源 Compose + 纯 JVM 业务 |
| 降级 | 19 | Apple 整曲之外的能力衰减：识曲音源、本地自动匹配、仅 Wi-Fi 上传、令牌加密、智能过渡、EQ、响度、频谱、壁纸取色、签名校验、焦点顺序、Profile、体积、汽水签名服务、Apple 试听等 |
| 需替代 | 31 | 洛雪脚本引擎、MediaStore 扫描、语音搜索、TV 扫码配置、后台播放、系统媒体控制、分享到相册、小组件 8 项、词幕 3 项、环境模式、方向键、阻止屏保、系统安装器、TalkBack、读屏备用操作、帧时间基线 |
| 不适用 | 5 | 折痕分栏、桌面姿态、双屏铰链、折叠不打断、电视全屏播放页 |

> 数字按上表逐行统计；t9 若调整判定，请同步更新本表。

---

## 已复核行号（抽查 10 行，逐条打开源码核对）

复核方法：对每行 `文件:行` 重新打开文件确认该行内容与「功能/行为」描述一致（本机 grep 结果一致）。

| 复核行 | 证据 | 该行实际内容（核对点） |
| --- | --- | --- |
| F-001 | `NetEaseProvider.kt:77` | `class NetEaseProvider : BaseNativeProvider(NETEASE_INFO) {` |
| F-016 | `SodaProvider.kt:80` | `class SodaProvider : BaseNativeProvider(SODA_INFO) {` |
| F-026 | `Availability.kt:15` | `fun availabilityOf(source: TrackSource, cookie: String): Availability {` |
| F-031 | `ChartsScreen.kt:137` | `fun ChartsScreen(` |
| F-051 | `NativeProvider.kt:52` | `suspend fun parseSong(cookie: String, link: String): Song =` |
| F-073 | `AppStore.kt:104` | `val splitBilingual: Boolean = false,`（拆单行双语开关） |
| F-078 | `LyricVisualizers.kt:38` | `object ClassicVisualizer : LyricVisualizer {` |
| F-099 | `SleepTimer.kt:56` | `fun start(minutes: Int, fade: Boolean) {` |
| F-101 | `EqualizerController.kt:49` | `object EqPresets {` |
| F-122 | `NowPlayingWidget.kt:71` | `class NowPlayingWidget : MusicWidget(WidgetKind.Now) {` |

> 其余行的路径与行号由脚本逐条校验过「文件存在 + 行号在文件长度内」，见提交说明。
