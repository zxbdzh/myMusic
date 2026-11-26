package com.mytv.mymusic.desktop.win

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** SMTC 媒体浮层上的按钮，对应桥回传的 `button` 字段。 */
enum class MediaButton { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

/**
 * 与 SMTC 桥进程（`desktop/native/smtc-bridge`）的客户端。
 *
 * 为什么是独立进程而不是进程内 JNI 库：见 `docs/pc/SMTC-SPIKE.md` 的实测结论——
 * NativeAOT 版的 `MediaPlayer` 拿到的 SMTC 对象 `IsEnabled=True` 却不注册系统会话，
 * 而同一份代码按 JIT 发布（哪怕只有 11 MB）就能正常出现在媒体浮层里。
 *
 * 健壮性约定：
 *  · 桥文件缺失 / 启动失败 / 中途崩溃 → [state] 变成 [State.FAILED]，只影响媒体浮层，
 *    播放、托盘、热键全部照常；
 *  · 写侧加锁：状态更新来自 Compose 主线程与引擎回调，不能交错半行；
 *  · 读侧一条线程按行解析，`probe` 结果通过 [probeResponses] 交给等待方。
 */
class SmtcBridgeClient(
    private val exe: File?,
    private val onButton: (MediaButton) -> Unit,
) : AutoCloseable {

    enum class State { DISABLED, STARTING, READY, FAILED }

    @Volatile
    var state: State = State.DISABLED
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val writeLock = Any()
    private val probeResponses = ArrayBlockingQueue<JsonObject>(4)

    /** 最近一次成功写入的状态；用于启动失败后重试时补发。 */
    @Volatile
    private var lastPublished: NowPlaying? = null

    fun start(): SmtcBridgeClient {
        if (exe == null || !exe.isFile) {
            state = State.DISABLED
            lastError = "SMTC 桥未构建（desktop/native/smtc-bridge），媒体浮层不可用"
            return this
        }
        state = State.STARTING
        try {
            val builder = ProcessBuilder(exe.absolutePath)
            builder.redirectErrorStream(false)
            val proc = builder.start()
            process = proc
            writer = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)

            thread(name = "mymusic-smtc-reader", isDaemon = true) {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        handle(line)
                    }
                } catch (_: Exception) {
                    // 进程退出属于正常路径：下面统一收尾
                } finally {
                    if (state != State.DISABLED) {
                        state = State.FAILED
                        if (lastError.isEmpty()) lastError = "SMTC 桥进程已退出"
                    }
                }
            }
            // 桥的错误流单独排空，避免管道写满导致子进程阻塞
            thread(name = "mymusic-smtc-stderr", isDaemon = true) {
                runCatching {
                    proc.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                        if (line.isNotBlank()) lastError = line
                    }
                }
            }
        } catch (t: Throwable) {
            state = State.FAILED
            lastError = "SMTC 桥启动失败: ${t.message}"
        }
        return this
    }

    private fun handle(line: String) {
        val payload = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        when (payload["event"]?.jsonPrimitive?.content) {
            "ready" -> {
                state = State.READY
                // 桥启动前可能有状态更新（引擎已在播），补发一次避免浮层空白
                lastPublished?.let { publish(it) }
            }
            "error" -> {
                state = State.FAILED
                lastError = payload["message"]?.jsonPrimitive?.content ?: "未知错误"
            }
            "button" -> {
                val name = payload["button"]?.jsonPrimitive?.content?.uppercase() ?: return
                runCatching { MediaButton.valueOf(name) }.onSuccess { onButton(it) }
            }
            "probe" -> {
                // 队列满时丢最老的，避免自检期间无限堆积
                if (!probeResponses.offer(payload)) {
                    probeResponses.poll()
                    probeResponses.offer(payload)
                }
            }
        }
    }

    /** 把播放状态推给系统媒体浮层；桥没起来时静默丢弃。 */
    fun publish(now: NowPlaying) {
        lastPublished = now
        val activeWriter = writer ?: return
        val payload = buildJsonObject {
            put("op", "update")
            put("status", if (now.isPlaying) "playing" else "paused")
            put("title", now.title)
            put("artist", now.artist)
            put("album", now.album)
            put("durationMs", now.durationMs)
            put("positionMs", now.positionMs)
            put("canSeek", true)
            put("canNext", now.canNext)
            put("canPrev", now.canPrevious)
        }
        synchronized(writeLock) {
            runCatching {
                activeWriter.write(payload.toString())
                activeWriter.write("\n")
                activeWriter.flush()
            }.onFailure {
                state = State.FAILED
                lastError = "SMTC 写入失败: ${it.message}"
            }
        }
    }

    /**
     * 让桥从系统侧（GSMTCS）回读当前会话。
     *
     * 这是**验收证据**：只有 Windows 真的把我们当成媒体会话，回读才能拿到标题。
     */
    fun requestProbe(timeoutMs: Long = 2500): JsonObject? {
        val activeWriter = writer ?: return null
        probeResponses.clear()
        synchronized(writeLock) {
            runCatching {
                activeWriter.write("""{"op":"probe"}""")
                activeWriter.write("\n")
                activeWriter.flush()
            }.onFailure { return null }
        }
        return probeResponses.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        val proc = process
        process = null
        writer = null
        if (proc != null) {
            runCatching {
                proc.outputStream.write("""{"op":"quit"}""".toByteArray(StandardCharsets.UTF_8))
                proc.outputStream.write('\n'.code)
                proc.outputStream.flush()
            }
            if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                proc.destroy()
            }
        }
    }

    companion object {
        /**
         * 按开发/打包两种布局找桥 exe。
         *
         * 顺序即优先级：系统属性覆盖 > jpackage 的 app 资源目录 > 仓库构建产物 > 用户安装目录。
         */
        fun locate(): File? {
            val candidates = buildList {
                System.getProperty("mymusic.smtc.bridge")?.let { add(File(it)) }
                System.getProperty("compose.application.resources.dir")?.let {
                    add(File(it, "smtc/myMusic.SmtcBridge.exe"))
                }
                val cwd = File(System.getProperty("user.dir") ?: ".")
                add(File(cwd, "desktop/build/native/smtc/myMusic.SmtcBridge.exe"))
                add(File(cwd, "build/native/smtc/myMusic.SmtcBridge.exe"))
                add(File(cwd.parentFile ?: cwd, "desktop/build/native/smtc/myMusic.SmtcBridge.exe"))
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    add(File(localAppData, "myMusic/smtc/myMusic.SmtcBridge.exe"))
                }
            }
            return candidates.firstOrNull { it.isFile }
        }
    }
}
