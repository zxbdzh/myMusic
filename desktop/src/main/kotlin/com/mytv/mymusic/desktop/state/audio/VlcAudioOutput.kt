package com.mytv.mymusic.desktop.state.audio

import com.mytv.mymusic.desktop.state.AudioStream
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.playback.core.CrossfadeControl
import com.mytv.mymusic.playback.core.EqualizerControl
import com.mytv.mymusic.playback.core.PlaybackSource
import com.mytv.mymusic.playback.core.SleepTimer
import com.mytv.mymusic.playback.vlc.VlcOptions
import com.mytv.mymusic.playback.vlc.VlcPlaybackEngine
import com.mytv.mymusic.playback.vlc.VlcUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 真实 libVLC 后端适配器：强类型直连 `:playback-vlc` 的 [VlcPlaybackEngine]。
 *
 * 已移除早期占位期的 `Class.forName` 反射：编译期直接校验契约，并把引擎内置的
 * 10 段均衡器、交叉淡化与睡眠定时全部透出给桌面状态层与设置页。
 */
class VlcAudioOutput internal constructor(
    private val engine: VlcPlaybackEngine,
    scope: CoroutineScope,
    resolvedHome: File?,
) : AudioOutput {

    override val label: String = "libVLC"
    override val ready: Boolean = true
    override val detail: String = buildString {
        append("libVLC + vlcj 4.12.1")
        resolvedHome?.let { append(" · ").append(it.absolutePath) }
    }

    override val equalizer: EqualizerControl get() = engine.equalizer
    override val crossfade: CrossfadeControl get() = engine.crossfade
    override val sleepTimer: SleepTimer get() = engine.sleepTimer

    private val _snapshot = MutableStateFlow(AudioSnapshot())
    override val snapshot: StateFlow<AudioSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            // 引擎内部事件回调已汇入 snapshot，这里单向映射到桌面 UI 快照
            engine.snapshot.collect { core ->
                _snapshot.value = _snapshot.value.copy(
                    playing = core.isPlaying,
                    buffering = core.isBuffering,
                    positionMs = core.positionMs,
                    durationMs = core.durationMs,
                    volume = core.volume,
                    error = core.errorText,
                )
            }
        }
    }

    override fun load(track: Track, stream: AudioStream) {
        runCatching {
            engine.prepare(
                PlaybackSource(
                    url = stream.url,
                    ext = stream.ext,
                    headers = stream.headers,
                    trackId = track.id,
                    title = track.title,
                ),
            )
        }.onFailure { report("加载音源失败", it) }
    }

    override fun play() {
        runCatching { engine.play() }.onFailure { report("起播失败", it) }
    }

    override fun pause() {
        runCatching { engine.pause() }.onFailure { report("暂停失败", it) }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { engine.seekTo(positionMs) }.onFailure { report("跳转失败", it) }
    }

    override fun setVolume(volume: Float) {
        runCatching { engine.setVolume(volume.coerceIn(0f, 1f)) }
            .onFailure { report("设置音量失败", it) }
    }

    override fun release() {
        runCatching { engine.release() }
    }

    private fun report(prefix: String, error: Throwable) {
        val reason = error.message ?: error::class.java.simpleName
        _snapshot.value = _snapshot.value.copy(error = "$prefix：$reason")
    }

    companion object {
        /**
         * 构造真实 [VlcAudioOutput]；不吞掉缺失原因，调用方据此构造 [UnavailableAudioOutput]。
         *
         * 注意：必须先配置 `jna.library.path` 再构造 [VlcPlaybackEngine]，否则 JNA 会错过随包运行库。
         */
        fun create(scope: CoroutineScope, explicitHome: File? = null): Result<VlcAudioOutput> {
            val home = explicitHome?.takeIf { VlcInstallation.isValidVlcDir(it) }
                ?: VlcInstallation.resolveHome()
                ?: return Result.failure(
                    VlcUnavailableException(
                        "未检测到 libvlc.dll（已检查随包目录、myMusic.vlc.home、VLC_HOME 与默认安装目录）。" +
                            "请运行 `./gradlew :desktop:fetchVlcRuntime` 或安装 64 位 VLC 3.0.x。",
                    ),
                )
            VlcInstallation.prependJnaLibraryPath(home)
            val options = VlcOptions.discover(home.absolutePath)
            options.configureNativePath()
            return runCatching {
                val engine = VlcPlaybackEngine(options = options, scope = scope)
                VlcAudioOutput(engine = engine, scope = scope, resolvedHome = home)
            }
        }
    }
}

/**
 * libVLC 运行库定位器。
 *
 * 判断顺序（先随包、后环境、再系统，保证绿色版与开发态一致）：
 * 1. 打包随包目录：`compose.application.resources.dir/vlc`
 * 2. 开发态 Gradle 注入属性：`myMusic.vlc.home`
 * 3. 环境变量：`MYMUSIC_VLC_HOME` / `VLC_HOME`
 * 4. 开发态本地构建缓存：`desktop/build/vlc-runtime` / `build/vlc-runtime`
 * 5. Windows 常见安装目录：`Program Files/VideoLAN/VLC`
 * 6. 当前工作目录：`./vlc`
 */
object VlcInstallation {

    const val DEV_HOME_PROPERTY = "myMusic.vlc.home"

    fun isValidVlcDir(dir: File?): Boolean =
        dir != null && dir.isDirectory && File(dir, "libvlc.dll").isFile

    /** jpackage 随包目录或开发态注入目录。 */
    fun bundledDir(): File? {
        val fromResources = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "vlc") }
        if (isValidVlcDir(fromResources)) return fromResources

        val fromProp = System.getProperty(DEV_HOME_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (isValidVlcDir(fromProp)) return fromProp

        return null
    }

    /** 按优先级解析第一个包含 `libvlc.dll` 的目录；找不到返回 null。 */
    fun resolveHome(userDir: File = File(System.getProperty("user.dir"))): File? {
        bundledDir()?.let { return it }
        return candidateDirs(userDir).firstOrNull { isValidVlcDir(it) }
    }

    /**
     * 在 `Main.kt` 启动第一行调用：把找到的随包/本地运行库压入 `jna.library.path` 首位。
     * JNA 只在首次加载 native 时读该属性，晚于 vlcj 初始化再设会无效。
     */
    fun applyBundledRuntime(): File? {
        val dir = resolveHome() ?: return null
        prependJnaLibraryPath(dir)
        return dir
    }

    internal fun prependJnaLibraryPath(dir: File) {
        val existing = System.getProperty("jna.library.path").orEmpty()
        val parts = existing.split(File.pathSeparator).filter { it.isNotBlank() }
        if (dir.absolutePath !in parts) {
            System.setProperty(
                "jna.library.path",
                (listOf(dir.absolutePath) + parts).joinToString(File.pathSeparator),
            )
        }
    }

    fun found(): Boolean = resolveHome() != null

    fun candidateDirs(userDir: File = File(System.getProperty("user.dir"))): List<File> = buildList {
        System.getenv("MYMUSIC_VLC_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        System.getenv("VLC_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        // 开发态直跑：兼容仓库根目录或 desktop 子目录作为工作目录
        add(File(userDir, "desktop/build/vlc-runtime"))
        add(File(userDir, "build/vlc-runtime"))
        add(File(userDir, "desktop/build/windowsAppResources/vlc"))
        add(File(userDir, "build/windowsAppResources/vlc"))
        add(File("C:\\Program Files\\VideoLAN\\VLC"))
        add(File("C:\\Program Files (x86)\\VideoLAN\\VLC"))
        add(File(userDir, "vlc"))
    }
}
