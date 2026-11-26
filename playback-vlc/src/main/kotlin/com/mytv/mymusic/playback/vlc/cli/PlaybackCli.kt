package com.mytv.mymusic.playback.vlc.cli

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.core.AutomixMethod
import com.mytv.mymusic.playback.core.CrossfadeMode
import com.mytv.mymusic.playback.core.CrossfadeSettings
import com.mytv.mymusic.playback.core.EqualizerCurve
import com.mytv.mymusic.playback.core.PlaybackState
import com.mytv.mymusic.playback.core.SleepTimerState
import com.mytv.mymusic.playback.vlc.PlaybackLog
import com.mytv.mymusic.playback.vlc.VlcOptions
import com.mytv.mymusic.playback.vlc.VlcPlaybackEngine
import java.io.File
import kotlin.system.exitProcess

/**
 * 命令行 runner：不打开界面听声，拿来验收播放引擎。
 *
 * 用法：
 * ```
 * # 1) 看一眼 native 库与 10 段均衡器（不播）
 * ./gradlew :playback-vlc:run --args="probe"
 *
 * # 2) 放本地 flac，开 10 段均衡器 + 响度归一化
 * ./gradlew :playback-vlc:run --args="play F:\tmp\audio-fixtures\tone-30s.flac --seconds 30 --eq pop --normalize 3"
 *
 * # 3) 本地 flac 与网络直链之间做 6 秒交叉淡化，并挂 1 分钟定时关闭
 * ./gradlew :playback-vlc:run --args="demo --first F:\tmp\audio-fixtures\track-a-60s.flac --second https://example.com/track-b.mp3 --crossfade 6 --sleep 1 --seconds 70"
 * ```
 *
 * 退出码：0 正常；1 参数错误；2 libVLC 不可用 / 播放失败（CI 里据此判断）。
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        usage()
        exitProcess(if (args.isEmpty()) 1 else 0)
    }
    val cli = CliArgs.parse(args.drop(1))
    PlaybackLog.info("CLI", "参数 ${args.joinToString(" ")}")
    try {
        when (args[0]) {
            "probe" -> probe(cli)
            "play" -> play(cli)
            "demo" -> demo(cli)
            else -> {
                PlaybackLog.error("CLI", "未知子命令：${args[0]}")
                usage()
                exitProcess(1)
            }
        }
    } catch (error: Throwable) {
        PlaybackLog.error("CLI", "失败：${error.javaClass.simpleName}: ${error.message}")
        exitProcess(2)
    }
}

private fun usage() {
    println(
        """
        用法：
          probe                              打印 libVLC 版本 / 均衡器段数与预设（不播放）
          play <url> [选项]                   播放一路音源
          demo --first <url> --second <url>  两首之间交叉淡化 + 定时关闭

        选项：
          --seconds N        播放时长（默认 30）
          --eq <preset>      均衡器预设：flat/pop/rock/jazz/vocal（默认关）
          --normalize dB     响度归一化增益（默认 0）
          --volume 0..1      音量（默认 0.8）
          --crossfade N      交叉淡化秒数（给了才开）
          --sleep N          定时关闭分钟数（给了才开）
          --vlc-home PATH    VLC 安装目录（默认自动发现）
        """.trimIndent(),
    )
}

private class CliArgs(
    val urls: List<String>,
    val seconds: Int,
    val eqPreset: String?,
    val normalizeDb: Float,
    val volume: Float,
    val crossfadeSeconds: Int?,
    val sleepMinutes: Int?,
    val vlcHome: String?,
    val first: String?,
    val second: String?,
) {
    companion object {
        fun parse(args: List<String>): CliArgs {
            val urls = mutableListOf<String>()
            var seconds = 30
            var eqPreset: String? = null
            var normalizeDb = 0f
            var volume = 0.8f
            var crossfadeSeconds: Int? = null
            var sleepMinutes: Int? = null
            var vlcHome: String? = null
            var first: String? = null
            var second: String? = null

            var index = 0
            while (index < args.size) {
                val arg = args[index]
                fun value(): String = args.getOrNull(index + 1) ?: error("$arg 缺少参数值")
                when (arg) {
                    "--seconds" -> { seconds = value().toInt(); index += 2 }
                    "--eq" -> { eqPreset = value(); index += 2 }
                    "--normalize" -> { normalizeDb = value().toFloat(); index += 2 }
                    "--volume" -> { volume = value().toFloat(); index += 2 }
                    "--crossfade" -> { crossfadeSeconds = value().toInt(); index += 2 }
                    "--sleep" -> { sleepMinutes = value().toInt(); index += 2 }
                    "--vlc-home" -> { vlcHome = value(); index += 2 }
                    "--first" -> { first = value(); index += 2 }
                    "--second" -> { second = value(); index += 2 }
                    else -> { urls += arg; index += 1 }
                }
            }
            return CliArgs(
                urls = urls,
                seconds = seconds,
                eqPreset = eqPreset,
                normalizeDb = normalizeDb,
                volume = volume,
                crossfadeSeconds = crossfadeSeconds,
                sleepMinutes = sleepMinutes,
                vlcHome = vlcHome,
                first = first,
                second = second,
            )
        }
    }
}

/** 不播放，只把"这台机器上的 libVLC 长什么样"写进日志：排查环境问题第一步。 */
private fun probe(cli: CliArgs) {
    val engine = VlcPlaybackEngine(options = VlcOptions.discover(cli.vlcHome))
    try {
        PlaybackLog.info("CLI", "libVLC = ${engine.describe()}")
        PlaybackLog.info("CLI", "nativeLibraryPath = ${engine.nativeLibraryPath}")
        val bands = engine.equalizer.state.value.bands
        PlaybackLog.info("CLI", "均衡器 ${bands.size} 段：${bands.joinToString { "${it.frequencyHz}Hz" }}")
        PlaybackLog.info("CLI", "预设：${com.mytv.mymusic.playback.core.EqPresets.all.joinToString { "${it.id}=${it.label}" }}")
        // 把"流行"预设落到 10 段上，直观看到每一段的 dB
        val amps = EqualizerCurve.amplitudes(
            com.mytv.mymusic.playback.core.EqualizerSettings(presetId = "pop"),
            bands.map { it.frequencyHz },
        )
        PlaybackLog.info("CLI", "流行预设曲线：${amps.joinToString { "%.1f".format(it) }} dB")
        PlaybackLog.info("CLI", "缓存目录 = ${engine.cache.dir}")
    } finally {
        engine.release()
    }
}

