package com.mytv.mymusic.desktop.win

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自检/演示用的命令目标：播一段现场合成的音阶，并记录收到的命令。
 *
 * **它不是产品播放器**——产品播放由 `:playback-vlc`（音频组）经 `MusicStore` 提供。
 * 这里存在的唯一理由是：托盘、全局热键、任务栏按钮、SMTC 的验收必须有一个
 * **真实可听、真实可控**的对象，而验收机不一定装了 VLC（本机就没有）。
 *
 * 实现刻意很小：不读网络流、不解码，只播 8 秒内存里合成的琶音并循环；
 * 命令语义与 [MediaCommand] 一一对应，方便在自检里断言"某个入口真的把命令送到了"。
 */
class DiagnosticTarget : MediaControlTarget, AutoCloseable {

    private val commands = AtomicInteger(0)

    @Volatile
    var isPlaying: Boolean = false
        private set

    private var clip: Clip? = null

    /** 收到的命令数；自检用它在"入口 → 命令"之间做前后对比。 */
    val commandCount: Int
        get() = commands.get()

    override fun onCommand(command: MediaCommand) {
        commands.incrementAndGet()
        when (command) {
            MediaCommand.TOGGLE_PLAY_PAUSE -> if (isPlaying) stopAudio() else startAudio()
            MediaCommand.PLAY -> startAudio()
            MediaCommand.PAUSE -> stopAudio()
            MediaCommand.STOP -> {
                stopAudio()
                runCatching { clip?.microsecondPosition = 0 }
            }
            // 自检只有一首循环音阶，"切歌"没有可切的对象；只记录命令
            MediaCommand.NEXT, MediaCommand.PREVIOUS -> Unit
        }
    }

    @Synchronized
    private fun startAudio() {
        if (isPlaying) return
        try {
            val active = clip ?: openClip().also { clip = it }
            active.start()
            isPlaying = true
        } catch (t: Throwable) {
            // 无声卡 / 设备独占：自检仍然要跑完，只是"可听"这一项降级
            System.err.println("myMusic 自检: 音频设备不可用（${t.message}）")
            isPlaying = false
        }
    }

    @Synchronized
    private fun stopAudio() {
        clip?.stop()
        isPlaying = false
    }

    private fun openClip(): Clip {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val pcm = synthesizeArpeggio()
        val audio = AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / 2).toLong())
        val newClip = AudioSystem.getClip()
        newClip.open(audio)
        runCatching {
            val gain = newClip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            gain.value = -12f.coerceIn(gain.minimum, gain.maximum)
        }
        newClip.loop(Clip.LOOP_CONTINUOUSLY)
        return newClip
    }

    override fun close() {
        runCatching { clip?.stop() }
        runCatching { clip?.close() }
        clip = null
        isPlaying = false
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val LOOP_SECONDS = 8

        /** A 大调琶音：A4 C#5 E5 A5，每音 0.5 秒，带指数衰减包络。 */
        private val NOTES = doubleArrayOf(440.0, 554.37, 659.25, 880.0)
        private const val NOTE_SECONDS = 0.5

        fun synthesizeArpeggio(): ByteArray {
            val total = SAMPLE_RATE * LOOP_SECONDS
            val noteSamples = (SAMPLE_RATE * NOTE_SECONDS).toInt()
            val bytes = ByteArray(total * 2)
            for (index in 0 until total) {
                val frequency = NOTES[(index / noteSamples) % NOTES.size]
                val within = index % noteSamples
                val envelope = Math.exp(-3.0 * within / noteSamples)
                val value = Math.sin(2.0 * Math.PI * frequency * index / SAMPLE_RATE) * envelope * 0.25
                val sample = (value * Short.MAX_VALUE).toInt()
                // 小端 16 bit PCM
                bytes[index * 2] = (sample and 0xFF).toByte()
                bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
