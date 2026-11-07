// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import com.superiorsoftllc.infantry.plugin.api.AudioCapture
import com.superiorsoftllc.infantry.plugin.api.CaptureEnd
import com.superiorsoftllc.infantry.plugin.api.CaptureHandle
import com.superiorsoftllc.infantry.plugin.api.CaptureOptions
import com.superiorsoftllc.infantry.plugin.api.PcmFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** VOICE-SEAM-1: one open input line. [read] blocks; a count of zero or less means the line closed. [close] is safe to call twice. */
interface CaptureLine {
    fun read(buffer: ByteArray): Int
    fun close()
}

/** Opens an input line in a format; null = no microphone, or the format is not supported. */
fun interface CaptureLines {
    fun open(format: PcmFormat): CaptureLine?
}

/** The real microphone: a `javax.sound.sampled` TargetDataLine, signed little-endian PCM. */
object JavaSoundLines : CaptureLines {
    override fun open(format: PcmFormat): CaptureLine? {
        val af = javax.sound.sampled.AudioFormat(format.sampleRateHz.toFloat(), format.bitsPerSample, format.channels, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, af)
        if (!AudioSystem.isLineSupported(info)) return null
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(af)
        line.start()
        return object : CaptureLine {
            override fun read(buffer: ByteArray): Int = line.read(buffer, 0, buffer.size)
            override fun close() {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }
    }
}

/**
 * VOICE-SEAM-1: the `audio.in` service for one module. It takes the host's one capture slot ([monitor], which drives the
 * listening indicator), reads the line on its own daemon thread in chunks of a tenth of a second, and ends at
 * [CaptureHandle.stop], at the time limit ([limitSeconds]: the request capped by the host setting, counted in audio
 * bytes, so it is exact) or when the line fails. onEnd runs exactly once, after the last chunk, with the slot already free.
 */
class JvmAudioCapture(
    private val module: String,
    private val monitor: CaptureMonitor,
    private val limitSeconds: (requested: Int?) -> Int,
    private val lines: CaptureLines = JavaSoundLines,
    private val log: (String) -> Unit = {},
) : AudioCapture {
    private class Handle : CaptureHandle {
        @Volatile override var active = true
        @Volatile var stopRequested = false
        @Volatile var line: CaptureLine? = null

        override fun stop() {
            stopRequested = true
            line?.close()
        }
    }

    override fun start(options: CaptureOptions, onChunk: (ByteArray) -> Unit, onEnd: (CaptureEnd) -> Unit): CaptureHandle {
        val handle = Handle()
        fun refuse(end: CaptureEnd): CaptureHandle {
            handle.active = false
            log("[plugins] $module: capture not started: ${end.reason}${end.detail?.let { " ($it)" } ?: ""}")
            runCatching { onEnd(end) }
            return handle
        }
        val f = options.format
        val frame = f.channels * (f.bitsPerSample / 8)
        if (f.sampleRateHz <= 0 || frame <= 0 || f.bitsPerSample % 8 != 0) return refuse(CaptureEnd(CaptureEnd.UNAVAILABLE, "unsupported format $f"))
        val slot = monitor.begin(module, handle::stop) ?: return refuse(CaptureEnd(CaptureEnd.BUSY, "another capture is running"))
        val line = runCatching { lines.open(f) }.getOrNull()
        if (line == null) {
            monitor.end(slot)
            return refuse(CaptureEnd(CaptureEnd.UNAVAILABLE, "no microphone for $f"))
        }
        handle.line = line
        val bytesPerSecond = f.sampleRateHz.toLong() * frame
        val limitBytes = limitSeconds(options.maxSeconds).toLong() * bytesPerSecond
        val chunk = (bytesPerSecond / CHUNKS_PER_SECOND / frame * frame).toInt().coerceAtLeast(frame)
        Thread({
            var end = CaptureEnd(CaptureEnd.STOPPED)
            var total = 0L
            val buffer = ByteArray(chunk)
            try {
                while (!handle.stopRequested) {
                    val n = line.read(buffer)
                    if (handle.stopRequested) break
                    if (n <= 0) {
                        end = CaptureEnd(CaptureEnd.FAILED, "the input line closed")
                        break
                    }
                    val take = minOf(n.toLong(), limitBytes - total).toInt()
                    if (take > 0) onChunk(buffer.copyOf(take))
                    total += take
                    if (total >= limitBytes) {
                        end = CaptureEnd(CaptureEnd.LIMIT)
                        break
                    }
                }
            } catch (e: Exception) {
                end = CaptureEnd(CaptureEnd.FAILED, e.message ?: e::class.simpleName)
            } finally {
                line.close()
                handle.active = false
                monitor.end(slot)
                runCatching { onEnd(end) }.onFailure { log("[plugins] $module: capture onEnd failed: ${it.message}") }
            }
        }, "audio-in-$module").apply { isDaemon = true }.start()
        return handle
    }

    private companion object {
        /** Chunk size for latency (a tenth of a second), not a limit. */
        const val CHUNKS_PER_SECOND = 10
    }
}
