package com.mytv.mymusic.desktop.win

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * 单实例互斥 + "第二次启动时唤醒已有实例"。
 *
 * 做法（两步各有明确职责，缺一不可）：
 *  1. **互斥**：对 `%APPDATA%\myMusic\instance.lock` 取独占 [FileLock]。文件锁随进程退出由
 *     操作系统释放，不怕崩溃留下脏状态（这也是不用"PID 文件 + 探活"的原因）。
 *  2. **唤醒**：主实例在 `127.0.0.1` 上监听一个**随机端口**并把端口写进 `instance.port`，
 *     副实例读到端口后连上去发一行 `ACTIVATE`。只绑回环地址，系统防火墙不会弹窗。
 *
 * 为什么不直接给主实例发 `WM_COPYDATA`：那要主实例先把窗口类注册成可查找的，
 * 而 Compose 的窗口类名是 AWT 的 `SunAwtFrame`（不可注册消息处理），回环 socket
 * 比自定义窗口过程简单得多，也天然跨语言。
 */
class SingleInstance private constructor(
    val isPrimary: Boolean,
    private val lock: FileLock?,
    private val channel: FileChannel?,
    private val lockFile: File,
    private val portFile: File,
    private val server: ServerSocket?,
) : AutoCloseable {

    @Volatile
    private var activationHandler: (() -> Unit)? = null

    @Volatile
    private var running = true

    /** 主实例：注册"副实例请求唤醒"的回调。回调在 accept 线程上执行。 */
    fun onActivate(handler: () -> Unit) {
        activationHandler = handler
    }

    /**
     * 副实例：把自己启动这件事告诉已经在跑的实例。
     * @return 是否成功送达（端口文件缺失或连接失败时为 false）
     */
    fun signalExistingInstance(): Boolean {
        if (isPrimary) return false
        val port = readPort() ?: return false
        return try {
            Socket().use { socket ->
                socket.soTimeout = CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().write((ACTIVATE + "\n").toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        } catch (_: Exception) {
            // 端口文件是上次崩溃残留：交给用户重新启动即可，不必弹窗打扰
            false
        }
    }

    private fun readPort(): Int? = try {
        portFile.readText(StandardCharsets.UTF_8).lineSequence().firstOrNull()?.trim()?.toIntOrNull()
    } catch (_: Exception) {
        null
    }

    override fun close() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        // 只由主实例清理端口文件；副实例不能删别人的
        if (isPrimary) {
            portFile.delete()
            lockFile.delete()
        }
    }

    private fun startAcceptLoop() {
        val socket = server ?: return
        thread(name = "mymusic-single-instance", isDaemon = true) {
            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.use {
                        val request = it.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        if (request?.trim() == ACTIVATE) {
                            activationHandler?.invoke()
                        }
                    }
                } catch (_: Exception) {
                    // 单个连接异常不应终止监听
                }
            }
        }
    }

    companion object {
        private const val ACTIVATE = "ACTIVATE"
        private const val CONNECT_TIMEOUT_MS = 1500

        /**
         * 尝试成为主实例。
         *
         * @param dataDir 稳定的应用数据目录（`%APPDATA%\myMusic`）；不存在会自动创建。
         */
        fun acquire(dataDir: File): SingleInstance {
            dataDir.mkdirs()
            val lockFile = File(dataDir, "instance.lock")
            val portFile = File(dataDir, "instance.port")
            val channel = RandomAccessFile(lockFile, "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // 同一 JVM 里第二次 acquire（测试会这么做）：按副实例处理
                null
            } catch (_: Exception) {
                null
            }

            if (lock == null) {
                channel.close()
                return SingleInstance(false, null, null, lockFile, portFile, null)
            }

            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            // 端口文件先写临时文件再原子替换：避免副实例读到半行端口号
            val temp = File(dataDir, "instance.port.tmp")
            temp.writeText("${server.localPort}\n", StandardCharsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    portFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                portFile.writeText("${server.localPort}\n", StandardCharsets.UTF_8)
            }

            return SingleInstance(true, lock, channel, lockFile, portFile, server).also { it.startAcceptLoop() }
        }
    }
}
