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

/** Why a line could not be opened, in words for the user (shown as the capture end detail). */
class CaptureOpenException(message: String) : Exception(message)

private const val PRIVACY_HINT = "Windows may be blocking the microphone: allow desktop apps to use it in Settings > Privacy & security > Microphone"

/** Formats worth trying when the device lacks the requested one: its native rates first, then lower ones; mono before stereo. */
fun captureFormatCandidates(want: PcmFormat): List<PcmFormat> {
    val out = mutableListOf(want)
    if (want.bitsPerSample == 16) {
        for (rate in listOf(want.sampleRateHz, 48000, 44100, 32000, 22050, 16000, 11025, 8000))
            for (ch in listOf(1, 2)) out += PcmFormat(rate, ch, 16)
    }
    return out.distinct()
}

/** The first candidate [supported] accepts, or null. Pure, so the selection is testable with fake devices. */
fun selectCaptureFormat(want: PcmFormat, supported: (PcmFormat) -> Boolean): PcmFormat? =
    captureFormatCandidates(want).firstOrNull(supported)

/**
 * Streaming 16-bit PCM converter: downmixes to the target channel count and resamples (linear interpolation) to the
 * target rate. State carries across [convert] calls, so chunk boundaries do not click.
 */
class PcmConverter(private val src: PcmFormat, private val dst: PcmFormat) {
    private val step = src.sampleRateHz.toDouble() / dst.sampleRateHz
    private var pos = 0.0               // position of the next output sample, in input frames, relative to prev
    private var prev: ShortArray? = null // last input frame of the previous call
    val isIdentity get() = src == dst

    private fun frames(b: ByteArray): Array<ShortArray> {
        val n = b.size / (2 * src.channels)
        return Array(n) { i -> ShortArray(src.channels) { c ->
            val o = (i * src.channels + c) * 2
            ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort()
        } }
    }

    private fun mix(f: ShortArray, outCh: Int, c: Int): Double =
        if (src.channels == outCh) f[c].toDouble() else f.sum().toDouble() / f.size

    fun convert(input: ByteArray): ByteArray {
        if (isIdentity) return input
        val cur = frames(input)
        if (cur.isEmpty()) return ByteArray(0)
        val all = if (prev == null) cur else arrayOf(prev!!) + cur
        val out = java.io.ByteArrayOutputStream()
        val outCh = dst.channels
        while (pos.toInt() + 1 < all.size) {
            val i = pos.toInt(); val frac = pos - i
            for (c in 0 until outCh) {
                val a = mix(all[i], outCh, c); val b = mix(all[i + 1], outCh, c)
                val v = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767)
                out.write(v and 0xff); out.write((v shr 8) and 0xff)
            }
            pos += step
        }
        val last = all.size - 1
        pos -= last
        prev = all[last]
        return out.toByteArray()
    }
}

/** A device line in [native] format presented to the caller as [want]. */
private class ConvertedLine(private val inner: CaptureLine, native: PcmFormat, private val want: PcmFormat) : CaptureLine {
    private val conv = PcmConverter(native, want)
    private val ratio = (native.sampleRateHz.toDouble() * native.channels) / (want.sampleRateHz.toDouble() * want.channels)
    private var pending = ByteArray(0)
    private val nativeFrame = native.channels * 2
    private val wantFrame = want.channels * 2

    override fun read(buffer: ByteArray): Int {
        while (pending.isEmpty()) {
            val n = ((buffer.size * ratio).toInt() / nativeFrame * nativeFrame).coerceAtLeast(nativeFrame)
            val raw = ByteArray(n)
            val got = inner.read(raw)
            if (got <= 0) return got
            pending = conv.convert(raw.copyOf(got))
        }
        val take = minOf(buffer.size / wantFrame * wantFrame, pending.size).coerceAtLeast(wantFrame).coerceAtMost(pending.size)
        System.arraycopy(pending, 0, buffer, 0, take)
        pending = pending.copyOfRange(take, pending.size)
        return take
    }

    override fun close() = inner.close()
}

/**
 * The real microphone: a `javax.sound.sampled` TargetDataLine, signed little-endian PCM. Windows devices often offer only
 * 44.1/48 kHz (and stereo), so every capture mixer is searched for the requested format first, then for a native one,
 * which is downmixed and resampled in software to what the caller asked for.
 */
object JavaSoundLines : CaptureLines {
    private fun af(f: PcmFormat) = javax.sound.sampled.AudioFormat(f.sampleRateHz.toFloat(), f.bitsPerSample, f.channels, true, false)

    override fun open(format: PcmFormat): CaptureLine? {
        val mixers = AudioSystem.getMixerInfo().mapNotNull { runCatching { AudioSystem.getMixer(it) }.getOrNull() }
        fun infoOf(f: PcmFormat) = DataLine.Info(TargetDataLine::class.java, af(f))
        // the default mixer first (null), then every mixer in order
        val sources: List<javax.sound.sampled.Mixer?> = listOf(null) + mixers
        var sawDevice = false
        var openError: Exception? = null
        for (candidate in captureFormatCandidates(format)) {
            val info = infoOf(candidate)
            for (m in sources) {
                val ok = runCatching { if (m == null) AudioSystem.isLineSupported(info) else m.isLineSupported(info) }.getOrDefault(false)
                if (!ok) continue
                sawDevice = true
                try {
                    val line = (if (m == null) AudioSystem.getLine(info) else m.getLine(info)) as TargetDataLine
                    line.open(af(candidate))
                    line.start()
                    val raw = object : CaptureLine {
                        override fun read(buffer: ByteArray): Int = line.read(buffer, 0, buffer.size)
                        override fun close() {
                            runCatching { line.stop() }
                            runCatching { line.close() }
                        }
                    }
                    return if (candidate == format) raw else ConvertedLine(raw, candidate, format)
                } catch (e: Exception) {
                    openError = e
                }
            }
        }
        if (sawDevice) throw CaptureOpenException("a microphone exists but could not be opened (${openError?.message ?: openError?.javaClass?.simpleName}). $PRIVACY_HINT, and close other apps using it")
        throw CaptureOpenException("no audio input device was found. Plug in or enable a microphone; on Windows also check: $PRIVACY_HINT")
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
        var why: String? = null
        val line = try { lines.open(f) } catch (e: Exception) { why = e.message; null }
        if (line == null) {
            monitor.end(slot)
            return refuse(CaptureEnd(CaptureEnd.UNAVAILABLE, why ?: "no microphone for $f"))
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
