package com.mytv.mymusic.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mytv.mymusic.aggregate.AggregationSettings
import com.mytv.mymusic.data.local.LocalMusicSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 同包的 MirrorKind / MirrorRoute / TIER_* 常量在 MirrorRoutes.kt（从 app 侧 GithubMirrorRoutes.kt
// 拆出来的纯数据部分）；import 写全是为了让「谁在哪」一眼可见，Kotlin 同包其实不用 import。

// 设置项：从 AppStore.kt 拆出来独立成文件（Room 部分见 data/AppDatabase.kt）。
// 搬进 :core 后不再持有 Context —— 两个 DataStore 由平台层创建后注入：
// Android 用 preferencesDataStore 委托（仍是 mymusic / lyric_memory 两个文件，用户设置不丢），
// 桌面用 PreferenceDataStoreFactory 创建。
// 同包引用：MirrorKind / MirrorRoute / TIER_* 在 MirrorRoutes.kt，PosterPrefs 在 PosterSettings.kt，
// SwipePrefsCodec / SharePrefsCodec 各在自己的文件里。

@Serializable
data class EqBandSetting(val freqHz: Int = 0, val gainDb: Float = 0f)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val customBands: List<EqBandSetting> = emptyList(),
)

enum class LyricsBackgroundMode { Off, Ambient, Reactive }

private fun String?.toLyricsBackgroundMode(): LyricsBackgroundMode =
    LyricsBackgroundMode.entries.firstOrNull { it.name == this } ?: LyricsBackgroundMode.Ambient

/**
 * 歌词背景“律动”跟拍的幅度：柔和只有光晕和流光跟拍；标准再加上封面弹跳和小节首拍的光环；强烈弹得更明显、每拍都有光环。
 * 旧版存的 Soft / Standard 照常读出。
 */
enum class RhythmIntensity { Soft, Standard, Strong }

private fun String?.toRhythmIntensity(): RhythmIntensity =
    RhythmIntensity.entries.firstOrNull { it.name == this } ?: RhythmIntensity.Standard

/** 跟拍校准的范围（毫秒）。 */
const val RHYTHM_OFFSET_MAX_MS = 200

/** 播放页歌词下面那一行（经典列表的第二行、其他动画模式的宿主副行）放什么。没有罗马音时“罗马音”回退成翻译。 */
enum class LyricSubline { Translation, Romanization, Both }

/** 全屏歌词每首歌换不换动画：固定、随机、按速度。 */
enum class LyricAutoSwitch { Fixed, Random, Tempo }

/** 按速度换动画的一档：每分钟拍数低于 [maxBpm]（左闭右开，上一档的上限是这一档的下限）时用 [mode]。 */
@Serializable
data class TempoRule(val maxBpm: Int, val mode: String)

/** 四档速度的默认对应：慢、中、快、很快。 */
val DefaultTempoRules = listOf(
    TempoRule(90, "vinyl"),
    TempoRule(120, "rings"),
    TempoRule(150, "wave"),
    TempoRule(TEMPO_RULE_OPEN_END, "jump"),
)

/** 最后一档没有上限。 */
const val TEMPO_RULE_OPEN_END = 999

/**
 * 歌词文字变换（见 LyricPipeline），对播放页、通知栏、桌面小组件、海报墙都生效；默认全关，关着时歌词和原来一模一样。
 */
data class LyricTextPrefs(
    val splitBilingual: Boolean = false,
    val traditional: Boolean = false,
    val autoRomanize: Boolean = false,
)

/** 播放页歌词字体：第一字体缺字时用第二字体，再用系统字体。名称只用于界面显示。 */
data class LyricFontPrefs(
    val primaryName: String = "",
    val fallbackName: String = "",
)

/**
 * 全屏歌词动画（设置 › 播放页 › 歌词动画，和全屏歌词页右上角 ✦ 打开的面板是同一份）。
 * [pluginSettings] 是“模式 id → { 设置键: 值 }”，只存改过的项，读的时候按模式的设置表合并默认值、夹紧。
 * [pins] 是“歌曲 key → 固定用的模式”。每首歌随机抽到的模式和测到的速度记在另一处（见 SettingsStore.lyricMemory），
 * 不放进这里：每首歌都可能写一次，放这里会让整个界面跟着重组。
 */
data class LyricStagePrefs(
    val visualizer: String = "classic",
    val auto: LyricAutoSwitch = LyricAutoSwitch.Fixed,
    val tempoRules: List<TempoRule> = DefaultTempoRules,
    val spectrum: Boolean = false,
    val subline: LyricSubline = LyricSubline.Translation,
    val pluginSettings: Map<String, Map<String, String>> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
    /** 首次进入全屏歌词页时 ✦ 外面的呼吸提示，打开过一次面板就不再显示。 */
    val hintSeen: Boolean = false,
    /** 随机池（模式 id）：随机换法从这里面抽。空＝除经典外的全部模式（旧数据、没动过时都是空）。 */
    val randomPool: Set<String> = emptySet(),
    /** 取色面板“最近用过”：`#RRGGBB`，新的在前，最多 [RECENT_COLORS_LIMIT] 个。点了“完成”就写，不进面板草稿。 */
    val recentColors: List<String> = emptyList(),
    /**
     * 风格预设：某个模式的一套设置存成的卡片，按创建顺序。卡片 id 是 `base#xxxx`，和内置模式一样能固定、进随机池、
     * 配给按速度某一档、给某首歌固定；只存在本机。预设的设置在面板草稿里（[StagePreset.settings]），新建、改名、删除点了就写。
     */
    val presets: List<StagePreset> = emptyList(),
)

const val RECENT_COLORS_LIMIT = 8

/** 一个风格预设：[base] 模式的一套设置 [settings]（只存改过的项），面板里叫 [name]。 */
@Serializable
data class StagePreset(
    val id: String,
    val base: String,
    val name: String,
    val settings: Map<String, String> = emptyMap(),
)

/**
 * 读预设：丢掉 id 不是 `base#xxxx`、和 base 对不上、base 是经典、名字空着的条目，同一个 id 只留第一个。
 * base 模式还在不在由界面判断（删掉的模式的预设不显示，也认不出）。app 侧 ui 测试也用，跨模块开放。
 */
fun decodePresets(raw: String?): List<StagePreset> {
    if (raw.isNullOrBlank()) return emptyList()
    val all = runCatching { presetJson.decodeFromString<List<StagePreset>>(raw) }.getOrDefault(emptyList())
    return all.filter { preset ->
        '#' in preset.id && preset.id.substringBefore('#') == preset.base && preset.base != "classic" && preset.name.isNotBlank()
    }.distinctBy { it.id }
}

private val presetJson = Json { ignoreUnknownKeys = true }

