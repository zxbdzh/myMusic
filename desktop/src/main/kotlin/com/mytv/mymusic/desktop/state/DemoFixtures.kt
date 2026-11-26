package com.mytv.mymusic.desktop.state

/**
 * 示例曲库：24 首单曲、6 张榜单、6 个推荐歌单、3 个自建歌单、2 段示例歌词。
 *
 * 歌词是自写的占位句（不是真实歌词），只用来验证滚动、当前行高亮、逐行进度这三件事；
 * 真歌词由 t2 的 provider 取回，走同一条 `MusicCatalog.lyrics` 通路。
 */
object DemoFixtures {

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        provider: String = "netease",
        providerName: String = "网易云",
        playable: Boolean = true,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        providerId = provider,
        providerName = providerName,
        durationMs = durationMs,
        playable = playable,
    )

    val tracks: List<Track> = listOf(
        track("t01", "晴天", "周杰伦", "叶惠美", 269_000),
        track("t02", "起风了", "买辣椒也用券", "起风了", 325_000),
        track("t03", "稻香", "周杰伦", "魔杰座", 223_000),
        track("t04", "光年之外", "邓紫棋", "光年之外", 235_000),
        track("t05", "孤勇者", "陈奕迅", "孤勇者", 258_000),
        track("t06", "一路生花", "温奕心", "一路生花", 232_000),
        track("t07", "海阔天空", "Beyond", "乐与怒", 326_000),
        track("t08", "勋章", "鹿晗", "勋章", 244_000),
        track("t09", "年少有为", "李荣浩", "耳朵", 285_000),
        track("t10", "我曾", "隔壁老樊", "我曾", 260_000),
        track("t11", "大鱼", "周深", "大鱼", 259_000),
        track("t12", "漠河舞厅", "柳爽", "漠河舞厅", 300_000),
        track("t13", "夜的第七章", "周杰伦", "依然范特西", 305_000),
        track("t14", "后来", "刘若英", "我等你", 298_000),
        track("t15", "花海", "周杰伦", "魔杰座", 265_000),
        track("t16", "富士山下", "陈奕迅", "What's Going On…?", 285_000),
        track("t17", "七里香", "周杰伦", "七里香", 299_000),
        track("t18", "告白气球", "周杰伦", "周杰伦的床边故事", 215_000),
        track("t19", "句号", "邓紫棋", "启示录", 250_000),
        track("t20", "我怀念的", "孙燕姿", "逆光", 327_000),
        track(
            "t21", "My Love", "Westlife", "Coast to Coast", 232_000,
            provider = "apple", providerName = "Apple Music",
        ),
        track(
            "t22", "Shape of You", "Ed Sheeran", "÷ (Divide)", 234_000,
            provider = "apple", providerName = "Apple Music",
        ),
        track(
            "t23", "A Thousand Years", "Christina Perri", "The Twilight Saga", 285_000,
            provider = "apple", providerName = "Apple Music", playable = false,
        ),
        track(
            "t24", "City of Stars", "Ryan Gosling", "La La Land", 149_000,
            provider = "bilibili", providerName = "Bilibili", playable = false,
        ),
    )

    val charts: List<ChartSummary> = listOf(
        ChartSummary("hot", "热歌榜", "每天更新 · 100 首", 128_400_000),
        ChartSummary("new", "新歌速递", "本周上新 · 60 首", 42_700_000),
        ChartSummary("surge", "飙升榜", "热度涨幅 · 100 首", 76_300_000),
        ChartSummary("origin", "原创榜", "独立音乐 · 50 首", 18_900_000),
        ChartSummary("western", "欧美热歌榜", "Billboard 视角 · 50 首", 33_500_000),
        ChartSummary("golden", "华语金曲榜", "经典重听 · 80 首", 91_200_000),
    )

    private val chartTrackIds: Map<String, List<String>> = mapOf(
        "hot" to listOf("t01", "t05", "t09", "t04", "t18", "t19", "t11", "t17"),
        "new" to listOf("t02", "t06", "t10", "t12", "t15", "t20"),
        "surge" to listOf("t06", "t08", "t13", "t03", "t14"),
        "origin" to listOf("t10", "t12", "t06", "t08"),
        "western" to listOf("t21", "t22", "t23", "t24"),
        "golden" to listOf("t07", "t14", "t16", "t20", "t11", "t03"),
    )

    fun chartTracks(id: String): List<Track> {
        val ids = chartTrackIds[id] ?: tracks.take(8).map { it.id }
        return ids.mapNotNull { trackId -> tracks.firstOrNull { it.id == trackId } }
    }

    val categories: List<String> = listOf("全部", "华语", "通勤", "深夜", "国风", "欧美")

    val playlists: List<PlaylistSummary> = listOf(
        PlaylistSummary("p1", "华语流行精选", "华语", 60, 12_800_000),
        PlaylistSummary("p2", "深夜电台", "深夜", 42, 6_400_000),
        PlaylistSummary("p3", "通勤路上", "通勤", 80, 21_300_000),
        PlaylistSummary("p4", "国风新声", "国风", 35, 3_900_000),
        PlaylistSummary("p5", "City Pop 回潮", "欧美", 28, 2_100_000),
        PlaylistSummary("p6", "钢琴与雨", "深夜", 24, 1_700_000),
    )

    private val playlistTrackIds: Map<String, List<String>> = mapOf(
        "p1" to listOf("t01", "t02", "t03", "t04", "t05", "t17", "t18"),
        "p2" to listOf("t12", "t14", "t20", "t10", "t11"),
        "p3" to listOf("t05", "t08", "t06", "t09", "t19"),
        "p4" to listOf("t11", "t17", "t13", "t15"),
        "p5" to listOf("t21", "t22", "t24"),
        "p6" to listOf("t20", "t16", "t23"),
    )

    fun playlistTracks(id: String): List<Track> {
        val ids = playlistTrackIds[id] ?: tracks.take(6).map { it.id }
        return ids.mapNotNull { trackId -> tracks.firstOrNull { it.id == trackId } }
    }

    val localPlaylists: List<PlaylistSummary> = listOf(
        PlaylistSummary("local1", "我喜欢的音乐（示例）", "本机", 5, 0),
        PlaylistSummary("local2", "写代码时听", "本机", 7, 0),
        PlaylistSummary("local3", "跑步歌单", "本机", 6, 0),
    )

    /** 两首带时间轴的示例歌词；其余歌曲返回空串，用来演示"没有歌词"的空状态。 */
    val lyrics: Map<String, String> = mapOf(
        "t01" to """
            [ti:示例歌词]
            [offset:0]
            [00:00.00]（示例歌词 · 非真实歌词内容）
            [00:06.40]窗外的光落在旧唱片上
            [00:13.20]风把夏天吹成了另一段旋律
            [00:20.60]我们在同一条街口转身
            [00:27.80]把没说完的话留给副歌
            [00:35.00]如果时间可以慢一点
            [00:42.30]就让这一句多停两拍
            [00:49.60]再回头的时候
            [00:56.10]你还在原地听
            [01:03.50]把音量调大一点
            [01:10.90]让心跳跟着鼓点走
            [01:18.20]这一段写给还没睡的人
            [01:25.60]写给每个按下播放的人
            [01:33.00]（间奏）
            [01:48.00]把音量调大一点
            [01:55.40]让心跳跟着鼓点走
            [02:02.80]这一段写给还没睡的人
            [02:10.20]写给每个按下播放的人
            [02:18.00]（尾奏 · 示例歌词结束）
        """.trimIndent(),
        "t05" to """
            [00:00.00]（示例歌词 · 非真实歌词内容）
            [00:07.10]灯亮起来之前
            [00:13.80]先把影子收进口袋
            [00:21.00]谁不是一边走一边学
            [00:28.40]把沉默过成答案
            [00:35.80]如果风真的会说话
            [00:43.10]它一定先说别停下
            [00:50.50]把每一次跌倒都留在身后
            [00:58.00]我们就这样往前走
            [01:05.30]（副歌）
            [01:12.70]把音量留给远方的车灯
            [01:20.00]把名字留给还没到的明天
            [01:27.40]如果你也听见了
            [01:34.80]就一起唱到最后一句
        """.trimIndent(),
    )
}
