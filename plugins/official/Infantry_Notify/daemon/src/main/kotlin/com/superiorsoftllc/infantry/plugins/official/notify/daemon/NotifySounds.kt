// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * NOTIFY-1: the sounds a notification can carry.
 *  - Built in: [BUILTIN] (`chime`, `bell`, `alarm`). They are synthesised here as 16-bit mono WAV, so the plugin ships no
 *    audio file and no third-party asset: the sounds are this code's own output, under this file's license (Apache-2.0).
 *  - The owner's own: any `.wav`, `.mp3` or `.ogg` in `<state dir>/plugin-data/Infantry_Notify/sounds/`, named by its file name.
 *  - `none` (or blank) = silent.
 */
class NotifySounds(private val dir: File) {
    data class Sound(val name: String, val type: String, val bytes: ByteArray)
    data class Listed(val name: String, val builtin: Boolean, val size: Long)

    /** Every sound by name: the built-ins first, then the folder's files in name order. */
    fun list(): List<Listed> =
        BUILTIN.map { Listed(it, true, synth(it)!!.size.toLong()) } +
            (dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }?.sortedBy { it.name.lowercase() }
                ?.map { Listed(it.name, false, it.length()) } ?: emptyList())

    /** The sound [name], or null for `none`. Throws [IllegalArgumentException] for an unknown or unsafe name. */
    fun resolve(name: String?): Sound? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n.equals(NONE, true)) return null
        require(validName(n)) { "sound must be a sound id or a plain file name (no folders): $n" }
        synth(n.lowercase())?.let { return Sound(n.lowercase(), "audio/wav", it) }
        val f = File(dir, n)
        require(f.isFile && f.extension.lowercase() in EXTENSIONS) {
            "unknown sound '$n' (built in: ${BUILTIN.joinToString(", ")}; or a .wav/.mp3/.ogg file in ${dir.path})"
        }
        return Sound(f.name, typeOf(f.extension), f.readBytes())
    }

    companion object {
        const val NONE = "none"
        val BUILTIN = listOf("chime", "bell", "alarm")
        val EXTENSIONS = setOf("wav", "mp3", "ogg")
        private const val RATE = 22_050

        fun validName(n: String): Boolean = n.isNotBlank() && n.length <= 128 && !n.contains('/') && !n.contains('\\') &&
            !n.contains("..") && !n.contains(':') && n.none { it.isISOControl() }

        fun typeOf(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "audio/wav"
        }

        private val cache = HashMap<String, ByteArray>()

        /** A built-in sound as WAV bytes, or null when [id] is not one. */
        fun synth(id: String): ByteArray? = synchronized(cache) {
            cache[id] ?: when (id) {
                // Three rising notes (C6 E6 G6), soft attack, each decaying.
                "chime" -> wav(notes(listOf(1046.5 to 0.16, 1318.5 to 0.16, 1568.0 to 0.42), decay = 6.0))
                // One A5 with a long ring and an octave partial.
                "bell" -> wav(notes(listOf(880.0 to 1.1), decay = 3.2, partial = 0.35))
                // Wake-up: three loud 1 kHz beeps.
                "alarm" -> wav(beeps(1000.0, count = 3, onSec = 0.22, offSec = 0.12))
                else -> null
            }?.also { cache[id] = it }
        }

        private fun notes(seq: List<Pair<Double, Double>>, decay: Double, partial: Double = 0.0): ShortArray {
            val out = ArrayList<Short>()
            for ((freq, sec) in seq) {
                val n = (RATE * sec).toInt()
                for (i in 0 until n) {
                    val t = i.toDouble() / RATE
                    val attack = minOf(1.0, t / 0.008)
                    val env = attack * exp(-decay * t)
                    val v = sin(2 * PI * freq * t) + partial * sin(2 * PI * 2 * freq * t)
                    out += (v / (1 + partial) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
                }
            }
            return out.toShortArray()
        }

        private fun beeps(freq: Double, count: Int, onSec: Double, offSec: Double): ShortArray {
            val out = ArrayList<Short>()
            repeat(count) {
                val on = (RATE * onSec).toInt()
                for (i in 0 until on) {
                    val t = i.toDouble() / RATE
                    val edge = minOf(1.0, t / 0.005, (onSec - t) / 0.005)
                    out += (sin(2 * PI * freq * t) * edge * 0.8 * Short.MAX_VALUE).toInt().toShort()
                }
                repeat((RATE * offSec).toInt()) { out += 0 }
            }
            return out.toShortArray()
        }

        private fun wav(samples: ShortArray): ByteArray {
            val data = samples.size * 2
            val b = ByteArrayOutputStream(44 + data)
            fun i32(v: Int) { b.write(v and 0xff); b.write(v shr 8 and 0xff); b.write(v shr 16 and 0xff); b.write(v shr 24 and 0xff) }
            fun i16(v: Int) { b.write(v and 0xff); b.write(v shr 8 and 0xff) }
            b.write("RIFF".toByteArray()); i32(36 + data); b.write("WAVE".toByteArray())
            b.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            b.write("data".toByteArray()); i32(data)
            for (s in samples) i16(s.toInt())
            return b.toByteArray()
        }
    }
}
