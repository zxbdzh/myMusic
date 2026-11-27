package com.mytv.mymusic.desktop.state.audio

import com.mytv.mymusic.desktop.state.AudioStream
import com.mytv.mymusic.desktop.state.Track
import com.mytv.mymusic.playback.core.PlaybackEngine
import com.mytv.mymusic.playback.core.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * libVLC 后端适配器（:desktop 里对 `:playback-core` 的唯一编译期耦合点）。
 *
 * 两条刻意的设计：
 *  1. **按类名反射构造** `VlcPlaybackEngine`：t3 正在重写 :playback-vlc，
 *     构造参数（缓存目录、音频设备、均衡器配置）还没定；用类名加载意味着
 *     "构造签名变了"只影响这一个文件，且退化成模拟输出而不是编译不过。
 *     接口仍按 `PlaybackEngine` 契约静态引用——方法名一变就是可见的契约破坏，不该被反射掩盖。
 *  2. **每次调用都包 runCatching**：libVLC 的 native 初始化失败是运行时问题
 *     （没装、位数不对、插件缺失），不该把整个界面带崩；失败原因写进 [AudioSnapshot.error]，
 *     由播放条显示出来。
 *
 * t3 落地后：删掉反射，直接 `VlcPlaybackEngine(...)`，其余不变。
 */
class VlcAudioOutput private constructor(
    private val engine: PlaybackEngine,
    scope: CoroutineScope,
) : AudioOutput {

    override val label: String = "libVLC"
    override val detail: String = "libVLC 3.x + vlcj 4.12.1"

    private val _snapshot = MutableStateFlow(AudioSnapshot())
    override val snapshot: StateFlow<AudioSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            // 引擎自己的事件回调已经合并进 snapshot，这里只做字段搬运
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
        private const val ENGINE_CLASS = "com.mytv.mymusic.playback.vlc.VlcPlaybackEngine"

        /** 有 libVLC 才值得实例化；否则连类都不碰，免得加载 native 时抛一堆栈。 */
        fun create(scope: CoroutineScope): VlcAudioOutput? {
            if (!VlcInstallation.found()) return null
            val engine = runCatching {
                Class.forName(ENGINE_CLASS).getDeclaredConstructor().newInstance() as? PlaybackEngine
            }.getOrNull() ?: return null
            return runCatching { VlcAudioOutput(engine, scope) }.getOrNull()
        }
    }
}

/**
 * libVLC 是否装在本机。
 *
 * 判断顺序：随包运行库（jpackage 的 app 资源目录）→ `VLC_HOME` 环境变量（随包分发时打包器会设置）
 * → 三个默认安装目录 → 工作目录下的 `vlc/`。只看 `libvlc.dll` 存在与否，不尝试 `Native.load`——
 * 那会把 JNA 的 native 状态污染到进程里，而"这一步只想知道有没有"。
 */
object VlcInstallation {
    /**
     * jpackage 会把 `appResourcesRootDir` 的内容放到 `<app>/resources`，
     * CMP 在启动时把绝对路径写进 `compose.application.resources.dir`。
     * 开发态（gradle run）没有这个属性，返回 null。
     */
    fun bundledDir(): File? {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources.isNullOrBlank()) return null
        val dir = File(resources, "vlc")
        return dir.takeIf { File(it, "libvlc.dll").isFile }
    }

    /**
     * 让 JNA 先认随包的 libvlc。
     *
     * JNA 只在第一次加载 native 时读 `jna.library.path`，所以必须在任何 vlcj 调用之前执行
     * （Main 启动第一行）。返回实际生效的目录；没随包运行库时返回 null，交给注册表/安装目录发现。
     */
    fun applyBundledRuntime(): File? {
        val dir = bundledDir() ?: return null
        val existing = System.getProperty("jna.library.path").orEmpty()
        val parts = existing.split(File.pathSeparator).filter { it.isNotBlank() }
        if (dir.absolutePath !in parts) {
            System.setProperty("jna.library.path", (listOf(dir.absolutePath) + parts).joinToString(File.pathSeparator))
        }
        return dir
    }

    fun found(): Boolean = bundledDir() != null || candidates().any { File(it).isFile }

    fun candidates(): List<String> {
        val fromEnv = System.getenv("VLC_HOME")
        val list = mutableListOf<String>()
        if (!fromEnv.isNullOrBlank()) list += File(fromEnv, "libvlc.dll").path
        list += "C:\\Program Files\\VideoLAN\\VLC\\libvlc.dll"
        list += "C:\\Program Files (x86)\\VideoLAN\\VLC\\libvlc.dll"
        // 本仓库的随包目录（t8 打包后 libvlc 会放在 app 目录旁）
        list += File(System.getProperty("user.dir"), "vlc/libvlc.dll").path
        return list
    }
}