private fun play(cli: CliArgs) {
    val url = cli.urls.firstOrNull() ?: error("play 需要一个音源地址")
    val engine = VlcPlaybackEngine(options = VlcOptions.discover(cli.vlcHome))
    try {
        PlaybackLog.info("CLI", "libVLC = ${engine.describe()}")
        engine.setVolume(cli.volume)
        if (cli.normalizeDb != 0f) engine.setNormalizationDb(cli.normalizeDb)
        cli.eqPreset?.let {
            engine.equalizer.setEnabled(true)
            engine.equalizer.selectPreset(it)
            PlaybackLog.info(
                "CLI",
                "均衡器 $it：${engine.equalizer.state.value.bands.joinToString { "%.1f".format(it.levelDb) }} dB",
            )
        }
        engine.prepare(sourceOf(url, 0))
        engine.play()
        watch(engine, cli.seconds, sleepMinutes = cli.sleepMinutes)
    } finally {
        engine.release()
    }
}

/** 两首之间交叉淡化：验收里"淡化可观测生效"就看这段日志的两条音量曲线。 */
private fun demo(cli: CliArgs) {
    val first = cli.first ?: error("demo 需要 --first")
    val second = cli.second ?: error("demo 需要 --second")
    val engine = VlcPlaybackEngine(options = VlcOptions.discover(cli.vlcHome))
    try {
        PlaybackLog.info("CLI", "libVLC = ${engine.describe()}")
        engine.setVolume(cli.volume)
        engine.queue.setQueue(listOf(song(first, "第一首"), song(second, "第二首")))
        val seconds = cli.crossfadeSeconds ?: 6
        engine.crossfade.configure(CrossfadeSettings(enabled = true, seconds = seconds, mode = CrossfadeMode.Fade))
        cli.eqPreset?.let {
            engine.equalizer.setEnabled(true)
            engine.equalizer.selectPreset(it)
        }
        cli.sleepMinutes?.let { minutes ->
            engine.sleepTimer.start(minutes)
            PlaybackLog.info("CLI", "定时关闭 ${minutes} 分钟（最后 30 秒淡出）")
        }
        engine.playAt(0)
        watch(engine, cli.seconds, sleepMinutes = cli.sleepMinutes)
        PlaybackLog.info("CLI", "上一次接歌：${engine.crossfade.report.value?.headline ?: "（没有发生过渡）"}")
        PlaybackLog.info("CLI", "接法：${engine.crossfade.report.value?.detail.orEmpty()}")
    } finally {
        engine.release()
    }
}

