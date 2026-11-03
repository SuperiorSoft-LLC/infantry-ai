// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace.daemon

import com.superiorsoftllc.infantry.daemon.api.usage.MeterReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * PACE-TREND-1: the pace history, one JSON line per sample in the plugin's own folder (`<stateDir>/samples.jsonl`). That file
 * is the only copy: whatever export / sync carries plugin data carries it. Held in memory per (provider, window) after load.
 * Thread-safe (the recorder, a frame and a tool may overlap).
 */
class PaceStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val series = LinkedHashMap<Pair<String, String>, MutableList<PaceSample>>()

    init { load() }

    private fun load() = synchronized(lock) {
        if (!file.isFile) return
        file.forEachLine { line ->
            val s = decode(line) ?: return@forEachLine
            series.getOrPut(s.provider to s.window) { mutableListOf() } += s
        }
        series.values.forEach { l -> l.sortBy { it.observedAt } }
    }

    /**
     * Appends [r] unless it is unreadable (no percent) or the same reading as the newest sample of its series (same
     * observedAt, percent and reset time: the daemon's cached live read has not refreshed). Returns whether it was kept.
     */
    fun record(r: MeterReading, tokens: List<SessionTokens> = emptyList()): Boolean = synchronized(lock) {
        val percent = r.percent ?: return false
        val list = series.getOrPut(r.provider to r.window) { mutableListOf() }
        val last = list.lastOrNull()
        val s = PaceSample(r.provider, r.window, r.observedAt, percent, r.paceDelta, r.projectedPercent, r.resetsAt, r.windowMinutes,
            last?.percent, last?.let { percent - it.percent }, tokens)
        if (last != null && last.observedAt == s.observedAt && last.percent == s.percent && last.resetsAt == s.resetsAt) return false
        if (last != null && s.observedAt < last.observedAt) return false
        list += s
        file.parentFile?.mkdirs()
        file.appendText(encode(s) + "\n")
        true
    }

    /** Drops samples older than [retentionDays] (0 = keep all) and rewrites the file when something went. Returns how many. */
    fun prune(retentionDays: Int): Int = synchronized(lock) {
        if (retentionDays <= 0) return 0
        val cutoff = clock() - retentionDays * 24L * 3_600_000L
        var dropped = 0
        for (list in series.values) {
            val before = list.size
            list.removeAll { it.observedAt < cutoff }
            dropped += before - list.size
        }
        series.entries.removeAll { it.value.isEmpty() }
        if (dropped > 0) rewrite()
        dropped
    }

    private fun rewrite() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w -> series.values.flatten().sortedBy { it.observedAt }.forEach { w.write(encode(it)); w.write("\n") } }
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Every (provider, window) with samples. */
    fun keys(): List<Pair<String, String>> = synchronized(lock) { series.keys.toList() }

    /** The samples of one series since [sinceMs] (inclusive), oldest first. */
    fun samples(provider: String, window: String, sinceMs: Long = 0): List<PaceSample> = synchronized(lock) {
        series[provider to window].orEmpty().filter { it.observedAt >= sinceMs }
    }

    fun count(): Int = synchronized(lock) { series.values.sumOf { it.size } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(s: PaceSample): String = buildJsonObject {
            put("p", s.provider); put("w", s.window); put("t", s.observedAt); put("u", s.percent)
            put("d", s.paceDelta?.let { JsonPrimitive(it) } ?: JsonNull)
            put("j", s.projectedPercent?.let { JsonPrimitive(it) } ?: JsonNull)
            put("r", s.resetsAt?.let { JsonPrimitive(it) } ?: JsonNull)
            put("m", s.windowMinutes)
            s.prevPercent?.let { put("pp", it) }; s.deltaPercent?.let { put("dp", it) }
            if (s.tokens.isNotEmpty()) put("k", kotlinx.serialization.json.JsonArray(s.tokens.map { t ->
                buildJsonObject {
                    put("s", t.sessionId); put("r", t.role)
                    t.input?.let { put("i", it) }; t.output?.let { put("o", it) }; t.cacheRead?.let { put("cr", it) }; t.cacheWrite?.let { put("cw", it) }
                }
            }))
        }.toString()

        fun decode(line: String): PaceSample? = try {
            val o: JsonObject = json.parseToJsonElement(line.trim().ifEmpty { return null }).jsonObject
            PaceSample(
                provider = o["p"]!!.jsonPrimitive.content, window = o["w"]!!.jsonPrimitive.content,
                observedAt = o["t"]!!.jsonPrimitive.longOrNull!!, percent = o["u"]!!.jsonPrimitive.doubleOrNull!!,
                paceDelta = o["d"]?.jsonPrimitive?.doubleOrNull, projectedPercent = o["j"]?.jsonPrimitive?.doubleOrNull,
                resetsAt = o["r"]?.jsonPrimitive?.longOrNull, windowMinutes = o["m"]?.jsonPrimitive?.intOrNull ?: 0,
                prevPercent = o["pp"]?.jsonPrimitive?.doubleOrNull, deltaPercent = o["dp"]?.jsonPrimitive?.doubleOrNull,
                tokens = (o["k"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { e ->
                    val t = e.jsonObject
                    SessionTokens(t["s"]!!.jsonPrimitive.content, t["r"]?.jsonPrimitive?.content.orEmpty(), t["i"]?.jsonPrimitive?.longOrNull,
                        t["o"]?.jsonPrimitive?.longOrNull, t["cr"]?.jsonPrimitive?.longOrNull, t["cw"]?.jsonPrimitive?.longOrNull)
                },
            )
        } catch (e: Exception) {
            null // a torn or foreign line is skipped, never fatal
        }
    }
}