/** 每首歌记下的随机结果和速度（最近 [LYRIC_MEMORY_LIMIT] 首）。 */
@Serializable
data class LyricMemoryEntry(val mode: String = "", val bpm: Float = 0f)

const val LYRIC_MEMORY_LIMIT = 2000

/** JSON 字符串数组 → 集合；读不出来（没写过、格式不对）时为空。app 侧 ui 测试也用，跨模块开放。 */
fun decodeStringSet(raw: String?): Set<String> = decodeStringList(raw).toSet()

/** JSON 字符串数组 → 列表（保持顺序）；读不出来时为空。 */
fun decodeStringList(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList() else runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

/** 播放页封面形状：随机（每次切歌换一种，不和上一首重复）或固定成某一种 MaterialShapes。 */
enum class CoverShapeChoice { Random, Cookie12, Cookie9, Cookie7, Sunny, SoftBurst, Clover8 }

private fun String?.toCoverShapeChoice(): CoverShapeChoice =
    CoverShapeChoice.entries.firstOrNull { it.name == this } ?: CoverShapeChoice.Random

/** 方式：先分析再挑接法、固定长度的等功率淡化，或者干脆不过渡。 */
enum class CrossfadeMode(val label: String) {
    Automix("智能过渡"),
    Fade("交叉淡化"),
    None("不过渡"),
}

/**
 * GitHub 加速镜像；空串是源站直连。
 *
 * 2026-11-23 实测（Release 资产 / `api.github.com` / raw 三类端点，成都教育网出口）：
 * - gh-proxy.org / gh-proxy.com：三类全通，且代理 `api.github.com`。
 * - ghfile.geekertao.top / ghfast.top / gh.3w.pm / cors.isteed.cc：只转发资产与 raw，**不代理 API**。
 * - ghproxy.net：可用但 2~4.5s，仅作兜底。
 * - raw.githack.com / rawcdn.githack.com / githack.com：只服务 raw/blob，最后兜底。
 *
 * 已剔除（域名不再解析 / TLS 不握手 / 403 只许 git / 变成落地页）：ghp.ci、hub.gitmirror.com、
 * github.moeyy.xyz、gh.cc、ghproxy.cn、gh-proxy.net、mirror.ghproxy.com、gh-proxy.ygxz.in、
 * gh.llkk.cc、gh.api.99988866.xyz、ghproxy.com、ghproxy.link、ghps.cc、gh.ddlc.top、
 * kkgithub.com、bgithub.xyz、githubfast.com、gitclone.com，以及 100 余个社区流传的 `gh.*`
 * 前缀代理。判定「活」的唯一标准是能前缀式抓回 raw 正文（HTTP 200 且不是 HTML 反爬页），
 * 网页能否打开不作数；实测脚本与完整记录见 gitee `mytv-android/my-mirror`。
 *
 * 运行时还会从 gitee `my-mirror` 拉最新清单与这里的内置值合并，见 [GithubMirrors]。
 * 排序铁律：**镜像 / 国内直连优先，raw 最后兜底**——按 [tier] 升序取，同档位保持声明顺序。
 */
enum class GithubMirror(
    val id: String,
    val label: String,
    /** [MirrorKind.PREFIX] 拼在原 URL 前；[MirrorKind.RAW_HOST] 只存主机名；直连为空串。 */
    val prefix: String,
    val kind: MirrorKind,
    /** 是否代理 `api.github.com`（不代理的线路只能用于下载资产与 raw） */
    val proxiesApi: Boolean,
    val tier: Int,
) {
    /** 并发测速所有线路，选最快可达者（原有行为） */
    Auto("auto", "自动测速", "", MirrorKind.PREFIX, true, TIER_MODE),
    Direct("direct", "GitHub 直连", "", MirrorKind.PREFIX, true, TIER_DIRECT),
    ProxyOrg("gh-proxy.org", "gh-proxy.org", "https://gh-proxy.org/", MirrorKind.PREFIX, true, TIER_FAST),
    ProxyCom("gh-proxy.com", "gh-proxy.com", "https://gh-proxy.com/", MirrorKind.PREFIX, true, TIER_FAST),
    Geekertao("ghfile.geekertao.top", "ghfile.geekertao.top", "https://ghfile.geekertao.top/", MirrorKind.PREFIX, false, TIER_FAST),
    Ghfast("ghfast.top", "ghfast.top", "https://ghfast.top/", MirrorKind.PREFIX, false, TIER_FAST),
    ThreeW("gh.3w.pm", "gh.3w.pm", "https://gh.3w.pm/", MirrorKind.PREFIX, false, TIER_FAST),
    CorsIsteed("cors.isteed.cc", "cors.isteed.cc", "https://cors.isteed.cc/", MirrorKind.PREFIX, false, TIER_FAST),
    ProxyNet("ghproxy.net", "ghproxy.net", "https://ghproxy.net/", MirrorKind.PREFIX, false, TIER_SLOW),
    RawGithack("raw.githack.com", "raw.githack.com", "raw.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    RawcdnGithack("rawcdn.githack.com", "rawcdn.githack.com", "rawcdn.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    Githack("githack.com", "githack.com", "githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    ;

    /** 具体代理线路（自动测速与直连另行展开）。 */
    val isProxy: Boolean get() = this != Auto && this != Direct

    fun toRoute(): MirrorRoute =
        MirrorRoute(id, label, prefix, kind, proxiesApi, tier, enabled = true)

    companion object {
        val proxies: List<GithubMirror> = entries.filter { it.isProxy }

        fun fromName(name: String?): GithubMirror = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/** 播放页上怎么画出这次接歌：不画、围着封面的一整圈，或沿封面外轮廓走一圈。 */
enum class CrossfadeAnimation(val label: String) {
    Off("关闭"),
    Ring("屏幕圆环"),
    Outline("封面描边"),
}

/** 折叠屏桌面姿态（半开、竖着立起来的那一半）播放页上半屏放什么；下半屏固定是控制。 */
enum class TabletopTop { Lyrics, Cover, Clock }

private fun String?.toTabletopTop(): TabletopTop =
    TabletopTop.entries.firstOrNull { it.name == this } ?: TabletopTop.Lyrics

private fun String?.toPosterTint(): PosterTint =
    PosterTint.entries.firstOrNull { it.name == this } ?: PosterTint.Medium

private fun String?.toCollectionView(): CollectionView =
    CollectionView.entries.firstOrNull { it.name == this } ?: CollectionView.List

private fun String?.toMiniPlayerTarget(): MiniPlayerTarget =
    MiniPlayerTarget.entries.firstOrNull { it.name == this } ?: MiniPlayerTarget.Player

class SettingsStore(
    /** 主设置文件（Android 是 `mymusic.preferences_pb`，桌面对应 %APPDATA%\myMusic\mymusic.preferences_pb）。 */
    private val store: DataStore<Preferences>,
    /** 每首歌的歌词记忆：写得很频繁，单独一个文件，避免整个界面跟着收到新设置。 */
    private val lyricMemory: DataStore<Preferences>,
) {
    private val providerKey = stringPreferencesKey("provider")
    private val chartsSourceKey = stringPreferencesKey("charts_source")
    private val playlistsSourceKey = stringPreferencesKey("playlists_source")
    private val startupTabKey = stringPreferencesKey("startup_tab")
    private val playlistsModeKey = stringPreferencesKey("playlists_mode")
    private val listenGroupKey = stringPreferencesKey("listen_group")
    private val themeKey = stringPreferencesKey("theme")
    private val layoutModeKey = stringPreferencesKey("layout_mode")
    private val cookiesKey = stringPreferencesKey("cookies")
    private val cookieUpdatedAtKey = stringPreferencesKey("cookie_updated_at")
    private val sodaSignerUrlKey = stringPreferencesKey("soda_signer_url")
    private val sodaSignerTokenKey = stringPreferencesKey("soda_signer_token")
    private val sodaSignerEnabledKey = booleanPreferencesKey("soda_signer_enabled")
    private val aggregationKey = stringPreferencesKey("aggregation")
    private val localMusicKey = stringPreferencesKey("local_music")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")
    private val volumeNormalizationKey = booleanPreferencesKey("volume_normalization")
    private val audioQualityKey = stringPreferencesKey("audio_quality")
    private val showBottomNavKey = booleanPreferencesKey("show_bottom_nav")
    private val equalizerEnabledKey = booleanPreferencesKey("equalizer_enabled")
    private val equalizerPresetKey = stringPreferencesKey("equalizer_preset")
    private val equalizerBandsKey = stringPreferencesKey("equalizer_bands")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val customSeedKey = intPreferencesKey("custom_seed")
    private val browseStyleKey = stringPreferencesKey("browse_style")
    private val playerStyleKey = stringPreferencesKey("player_style")
    private val contrastKey = floatPreferencesKey("contrast")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val amoledKey = booleanPreferencesKey("amoled")
    private val blurModeKey = stringPreferencesKey("blur_mode")
    private val lastSeedKey = intPreferencesKey("last_seed")
    private val sleepFadeKey = booleanPreferencesKey("sleep_fade")
    private val crossfadeEnabledKey = booleanPreferencesKey("crossfade_enabled")
    private val crossfadeSecondsKey = intPreferencesKey("crossfade_seconds")
    private val crossfadeAnimationKey = stringPreferencesKey("crossfade_animation")
    private val crossfadeModeKey = stringPreferencesKey("crossfade_mode")
    private val cacheLimitKey = intPreferencesKey("cache_limit_mb")
    private val lyricsBackgroundKey = stringPreferencesKey("lyrics_background")
    private val notificationLyricsKey = booleanPreferencesKey("notification_lyrics")
    private val floatingLyricsEnabledKey = booleanPreferencesKey("floating_lyrics_enabled")
    private val lyriconProviderEnabledKey = booleanPreferencesKey("lyricon_provider_enabled")
    private val lyriconSubscriberEnabledKey = booleanPreferencesKey("lyricon_subscriber_enabled")
    /** Apple Music 匿名歌词走第三方中转（zarz / paxsenix / AMLL）的总开关。 */
    private val thirdPartyLyricsKey = booleanPreferencesKey("third_party_lyrics")
    /** 原平台无词或缺封面时，查询 LRCLIB / Apple 公开搜索。 */
    private val metadataFallbackKey = booleanPreferencesKey("metadata_fallback")
    private val rhythmIntensityKey = stringPreferencesKey("rhythm_intensity")
    private val coverShapeKey = stringPreferencesKey("cover_shape")
    private val tvAmbientClockKey = booleanPreferencesKey("tv_ambient_clock")
    private val tabletopTopKey = stringPreferencesKey("tabletop_top")
    private val keepScreenOnKey = booleanPreferencesKey("tv_keep_screen_on")
    private val tvBackgroundPlayKey = booleanPreferencesKey("tv_background_play")
    private val rhythmOffsetKey = intPreferencesKey("rhythm_offset_ms")
    private val rhythmDebugKey = booleanPreferencesKey("rhythm_debug")
    // 海报墙：墙内设置六项 + 详情页视图 + 迷你播放器去处，和播放页的设置一样只存在本机
    private val posterTintKey = stringPreferencesKey("poster_tint")
    private val posterLightsOutKey = booleanPreferencesKey("poster_lights_out")
    private val posterIndexKey = booleanPreferencesKey("poster_index")
    private val posterTranslationKey = booleanPreferencesKey("poster_translation")
    private val posterFollowKey = booleanPreferencesKey("poster_follow")
    private val posterTvDriftKey = booleanPreferencesKey("poster_tv_drift")
    // 视角远近：手机、平板、电视各一份（按墙的格距档存）
    private val posterZoomPhoneKey = floatPreferencesKey("poster_zoom_phone")
    private val posterZoomTabletKey = floatPreferencesKey("poster_zoom_tablet")
    private val posterZoomTvKey = floatPreferencesKey("poster_zoom_tv")
    private val collectionViewKey = stringPreferencesKey("collection_view")
    private val miniPlayerTargetKey = stringPreferencesKey("mini_player_target")
    // 播放上报：把播放记录回传给平台，让推荐流跟着口味走；关掉后一个请求都不发
    private val playReportEnabledKey = booleanPreferencesKey("play_report_enabled")
    // 汽水上报用的设备标识：每次启动重新生成就没法积累口味，所以落盘
    private val sodaReportDeviceIdKey = stringPreferencesKey("soda_report_device_id")
    // 酷狗概念版「听歌领会员」：启动时自动领一天的开关，和上次自动领取的日期（只用来去重）
    private val kugouWelfareAutoKey = booleanPreferencesKey("kugou_welfare_auto")
    private val kugouWelfareLastDateKey = stringPreferencesKey("kugou_welfare_last_date")
    // 全屏歌词动画与歌词文字变换
    private val lyricVisualizerKey = stringPreferencesKey("lyric_visualizer")
    private val lyricVisualizerAutoKey = stringPreferencesKey("lyric_visualizer_auto")
    private val lyricTempoRulesKey = stringPreferencesKey("lyric_tempo_rules")
    private val lyricVisualizerPinsKey = stringPreferencesKey("lyric_visualizer_pins")
    private val lyricVisualizerMemoryKey = stringPreferencesKey("lyric_visualizer_memory")
    private val lyricPluginSettingsKey = stringPreferencesKey("lyric_plugin_settings")
    private val lyricSpectrumKey = booleanPreferencesKey("lyric_spectrum")
    private val lyricSublineKey = stringPreferencesKey("lyric_subline")
    private val lyricHintSeenKey = booleanPreferencesKey("lyric_visualizer_hint_seen")
    private val lyricRandomPoolKey = stringPreferencesKey("lyric_random_pool")
    private val lyricRecentColorsKey = stringPreferencesKey("lyric_recent_colors")
    private val lyricStagePresetsKey = stringPreferencesKey("lyric_stage_presets")
    private val lyricSplitBilingualKey = booleanPreferencesKey("lyric_split_bilingual")
    private val lyricTraditionalKey = booleanPreferencesKey("lyric_traditional")
    private val lyricAutoRomanizeKey = booleanPreferencesKey("lyric_auto_romanize")
    private val lyricFontPrimaryKey = stringPreferencesKey("lyric_font_primary")
    private val lyricFontFallbackKey = stringPreferencesKey("lyric_font_fallback")
    /** 更新走哪条 GitHub 镜像线路；见 [GithubMirror]。 */
    private val githubMirrorKey = stringPreferencesKey("github_mirror")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: Flow<AppSettings> = store.data.map { values ->
        AppSettings(
            provider = values[providerKey] ?: "netease",
            chartsSource = values[chartsSourceKey] ?: "",
            playlistsSource = values[playlistsSourceKey] ?: "",
            startupTab = values[startupTabKey] ?: "",
            playlistsMode = values[playlistsModeKey] ?: "",
            listenGroup = values[listenGroupKey] ?: "",
            theme = values[themeKey] ?: "system",
            layoutMode = values[layoutModeKey] ?: "Auto",
            cookies = runCatching { json.decodeFromString<Map<String, String>>(values[cookiesKey] ?: "{}") }
                .getOrDefault(emptyMap()),
            sodaSignerUrl = values[sodaSignerUrlKey] ?: "",
            sodaSignerToken = values[sodaSignerTokenKey] ?: "",
            sodaSignerEnabled = values[sodaSignerEnabledKey] ?: true,
            thirdPartyLyrics = values[thirdPartyLyricsKey] ?: true,
            metadataFallback = values[metadataFallbackKey] ?: true,
            aggregation = runCatching {
                json.decodeFromString<AggregationSettings>(values[aggregationKey] ?: "")
            }.getOrDefault(AggregationSettings()),
            localMusic = runCatching {
                json.decodeFromString<LocalMusicSettings>(values[localMusicKey] ?: "")
            }.getOrDefault(LocalMusicSettings()),
            dynamicColor = values[dynamicColorKey] ?: false,
            reduceMotion = values[reduceMotionKey] ?: false,
            volumeNormalization = values[volumeNormalizationKey] ?: false,
            audioQuality = values[audioQualityKey] ?: "auto",
            showBottomNav = values[showBottomNavKey] ?: true,
            lyricsBackground = values[lyricsBackgroundKey].toLyricsBackgroundMode(),
            notificationLyrics = values[notificationLyricsKey] ?: false,
            floatingLyricsEnabled = values[floatingLyricsEnabledKey] ?: false,
            lyriconProviderEnabled = values[lyriconProviderEnabledKey] ?: true,
            lyriconSubscriberEnabled = values[lyriconSubscriberEnabledKey] ?: false,
            rhythmIntensity = values[rhythmIntensityKey].toRhythmIntensity(),
            coverShape = values[coverShapeKey].toCoverShapeChoice(),
            tvAmbientClock = values[tvAmbientClockKey] ?: true,
            tabletopTop = values[tabletopTopKey].toTabletopTop(),
            keepScreenOn = values[keepScreenOnKey] ?: false,
            tvBackgroundPlay = values[tvBackgroundPlayKey] ?: false,
            rhythmOffsetMs = (values[rhythmOffsetKey] ?: 0).coerceIn(-RHYTHM_OFFSET_MAX_MS, RHYTHM_OFFSET_MAX_MS),
            rhythmDebug = values[rhythmDebugKey] ?: false,
            poster = posterPrefs(values),
            swipe = SwipePrefsCodec.read(values),
            share = SharePrefsCodec.read(values),
            collectionView = values[collectionViewKey].toCollectionView(),
            miniPlayerTarget = values[miniPlayerTargetKey].toMiniPlayerTarget(),
            playReportEnabled = values[playReportEnabledKey] ?: true,
            sodaReportDeviceId = values[sodaReportDeviceIdKey] ?: "",
            kugouWelfareAuto = values[kugouWelfareAutoKey] ?: true,
            kugouWelfareLastClaimDate = values[kugouWelfareLastDateKey] ?: "",
            lyricStage = lyricStagePrefs(values),
            lyricFonts = LyricFontPrefs(
                primaryName = values[lyricFontPrimaryKey] ?: "",
                fallbackName = values[lyricFontFallbackKey] ?: "",
            ),
            lyricText = LyricTextPrefs(
                splitBilingual = values[lyricSplitBilingualKey] ?: false,
                traditional = values[lyricTraditionalKey] ?: false,
                autoRomanize = values[lyricAutoRomanizeKey] ?: false,
            ),
            equalizer = EqualizerSettings(
                enabled = values[equalizerEnabledKey] ?: false,
                presetId = values[equalizerPresetKey] ?: "flat",
                customBands = runCatching {
                    json.decodeFromString<List<EqBandSetting>>(values[equalizerBandsKey] ?: "[]")
                }.getOrDefault(emptyList()),
            ),
            searchHistory = runCatching {
                json.decodeFromString<List<String>>(values[searchHistoryKey] ?: "[]")
            }.getOrDefault(emptyList()),
            lastUpdateCheck = values[lastUpdateCheckKey] ?: 0L,
            // 旧版“系统取色”开关迁移为“系统壁纸”配色来源
            colorSource = values[colorSourceKey]
                ?: if (values[dynamicColorKey] == true) "Wallpaper" else "Cover",
            customSeed = values[customSeedKey] ?: 0xFF006B5F.toInt(),
            browseStyle = values[browseStyleKey] ?: "TonalSpot",
            playerStyle = values[playerStyleKey] ?: "Vibrant",
            contrast = values[contrastKey] ?: 0f,
            fontScale = values[fontScaleKey] ?: 1f,
            amoled = values[amoledKey] ?: false,
            blurMode = values[blurModeKey] ?: "Auto",
            lastSeed = values[lastSeedKey] ?: 0,
            sleepFade = values[sleepFadeKey] ?: true,
            crossfadeEnabled = values[crossfadeEnabledKey] ?: false,
            crossfadeSeconds = (values[crossfadeSecondsKey] ?: 6).coerceIn(4, 10),
            crossfadeAnimation = values[crossfadeAnimationKey] ?: CrossfadeAnimation.Ring.name,
            crossfadeMode = values[crossfadeModeKey] ?: CrossfadeMode.Automix.name,
            cacheLimitMb = values[cacheLimitKey] ?: 0,
            githubMirror = GithubMirror.fromName(values[githubMirrorKey]),
        )
    }

    suspend fun provider(value: String) = store.edit { it[providerKey] = value }
    suspend fun chartsSource(value: String) = store.edit { it[chartsSourceKey] = value }
    suspend fun playlistsSource(value: String) = store.edit { it[playlistsSourceKey] = value }
    suspend fun startupTab(value: String) = store.edit { it[startupTabKey] = value }
    suspend fun playlistsMode(value: String) = store.edit { it[playlistsModeKey] = value }
    suspend fun listenGroup(value: String) = store.edit { it[listenGroupKey] = value }
    suspend fun theme(value: String) = store.edit { it[themeKey] = value }
    suspend fun layoutMode(value: String) = store.edit { it[layoutModeKey] = value }
    suspend fun dynamicColor(value: Boolean) = store.edit { it[dynamicColorKey] = value }
    suspend fun reduceMotion(value: Boolean) = store.edit { it[reduceMotionKey] = value }
    suspend fun volumeNormalization(value: Boolean) = store.edit { it[volumeNormalizationKey] = value }
    suspend fun audioQuality(value: String) = store.edit { it[audioQualityKey] = value }
    suspend fun showBottomNav(value: Boolean) = store.edit { it[showBottomNavKey] = value }
    suspend fun colorSource(value: String) = store.edit { it[colorSourceKey] = value }
    suspend fun customSeed(value: Int) = store.edit { it[customSeedKey] = value }
    suspend fun browseStyle(value: String) = store.edit { it[browseStyleKey] = value }
    suspend fun playerStyle(value: String) = store.edit { it[playerStyleKey] = value }
    suspend fun contrast(value: Float) = store.edit { it[contrastKey] = value }
    suspend fun fontScale(value: Float) = store.edit { it[fontScaleKey] = value }
    suspend fun amoled(value: Boolean) = store.edit { it[amoledKey] = value }
    suspend fun blurMode(value: String) = store.edit { it[blurModeKey] = value }
    suspend fun lyricsBackground(value: LyricsBackgroundMode) = store.edit { it[lyricsBackgroundKey] = value.name }
    suspend fun notificationLyrics(value: Boolean) = store.edit { it[notificationLyricsKey] = value }
    suspend fun floatingLyricsEnabled(value: Boolean) = store.edit { it[floatingLyricsEnabledKey] = value }
    suspend fun lyriconProviderEnabled(value: Boolean) = store.edit { it[lyriconProviderEnabledKey] = value }
    suspend fun lyriconSubscriberEnabled(value: Boolean) = store.edit { it[lyriconSubscriberEnabledKey] = value }
    suspend fun rhythmIntensity(value: RhythmIntensity) = store.edit { it[rhythmIntensityKey] = value.name }
    suspend fun coverShape(value: CoverShapeChoice) = store.edit { it[coverShapeKey] = value.name }
    suspend fun tvAmbientClock(value: Boolean) = store.edit { it[tvAmbientClockKey] = value }
    suspend fun tabletopTop(value: TabletopTop) = store.edit { it[tabletopTopKey] = value.name }
    suspend fun keepScreenOn(value: Boolean) = store.edit { it[keepScreenOnKey] = value }
    suspend fun tvBackgroundPlay(value: Boolean) = store.edit { it[tvBackgroundPlayKey] = value }
    suspend fun rhythmOffsetMs(value: Int) =
        store.edit { it[rhythmOffsetKey] = value.coerceIn(-RHYTHM_OFFSET_MAX_MS, RHYTHM_OFFSET_MAX_MS) }
    suspend fun rhythmDebug(value: Boolean) = store.edit { it[rhythmDebugKey] = value }
    suspend fun collectionView(value: CollectionView) = store.edit { it[collectionViewKey] = value.name }
    suspend fun miniPlayerTarget(value: MiniPlayerTarget) = store.edit { it[miniPlayerTargetKey] = value.name }
    suspend fun playReportEnabled(value: Boolean) = store.edit { it[playReportEnabledKey] = value }

    /** 酷狗概念版「听歌领会员」的自动领取开关。 */
    suspend fun kugouWelfareAuto(value: Boolean) = store.edit { it[kugouWelfareAutoKey] = value }

    /** 记一次自动领取的日期（北京时间），同一天不再重复打接口。 */
    suspend fun kugouWelfareLastClaimDate(value: String) =
        store.edit { it[kugouWelfareLastDateKey] = value }

    /**
     * 汽水上报的设备标识：第一次用随机值落盘，之后一直复用。
     * 官方客户端靠它把同一个人的播放行为串起来，每次启动都换就等于没有历史。
     */
    suspend fun sodaReportDeviceId(): String {
        val existing = store.data.first()[sodaReportDeviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        store.edit { it[sodaReportDeviceIdKey] = generated }
        return generated
    }
    suspend fun lastSeed(value: Int) = store.edit { it[lastSeedKey] = value }
    suspend fun sleepFade(value: Boolean) = store.edit { it[sleepFadeKey] = value }
    suspend fun crossfadeEnabled(value: Boolean) = store.edit { it[crossfadeEnabledKey] = value }
    suspend fun crossfadeSeconds(value: Int) = store.edit { it[crossfadeSecondsKey] = value.coerceIn(4, 10) }
    suspend fun crossfadeAnimation(value: CrossfadeAnimation) =
        store.edit { it[crossfadeAnimationKey] = value.name }
    suspend fun crossfadeMode(value: CrossfadeMode) =
        store.edit { it[crossfadeModeKey] = value.name }
    suspend fun cacheLimitMb(value: Int) = store.edit { it[cacheLimitKey] = value }
    suspend fun githubMirror(value: GithubMirror) = store.edit { it[githubMirrorKey] = value.name }

    /** 墙内设置：在同一次 edit 里读出当前值再写回，连着点两个开关也不会互相覆盖。 */
    suspend fun poster(transform: (PosterPrefs) -> PosterPrefs) = store.edit {
        val value = transform(posterPrefs(it))
        it[posterTintKey] = value.tint.name
        it[posterLightsOutKey] = value.lightsOut
        it[posterIndexKey] = value.showIndex
        it[posterTranslationKey] = value.translation
        it[posterFollowKey] = value.follow
        it[posterTvDriftKey] = value.tvDrift
        it[posterZoomPhoneKey] = PosterZoom.snap(value.zoom.phone)
        it[posterZoomTabletKey] = PosterZoom.snap(value.zoom.tablet)
        it[posterZoomTvKey] = PosterZoom.snap(value.zoom.tv)
    }

    /** 滑动操作（设置 › 滑动操作）：只在本机，不进同步。 */
    suspend fun swipe(transform: (SwipePrefs) -> SwipePrefs) = store.edit {
        SwipePrefsCodec.write(it, transform(SwipePrefsCodec.read(it)))
    }

    /** 歌词分享页的选项：只在本机，不进同步。 */
    suspend fun share(transform: (SharePrefs) -> SharePrefs) = store.edit {
        SharePrefsCodec.write(it, transform(SharePrefsCodec.read(it)))
    }

    private fun posterPrefs(values: Preferences) = PosterPrefs(
        tint = values[posterTintKey].toPosterTint(),
        lightsOut = values[posterLightsOutKey] ?: false,
        showIndex = values[posterIndexKey] ?: true,
        translation = values[posterTranslationKey] ?: true,
        follow = values[posterFollowKey] ?: true,
        tvDrift = values[posterTvDriftKey] ?: true,
        zoom = PosterZoom(
            phone = PosterZoom.snap(values[posterZoomPhoneKey] ?: PosterZoom.DEFAULT),
            tablet = PosterZoom.snap(values[posterZoomTabletKey] ?: PosterZoom.DEFAULT),
            tv = PosterZoom.snap(values[posterZoomTvKey] ?: PosterZoom.DEFAULT),
        ),
    )

    private fun lyricStagePrefs(values: Preferences) = LyricStagePrefs(
        visualizer = values[lyricVisualizerKey] ?: "classic",
        auto = LyricAutoSwitch.entries.firstOrNull { it.name == values[lyricVisualizerAutoKey] } ?: LyricAutoSwitch.Fixed,
        tempoRules = runCatching { json.decodeFromString<List<TempoRule>>(values[lyricTempoRulesKey] ?: "") }
            .getOrNull()?.takeIf { it.size == DefaultTempoRules.size } ?: DefaultTempoRules,
        spectrum = values[lyricSpectrumKey] ?: false,
        subline = LyricSubline.entries.firstOrNull { it.name == values[lyricSublineKey] } ?: LyricSubline.Translation,
        pluginSettings = runCatching {
            json.decodeFromString<Map<String, Map<String, String>>>(values[lyricPluginSettingsKey] ?: "{}")
        }.getOrDefault(emptyMap()),
        pins = runCatching { json.decodeFromString<Map<String, String>>(values[lyricVisualizerPinsKey] ?: "{}") }
            .getOrDefault(emptyMap()),
        hintSeen = values[lyricHintSeenKey] ?: false,
        randomPool = decodeStringSet(values[lyricRandomPoolKey]),
        recentColors = decodeStringList(values[lyricRecentColorsKey]).take(RECENT_COLORS_LIMIT),
        presets = decodePresets(values[lyricStagePresetsKey]),
    )

    /** 全屏歌词动画的设置：同一次 edit 里读出当前值再写回，连着改几项不会互相覆盖。 */
    suspend fun lyricStage(transform: (LyricStagePrefs) -> LyricStagePrefs) = store.edit {
        val value = transform(lyricStagePrefs(it))
        it[lyricVisualizerKey] = value.visualizer
        it[lyricVisualizerAutoKey] = value.auto.name
        it[lyricTempoRulesKey] = json.encodeToString(value.tempoRules)
        it[lyricSpectrumKey] = value.spectrum
        it[lyricSublineKey] = value.subline.name
        it[lyricPluginSettingsKey] = json.encodeToString(value.pluginSettings)
        it[lyricVisualizerPinsKey] = json.encodeToString(value.pins)
        it[lyricHintSeenKey] = value.hintSeen
        it[lyricRandomPoolKey] = json.encodeToString(value.randomPool.toList())
        it[lyricRecentColorsKey] = json.encodeToString(value.recentColors.take(RECENT_COLORS_LIMIT))
        it[lyricStagePresetsKey] = json.encodeToString(value.presets)
    }

    suspend fun lyricFonts(primaryName: String? = null, fallbackName: String? = null) = store.edit {
        primaryName?.let { name -> it[lyricFontPrimaryKey] = name }
        fallbackName?.let { name -> it[lyricFontFallbackKey] = name }
    }

    suspend fun lyricText(transform: (LyricTextPrefs) -> LyricTextPrefs) = store.edit {
        val current = LyricTextPrefs(
            splitBilingual = it[lyricSplitBilingualKey] ?: false,
            traditional = it[lyricTraditionalKey] ?: false,
            autoRomanize = it[lyricAutoRomanizeKey] ?: false,
        )
        val value = transform(current)
        it[lyricSplitBilingualKey] = value.splitBilingual
        it[lyricTraditionalKey] = value.traditional
        it[lyricAutoRomanizeKey] = value.autoRomanize
    }

    /** 这首歌记下的随机结果和速度；没记过返回 null。 */
    suspend fun lyricMemory(songKey: String): LyricMemoryEntry? {
        if (songKey.isBlank()) return null
        return decodeLyricMemory(lyricMemory.data.first()[lyricVisualizerMemoryKey])[songKey]
    }

    /** 更新这首歌的记录并挪到最新；超过 [LYRIC_MEMORY_LIMIT] 首时丢掉最久没听的。 */
    suspend fun rememberLyric(songKey: String, update: (LyricMemoryEntry) -> LyricMemoryEntry) {
        if (songKey.isBlank()) return
        lyricMemory.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val entry = update(memory.remove(songKey) ?: LyricMemoryEntry())
            memory[songKey] = entry
            while (memory.size > LYRIC_MEMORY_LIMIT) memory.remove(memory.keys.first())
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(memory)
        }
    }

    /** 记着随机结果（模式不为空）的歌有几首。 */
    suspend fun lyricMemoryModeCount(): Int =
        decodeLyricMemory(lyricMemory.data.first()[lyricVisualizerMemoryKey]).values.count { it.mode.isNotEmpty() }

    /**
     * 忘掉所有随机结果：模式清空、测到的速度留着，只剩模式的整条删掉。返回“歌曲 key → 原来的模式”，撤销时交给 [restoreLyricModes]。
     */
    suspend fun forgetLyricModes(): Map<String, String> {
        val forgotten = LinkedHashMap<String, String>()
        lyricMemory.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val kept = LinkedHashMap<String, LyricMemoryEntry>()
            for ((key, entry) in memory) {
                if (entry.mode.isNotEmpty()) forgotten[key] = entry.mode
                if (entry.bpm > 0f) kept[key] = entry.copy(mode = "")
            }
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(kept)
        }
        return forgotten
    }

    /** 撤销 [forgetLyricModes]：只写回现在模式还空着的歌（这期间重新抽过的不动），不改顺序，超出上限时丢最久的。 */
    suspend fun restoreLyricModes(modes: Map<String, String>) {
        if (modes.isEmpty()) return
        lyricMemory.edit {
            val memory = decodeLyricMemory(it[lyricVisualizerMemoryKey])
            val restored = LinkedHashMap<String, LyricMemoryEntry>()
            // 按原来的先后排回去：撤销的条目在前（更早），这期间新记的在后
            for ((key, mode) in modes) {
                val current = memory[key]
                restored[key] = if (current == null) LyricMemoryEntry(mode = mode) else if (current.mode.isEmpty()) current.copy(mode = mode) else current
            }
            for ((key, entry) in memory) if (key !in restored) restored[key] = entry
            while (restored.size > LYRIC_MEMORY_LIMIT) restored.remove(restored.keys.first())
            it[lyricVisualizerMemoryKey] = json.encodeToString<Map<String, LyricMemoryEntry>>(restored)
        }
    }

    /** JSON 对象按写入顺序解码，最早的在前面。 */
    private fun decodeLyricMemory(raw: String?): LinkedHashMap<String, LyricMemoryEntry> =
        runCatching { LinkedHashMap(json.decodeFromString<Map<String, LyricMemoryEntry>>(raw ?: "{}")) }
            .getOrDefault(LinkedHashMap())

    suspend fun sodaSigner(url: String, token: String, enabled: Boolean = true) = store.edit {
        it[sodaSignerUrlKey] = url.trim()
        it[sodaSignerTokenKey] = token.trim()
        it[sodaSignerEnabledKey] = enabled
    }

    suspend fun thirdPartyLyrics(enabled: Boolean) = store.edit {
        it[thirdPartyLyricsKey] = enabled
    }

    suspend fun metadataFallback(enabled: Boolean) = store.edit {
        it[metadataFallbackKey] = enabled
    }

    suspend fun cookie(provider: String, cookie: String) = store.edit {
        val current = runCatching {
            json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        val updatedAt = runCatching {
            json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        if (cookie.isBlank()) current.remove(provider) else current[provider] = cookie
        updatedAt[provider] = System.currentTimeMillis()
        it[cookiesKey] = json.encodeToString(current)
        it[cookieUpdatedAtKey] = json.encodeToString(updatedAt)
    }

    /** 只有当前值仍等于调用方读到的那一份才写入，避免迟到的认证探测覆盖删除或新账号配置。app 侧 SubsonicManager 也要用，跨模块开放。 */
    suspend fun cookieIfCurrent(provider: String, expectedRaw: String, cookie: String): Boolean {
        var written = false
        store.edit {
            val current = runCatching {
                json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
            }.getOrDefault(emptyMap()).toMutableMap()
            if (current[provider] != expectedRaw) return@edit
            current[provider] = cookie
            val updatedAt = runCatching {
                json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
            }.getOrDefault(emptyMap()).toMutableMap()
            updatedAt[provider] = System.currentTimeMillis()
            it[cookiesKey] = json.encodeToString(current)
            it[cookieUpdatedAtKey] = json.encodeToString(updatedAt)
            written = true
        }
        return written
    }

    /** 同步读取 Cookie 和修改时间，避免两次 DataStore 读取之间出现不一致。 */
    suspend fun cookieSnapshot(): CookieSnapshot {
        val values = store.data.first()
        return CookieSnapshot(
            cookies = runCatching {
                json.decodeFromString<Map<String, String>>(values[cookiesKey] ?: "{}")
            }.getOrDefault(emptyMap()),
            updatedAt = runCatching {
                json.decodeFromString<Map<String, Long>>(values[cookieUpdatedAtKey] ?: "{}")
            }.getOrDefault(emptyMap()),
        )
    }

    /**
     * 应用同步合并结果。updatedAt 里出现但 active 里没有的平台视为已退出登录。
     * removeAbsent 为 true 时（用云端覆盖本机）连未出现在快照里的平台一起清掉。
     */
    suspend fun applyCookies(
        active: Map<String, String>,
        updatedAt: Map<String, Long>,
        removeAbsent: Boolean,
    ) = store.edit {
        val current = runCatching {
            json.decodeFromString<Map<String, String>>(it[cookiesKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        val times = runCatching {
            json.decodeFromString<Map<String, Long>>(it[cookieUpdatedAtKey] ?: "{}")
        }.getOrDefault(emptyMap()).toMutableMap()
        if (removeAbsent) {
            current.keys.filterNot { provider -> provider in updatedAt }.forEach { current.remove(it) }
            times.keys.filterNot { provider -> provider in updatedAt }.forEach { times.remove(it) }
        }
        updatedAt.forEach { (provider, time) ->
            if (active.containsKey(provider)) {
                current[provider] = active.getValue(provider)
            } else {
                current.remove(provider)
            }
            times[provider] = time
        }
        it[cookiesKey] = json.encodeToString(current)
        it[cookieUpdatedAtKey] = json.encodeToString(times)
    }

    suspend fun saveAggregation(value: AggregationSettings) = store.edit {
        it[aggregationKey] = json.encodeToString(value)
    }

    /** 设置 › 本地音乐：在 edit 里读旧值再改，扫描写“上次扫描时间”和用户改开关同时发生也不会互相覆盖。 */
    suspend fun localMusic(transform: (LocalMusicSettings) -> LocalMusicSettings) = store.edit {
        val current = runCatching { json.decodeFromString<LocalMusicSettings>(it[localMusicKey] ?: "") }
            .getOrDefault(LocalMusicSettings())
        it[localMusicKey] = json.encodeToString(transform(current))
    }

    suspend fun saveEqualizer(value: EqualizerSettings) = store.edit {
        it[equalizerEnabledKey] = value.enabled
        it[equalizerPresetKey] = value.presetId
        it[equalizerBandsKey] = json.encodeToString(value.customBands)
    }

    suspend fun addSearchHistory(query: String) = store.edit {
        val value = query.trim()
        if (value.isBlank()) return@edit
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).toMutableList()
        current.remove(value)
        current.add(0, value)
        it[searchHistoryKey] = json.encodeToString(current.take(20))
    }

    suspend fun removeSearchHistory(query: String) = store.edit {
        val current = runCatching {
            json.decodeFromString<List<String>>(it[searchHistoryKey] ?: "[]")
        }.getOrDefault(emptyList()).filterNot { item -> item == query }
        it[searchHistoryKey] = json.encodeToString(current)
    }

    suspend fun clearSearchHistory() = store.edit { it[searchHistoryKey] = "[]" }

    suspend fun lastUpdateCheck(value: Long) = store.edit { it[lastUpdateCheckKey] = value }
}

data class CookieSnapshot(
    val cookies: Map<String, String> = emptyMap(),
    val updatedAt: Map<String, Long> = emptyMap(),
)

data class AppSettings(
    val provider: String = "netease",
    val chartsSource: String = "",
    val playlistsSource: String = "",
    /** 冷启动打开的顶层入口（TopTab.route），空表示默认「榜单」。 */
    val startupTab: String = "",
    /** 歌单页上次用的浏览方式（PlaylistMode 名字），空表示默认「推荐」。 */
    val playlistsMode: String = "",
    /** 听歌模式页上次选的分组，空表示「全部」。 */
    val listenGroup: String = "",
    val theme: String = "system",
    /** 界面布局：Auto / Phone / Tablet / Tv。 */
    val layoutMode: String = "Auto",
    val cookies: Map<String, String> = emptyMap(),
    /**
     * 汽水签名器（sodam / libmssdk 的 /sign 协议）。地址和 Token 留空表示用内置的
     * SodaM 公共签名服务；[sodaSignerEnabled] 关掉后只用公开试听流。
     */
    val sodaSignerUrl: String = "",
    val sodaSignerToken: String = "",
    val sodaSignerEnabled: Boolean = true,
    /**
     * Apple Music 匿名歌词是否走第三方中转（zarz / paxsenix / AMLL）。默认开；
     * 关掉后 Apple 歌词只试 Apple 自己的接口，匿名用户等于没有歌词。
     */
    val thirdPartyLyrics: Boolean = true,
    /** 原平台无词或缺封面时，用公共歌词库和公开封面搜索补全。 */
    val metadataFallback: Boolean = true,
    val aggregation: AggregationSettings = AggregationSettings(),
    /** 设置 › 本地音乐（扫描范围、匹配、播放兜底）。 */
    val localMusic: LocalMusicSettings = LocalMusicSettings(),
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
    val volumeNormalization: Boolean = false,
    /** 播放音质偏好：`auto` 或某个 `AudioQuality.id`。平台不支持所选档位时按 auto 处理。 */
    val audioQuality: String = "auto",
    val showBottomNav: Boolean = true,
    val lyricsBackground: LyricsBackgroundMode = LyricsBackgroundMode.Ambient,
    /** 系统媒体播放条第二行显示当前歌词（顶掉歌手名）；默认关。 */
    val notificationLyrics: Boolean = false,
    /** 系统悬浮窗歌词；默认关，开启前还要取得系统悬浮窗权限。 */
    val floatingLyricsEnabled: Boolean = false,
    /** 词幕 Provider 默认开；Subscriber 只在用户主动打开后连接。两项都只存在本机。 */
    val lyriconProviderEnabled: Boolean = true,
    val lyriconSubscriberEnabled: Boolean = false,
    val rhythmIntensity: RhythmIntensity = RhythmIntensity.Standard,
    val coverShape: CoverShapeChoice = CoverShapeChoice.Random,
    /** 电视环境模式右上角是否显示时钟。 */
    val tvAmbientClock: Boolean = true,
    /** 折叠屏桌面姿态播放页的上半屏：默认歌词。 */
    val tabletopTop: TabletopTop = TabletopTop.Lyrics,
    /**
     * 电视播放时给窗口挂 FLAG_KEEP_SCREEN_ON：屏幕不超时，系统屏保就不会启动。
     * 默认关：屏幕会一直亮着，OLED 有烧屏风险；息屏听歌、离开应用都不受影响。
     */
    val keepScreenOn: Boolean = false,
    /**
     * 电视上离开应用后继续播放（后台播放）。默认关：按 Home、切到别的应用、退出应用都会暂停，
     * 和手机、平板相反——那边一直是后台播放。
     */
    val tvBackgroundPlay: Boolean = false,
    /** 跟拍校准：律动画面相对“听到的位置”再晚多少毫秒（负数提前），−200..200。 */
    val rhythmOffsetMs: Int = 0,
    /** 播放页角落显示跟拍调试信息（只在可调试的安装包里能打开）。 */
    val rhythmDebug: Boolean = false,
    /** 海报墙的墙内设置，队列墙和详情页海报视图共用。 */
    val poster: PosterPrefs = PosterPrefs(),
    /** 歌曲列表左右滑的按钮和拉到头（设置 › 滑动操作）。 */
    val swipe: SwipePrefs = SwipePrefs(),
    /** 歌词分享页上次用的样式、配色等选项。 */
    val share: SharePrefs = SharePrefs(),
    /** 详情页上次用的视图（列表 / 海报），不单独做设置项。 */
    val collectionView: CollectionView = CollectionView.List,
    /** 点按迷你播放器打开播放页还是海报墙（设置 › 播放页）。 */
    val miniPlayerTarget: MiniPlayerTarget = MiniPlayerTarget.Player,
    /** 播放上报总开关：关掉后任何平台都不再回传播放记录。 */
    val playReportEnabled: Boolean = true,
    /** 汽水上报用的设备标识，空表示还没生成过（见 SettingsStore.sodaReportDeviceId）。 */
    val sodaReportDeviceId: String = "",
    /** 酷狗概念版「听歌领会员」是否在启动时自动领一天。 */
    val kugouWelfareAuto: Boolean = true,
    /** 上次自动领取的日期（北京时间 yyyy-MM-dd），空表示还没自动领过。 */
    val kugouWelfareLastClaimDate: String = "",
    /** 全屏歌词动画。 */
    val lyricStage: LyricStagePrefs = LyricStagePrefs(),
    val lyricFonts: LyricFontPrefs = LyricFontPrefs(),
    /** 歌词文字变换（拆单行双语、简转繁、补罗马音）。 */
    val lyricText: LyricTextPrefs = LyricTextPrefs(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val searchHistory: List<String> = emptyList(),
    val lastUpdateCheck: Long = 0L,
    val colorSource: String = "Cover",
    val customSeed: Int = 0xFF006B5F.toInt(),
    val browseStyle: String = "TonalSpot",
    val playerStyle: String = "Vibrant",
    val contrast: Float = 0f,
    /** 界面文字缩放倍数（1 为标准），见 Appearance.fontScale。 */
    val fontScale: Float = 1f,
    val amoled: Boolean = false,
    val blurMode: String = "Auto",
    val lastSeed: Int = 0,
    val sleepFade: Boolean = true,
    /** 切歌过渡总开关：自动接下一首时把上一首的结尾和下一首的开头叠起来。 */
    val crossfadeEnabled: Boolean = false,
    /** 交叉淡化的时长（秒），只在这一步用；智能过渡在后续阶段自己决定长度。 */
    val crossfadeSeconds: Int = 6,
    /** 播放页上画不画这次过渡：`Off` / `Ring` / `Outline`，见 [CrossfadeAnimation]。 */
    val crossfadeAnimation: String = "Ring",
    /** 交叉淡化还是智能过渡，见 [CrossfadeMode]。 */
    val crossfadeMode: String = "Automix",
    /** 播放缓存上限（MB），0 表示按设备默认：电视 256，其他 512。 */
    val cacheLimitMb: Int = 0,
    /** 更新检查与下载用哪条 GitHub 线路。 */
    val githubMirror: GithubMirror = GithubMirror.Auto,
)
