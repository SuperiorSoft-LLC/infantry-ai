// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.ConfigManifestSource
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 2): compiles the merged [ConfigTree] into ONE canonical JSON (every
 * key sorted, defaults written out, so the same declarations always give the same text) and hashes it. The daemon keeps
 * the row per host; the dialog reads it back with [parse].
 */
object ConfigManifests {
    private val encoder = Json { encodeDefaults = true }
    private val decoder = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(ModuleDeclaration.serializer())

    /** [tree]'s accepted declarations as canonical JSON text. */
    fun canonical(declarations: List<ModuleDeclaration>): String = sorted(encoder.encodeToJsonElement(list, declarations)).toString()

    fun compile(tree: ConfigTree, host: String, sources: List<ConfigManifestSource>, now: Long = 0): ConfigManifest {
        val json = canonical(tree.declarations)
        return ConfigManifest(host, sha256Hex(json), json, sources, now)
    }

    /** The declarations in [manifest], or null when its JSON does not parse (the caller keeps the local tree). */
    fun parse(manifest: ConfigManifest): List<ModuleDeclaration>? = runCatching { decoder.decodeFromString(list, manifest.json) }.getOrNull()

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map(::sorted))
        else -> e
    }
}

private val primes: List<Int> = generateSequence(2) { it + 1 }.filter { n -> (2 until n).none { n % it == 0 } }.take(64).toList()

/** The first 32 bits of the fractional part of [x]. */
private fun frac32(x: Double): Int = ((x - floor(x)) * 4294967296.0).toLong().toInt()

/** SHA-256's round constants and start state: the fractional parts of the cube and square roots of the first primes. */
private val K: IntArray = IntArray(64) { frac32(primes[it].toDouble().pow(1.0 / 3.0)) }
private val H0: IntArray = IntArray(8) { frac32(sqrt(primes[it].toDouble())) }

/** SHA-256 of [text] (UTF-8) as 64 lowercase hex digits; common code has no digest, and wasm needs one too. */
fun sha256Hex(text: String): String {
    val msg = text.encodeToByteArray()
    val padded = msg.copyOf(((msg.size + 9 + 63) / 64) * 64)
    padded[msg.size] = 0x80.toByte()
    val bits = msg.size.toLong() * 8
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
    val h = H0.copyOf()
    val w = IntArray(64)
    for (chunk in 0 until padded.size / 64) {
        for (t in 0 until 16) {
            val o = chunk * 64 + t * 4
            w[t] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotr(7) xor w[t - 15].rotr(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotr(17) xor w[t - 2].rotr(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val t1 = hh + (e.rotr(6) xor e.rotr(11) xor e.rotr(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotr(2) xor a.rotr(13) xor a.rotr(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))
