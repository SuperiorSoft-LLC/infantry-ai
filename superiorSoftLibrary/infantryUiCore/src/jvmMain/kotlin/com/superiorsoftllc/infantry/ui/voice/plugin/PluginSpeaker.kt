package com.superiorsoftllc.infantry.ui.voice.plugin

import com.superiorsoftllc.infantry.ui.voice.Speaker
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Plays PCM the plugin produced; replaceable so tests need no sound card. Throws when it cannot play. */
fun interface PcmPlayer {
    fun play(pcm: ByteArray, output: VoicePluginOutput, volumePercent: Int, cancelled: () -> Boolean)
}

/** The JDK's own audio (javax.sound): no player process, no dependency. Volume is applied to the samples. */
object JavaSoundPlayer : PcmPlayer {
    override fun play(pcm: ByteArray, output: VoicePluginOutput, volumePercent: Int, cancelled: () -> Boolean) {
        val format = AudioFormat(output.sampleRate.toFloat(), 16, output.channels, true, false)
        val scaled = scale(pcm, volumePercent)
        val line = AudioSystem.getSourceDataLine(format)
        try {
            line.open(format)
            line.start()
            var off = 0
            val chunk = format.frameSize * (output.sampleRate / 20).coerceAtLeast(1)
            while (off < scaled.size && !cancelled()) {
                val n = minOf(chunk, scaled.size - off)
                line.write(scaled, off, n)
                off += n
            }
            if (cancelled()) line.flush() else line.drain()
        } finally {
            line.close()
        }
    }

    /** s16le samples times [volumePercent] / 100. */
    fun scale(pcm: ByteArray, volumePercent: Int): ByteArray {
        val v = volumePercent.coerceIn(0, 100)
        if (v == 100) return pcm
        val out = pcm.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val s = ((out[i].toInt() and 0xff) or (out[i + 1].toInt() shl 8)) * v / 100
            out[i] = s.toByte(); out[i + 1] = (s shr 8).toByte()
            i += 2
        }
        return out
    }
}

/**
 * VOICE-PLUGIN-1: speaks through the enabled voice plugin, else through [fallback] (the OS voice). The plugin runs as a
 * separate process per alert ([synthesize]): the text on stdin, PCM back on stdout, killed at the manifest's timeout.
 * Any failure (not installed, bad manifest or checksum, start error, timeout, non-zero exit, no audio, no sound device)
 * speaks that alert with [fallback] instead. Nothing runs between alerts: the worker thread ends when idle too.
 */
class PluginSpeaker(
    private val store: VoicePluginStore,
    private val fallback: Speaker,
    private val player: PcmPlayer = JavaSoundPlayer,
    private val log: (String) -> Unit = { System.err.println("[voice-plugin] $it") },
) : Speaker {
    private val worker = ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "voice-plugin").apply { isDaemon = true }
    }
    @Volatile private var current: Process? = null
    @Volatile private var generation = 0

    override fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean {
        val id = store.enabledId() ?: return fallback.speak(text, volumePercent, voiceName, interrupt)
        val clean = text.replace(Regex("[\r\n]+"), " ").trim()
        if (clean.isEmpty()) return false
        if (interrupt) {
            generation++
            worker.queue.clear()
            current?.destroyForcibly()
        }
        val mine = generation
        worker.execute {
            if (mine != generation) return@execute
            val cancelled = { mine != generation }
            var why = "unusable"
            val plugin = store.ready(id) { why = it }
            val pcm = if (plugin == null) null else synthesize(plugin, clean, cancelled) { why = it }
            if (cancelled()) return@execute
            if (plugin == null || pcm == null) {
                log("$id: $why; using the system voice")
                fallback.speak(clean, volumePercent, voiceName, interrupt = false)
                return@execute
            }
            try {
                player.play(pcm, plugin.manifest.output, volumePercent, cancelled)
            } catch (e: Exception) {
                log("$id: cannot play audio (${e.message ?: e::class.simpleName}); using the system voice")
                fallback.speak(clean, volumePercent, voiceName, interrupt = false)
            }
        }
        return true
    }

    override fun beep() = fallback.beep()

    override fun voices(): List<String> = fallback.voices()

    /** Waits until no alert is queued or speaking (tests). */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (worker.activeCount == 0 && worker.queue.isEmpty()) return true
            Thread.sleep(20)
        }
        return false
    }

    /**
     * Runs [plugin] once for [text]: the PCM it wrote, or null ([why] says so) when it could not start, ran past its
     * timeout (then it is killed), exited non-zero, wrote nothing or wrote more than [MAX_PCM].
     */
    internal fun synthesize(plugin: InstalledVoicePlugin, text: String, cancelled: () -> Boolean, why: (String) -> Unit): ByteArray? {
        val process = try {
            ProcessBuilder(plugin.argv()).directory(plugin.dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            why("could not start ${plugin.argv().first()}: ${e.message}"); return null
        }
        current = process
        try {
            val out = ByteArrayOutputStream()
            var overflow = false
            val reader = Thread {
                runCatching {
                    process.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > MAX_PCM) { overflow = true; break }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            runCatching { process.outputStream.use { it.write((text + "\n").toByteArray(Charsets.UTF_8)) } }
            if (!process.waitFor(plugin.manifest.timeoutMs, TimeUnit.MILLISECONDS)) {
                kill(process)
                why("timed out after ${plugin.manifest.timeoutMs} ms (killed)"); return null
            }
            reader.join(2_000)
            if (cancelled()) return null
            val exit = process.exitValue()
            return when {
                overflow -> { why("wrote more than $MAX_PCM bytes of audio"); null }
                exit != 0 -> { why("exited $exit"); null }
                out.size() == 0 -> { why("produced no audio"); null }
                else -> out.toByteArray()
            }
        } finally {
            if (process.isAlive) kill(process)
            current = null
        }
    }

    private fun kill(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
    }

    companion object {
        /** 16 MiB: about 8 minutes of 16 kHz mono, far past any short alert. */
        const val MAX_PCM = 16 * 1024 * 1024

        /** The desktop speaker: plugins from [pluginsDir] when one is installed and enabled, else the OS voice. */
        fun desktop(pluginsDir: File, osVoice: Speaker): PluginSpeaker = PluginSpeaker(VoicePluginStore(pluginsDir), osVoice)
    }
}