/**
 * 每秒打一行位置 / 音量 / 状态，直到时长到了或播放结束。
 * 这一行就是验收要的"30 秒播放日志"——只打必要字段，不刷 buffering 百分比。
 */
private fun watch(engine: VlcPlaybackEngine, seconds: Int, sleepMinutes: Int?) {
    val startedAt = System.nanoTime()
    var lastLoggedSecond = -1
    var lastXfadeStep = -1
    while (true) {
        Thread.sleep(100)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val snapshot = engine.snapshot.value
        val second = (elapsedMs / 1_000).toInt()
        if (second != lastLoggedSecond) {
            lastLoggedSecond = second
            val sleep = engine.sleepTimer.state.value
            PlaybackLog.info(
                "PLAY",
                "t=${elapsedMs}ms pos=${snapshot.positionMs}ms dur=${snapshot.durationMs}ms " +
                    "vol=${"%.2f".format(snapshot.volume)} state=${snapshot.state}${sleepNote(sleep)}",
            )
        }
        // 交叉淡化进行中：每 10% 记一次两路音量，验证等功率曲线（不是线性的）
        val transition = engine.crossfade.transition.value
        if (transition != null) {
            val progress = transition.progressAt(engine.nowMs())
            val step = (progress * 10f).toInt()
            if (step > lastXfadeStep) {
                lastXfadeStep = step
                PlaybackLog.info(
                    "XFADE",
                    "进度=${"%.2f".format(progress)} 主音量=${"%.3f".format(engine.primaryVolumePercent() / 100f)} " +
                        "次音量=${"%.3f".format(engine.incomingVolumePercent() / 100f)} 接法=${transition.method}",
                )
            }
        }
        if (elapsedMs >= seconds * 1000L) {
            PlaybackLog.info("CLI", "到达时长 ${seconds}s，停止")
            break
        }
        if (snapshot.state == PlaybackState.Ended || snapshot.state == PlaybackState.Error) {
            PlaybackLog.info("CLI", "播放结束：state=${snapshot.state} error=${snapshot.errorText}")
            break
        }
        // 定时关闭到点后引擎会暂停；开过定时才看这个条件
        if (sleepMinutes != null && !engine.sleepTimer.state.value.active && elapsedMs > 1_000) {
            PlaybackLog.info("CLI", "定时关闭已触发（暂停播放）")
            break
        }
    }
    PlaybackLog.info(
        "CLI",
        "摘要：state=${engine.snapshot.value.state} pos=${engine.snapshot.value.positionMs}ms " +
            "均衡器=${engine.equalizer.state.value.presetId} 生效=${engine.equalizer.state.value.effective} " +
            "缓存=${engine.cache.sizeBytes()}B",
    )
}

private fun sleepNote(state: SleepTimerState): String =
    if (!state.active) "" else if (state.endOfTrack) " sleep=播完停" else " sleep=剩 ${state.remainingMs() / 1000}s"

private fun song(url: String, title: String): Song = Song(
    id = title,
    name = title,
    artist = "验收",
    album = "playback-vlc",
    duration = 60,
    source = "local",
    url = url,
    ext = url.substringAfterLast('.', "").substringBefore('?'),
)

private fun sourceOf(url: String, durationMs: Long) = com.mytv.mymusic.playback.core.PlaybackSource(
    url = url,
    ext = File(url).extension,
    trackId = url,
    title = File(url).name,
    live = durationMs <= 0L && url.contains("live", ignoreCase = true),
)
