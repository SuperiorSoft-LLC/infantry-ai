// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.sound

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Desktop mp3 playback. The ONLY file that touches JLayer (`javazoom.jl`, LGPL): swap the decoder here and nothing else
 * changes. The mp3 is decoded to PCM synchronously (so a corrupt file returns false), scaled by the volume, and played on
 * a daemon thread through javax.sound.
 */
class JvmSoundPlayer : SoundPlayer {
    @Volatile private var line: SourceDataLine? = null

    override fun play(mp3: ByteArray, volumePercent: Int): Boolean = runCatching {
        val pcm = decode(mp3)
        val scaled = scale(pcm.bytes, volumePercent)
        val format = AudioFormat(pcm.sampleRate.toFloat(), 16, pcm.channels, true, false)
        val out = AudioSystem.getSourceDataLine(format).also { it.open(format) }
        line?.let { prev -> runCatching { prev.stop(); prev.close() } }
        line = out
        Thread({
            runCatching {
                out.start()
                out.write(scaled, 0, scaled.size)
                out.drain()
            }
            runCatching { out.close() }
        }, "incoming-comms-sound").apply { isDaemon = true }.start()
        true
    }.getOrDefault(false)

    class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    companion object {
        /** 16-bit little-endian PCM of [mp3]; throws when there is no decodable frame. */
        fun decode(mp3: ByteArray): Pcm {
            val bitstream = javazoom.jl.decoder.Bitstream(ByteArrayInputStream(mp3))
            val decoder = javazoom.jl.decoder.Decoder()
            val out = ByteArrayOutputStream()
            var rate = 0
            var channels = 0
            try {
                while (out.size() < MAX_PCM_BYTES) {
                    val header = bitstream.readFrame() ?: break
                    val buf = decoder.decodeFrame(header, bitstream) as javazoom.jl.decoder.SampleBuffer
                    rate = buf.sampleFrequency
                    channels = buf.channelCount
                    for (i in 0 until buf.bufferLength) {
                        val s = buf.buffer[i].toInt()
                        out.write(s and 0xFF)
                        out.write((s shr 8) and 0xFF)
                    }
                    bitstream.closeFrame()
                }
            } finally {
                runCatching { bitstream.close() }
            }
            require(out.size() > 0 && rate > 0 && channels > 0) { "no mp3 frames" }
            return Pcm(out.toByteArray(), rate, channels)
        }

        /** s16le samples times [volumePercent] / 100. */
        fun scale(pcm: ByteArray, volumePercent: Int): ByteArray {
            val v = volumePercent.coerceIn(0, 100)
            if (v == 100) return pcm
            val out = ByteArray(pcm.size)
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val scaled = (sample.toShort() * v / 100)
                out[i] = (scaled and 0xFF).toByte()
                out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                i += 2
            }
            return out
        }

        private const val MAX_PCM_BYTES = 40 * 1024 * 1024
    }
}

/** A [CustomSoundStore] backed by one file; every failure is a false / null, never a throw. */
class FileCustomSoundStore(private val file: File) : CustomSoundStore {
    override fun save(bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun load(): ByteArray? = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun clear() { runCatching { file.delete() } }
}
