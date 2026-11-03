// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * STATS-1 (contract 1.92.0): builds [SessionStats] for one session from its harness's OWN local record.
 *
 * - `claude`:   `<home>/.claude/projects/<any>/<providerSessionId>.jsonl`
 * - `pi`:       `<home>/.pi/agent/sessions/<any>/<timestamp>_<providerSessionId>.jsonl`
 * - `omp`:      Oh My Pi, a Pi fork: the same layout and line shape under `<home>/.omp/agent/` (BUG-STATS-1)
 * - `opencode`: `<home>/.local/share/opencode/opencode.db` (sqlite, opened READ-ONLY with a busy timeout)
 *
 * Read only: no harness file is ever written, no credential file is ever opened, nothing goes on the network.
 * Files are streamed line by line (real Claude sessions run to tens of MB). The resolved path is cached per
 * session, so a harness's session directories are scanned once, not on every request. Any failure (missing file,
 * locked db, unexpected shape) yields the identity-only stats with every harness field null; it never throws.
 *
 * Context window: only from a LOCAL catalog that states it (Pi's `models-store.json`, OpenCode's cached
 * `models.json`). Claude Code's record states none, so its limit is always null: never guessed from a model name.
 */
class SessionStatsService(private val home: File = File(System.getProperty("user.home") ?: ".")) {

    /** How many times a session directory was scanned to resolve a path. Exposed for the cache test. */
    @Volatile internal var resolveScans: Int = 0
        private set

    /** EXPERIMENTS-1: functions that tag a session from one parsed record line (generic; experiments register here). */
    private val taggers = java.util.concurrent.CopyOnWriteArrayList<(JsonObject) -> String?>()

    fun addTagger(tagger: (JsonObject) -> String?) { taggers += tagger }

    /** HARNESS H5d: a plugin harness's stats source by harness id (the daemon points this at its registry); null = none. */
    @Volatile var pluginStats: (harnessId: String) -> PluginStatsSource? = { null }

    /** Harnesses that keep a pi-layout session store (`<home>/<root>/agent/sessions`): harness id -> store root. Data, extended with [registerPiStore]. */
    private val piStoreRoots = ConcurrentHashMap<String, String>(mapOf(PI to PI_ROOT, OMP to OMP_ROOT))

    fun registerPiStore(harnessId: String, root: String) { piStoreRoots[harnessId] = root }

    private val paths = ConcurrentHashMap<String, File>()
    private val limitCache = ConcurrentHashMap<String, Pair<Long, Long?>>()

    fun read(sessionId: String, harness: String, providerSessionId: String?, status: String?): SessionStats {
        val base = SessionStats(sessionId = sessionId, harness = harness, providerSessionId = providerSessionId, status = status)
        val psid = providerSessionId?.takeIf { SAFE_ID.matches(it) && ".." !in it } ?: return base
        return try {
            when (harness) {
                // A harness id that is an OpenCode variant (`opencode-go`, ...) shares OpenCode's record (H8b-3: read by the opencode module).
                else -> if (piStoreRoots.containsKey(harness)) piStoreRoots.getValue(harness).let { root -> resolve(harness, psid) { piFile(root, psid) }?.let { readPi(root, it, base) } }
                else (pluginStats(harness) ?: if (harness.startsWith("$OPENCODE-")) pluginStats(OPENCODE) else null)?.read(psid)?.let { r -> r.events?.let { base.withPluginEvents(it, ::anyCatalogLimit) } ?: base.withPlugin(r) }
            } ?: base
        } catch (e: Exception) {
            base
        }
    }

    private fun resolve(harness: String, psid: String, scan: () -> File?): File? {
        val key = "$harness:$psid"
        paths[key]?.let { if (it.isFile) return it else paths.remove(key) }
        resolveScans++
        return scan()?.also { paths[key] = it }
    }

    // H7b-3: the Claude Code session-store reader moved to the Infantry_Claude plugin (HarnessStats); [textLength] stays for Pi.

    private fun textLength(content: JsonElement?): Int = when (content) {
        is JsonPrimitive -> content.content.length
        is JsonArray -> content.sumOf { (it as? JsonObject)?.str("text")?.length ?: 0 }
        else -> 0
    }

    // ------------------------------------------------------------------ Pi

    /** [root] is the harness's home dir: `.pi` for Pi, `.omp` for Oh My Pi (same layout and line shape). */
    private fun piFile(root: String, psid: String): File? =
        File(home, "$root/agent/sessions").listFiles { f -> f.isDirectory }
            ?.asSequence()
            ?.flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith("_$psid.jsonl") }.orEmpty().asSequence() }
            ?.firstOrNull()

    private fun readPi(root: String, file: File, base: SessionStats): SessionStats {
        val acc = StatsAccumulator()
        val tags = LinkedHashSet<String>()
        var provider: String? = null
        var model: String? = null
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = parse(line) ?: continue
                val lineAt = isoMillis(o.str("timestamp"))
                acc.seen(lineAt)
                when (o.str("type")) {
                    "model_change" -> { provider = o.str("provider") ?: provider; model = o.str("modelId") ?: model }
                    "message" -> {
                        val m = o["message"] as? JsonObject ?: continue
                        if (taggers.isNotEmpty() && m.str("role") == "system") taggers.forEach { t -> t(o)?.let { tags += it } }
                        when (m.str("role")) {
                            "user" -> acc.prompt()
                            "toolResult" -> acc.toolResult(textLength(m["content"]), m.longOrNull("timestamp") ?: lineAt)
                            "assistant" -> {
                                provider = m.str("provider") ?: provider
                                acc.assistantAt(m.longOrNull("timestamp") ?: lineAt)
                                model = m.str("model") ?: model
                                acc.turn(m.str("model"))
                                val u = m["usage"] as? JsonObject
                                if (u != null) {
                                    acc.request(UsageAdapters.pi(u, m.longOrNull("timestamp") ?: lineAt, m.str("model")))
                                }
                                (m["content"] as? JsonArray)?.forEach { block ->
                                    val b = block as? JsonObject ?: return@forEach
                                    if (b.str("type") == "toolCall") acc.tool(b.str("name") ?: "unknown", m.longOrNull("timestamp") ?: lineAt)
                                }
                            }
                        }
                    }
                }
            }
        }
        val limit = catalogLimit(File(home, "$root/agent/models-store.json"), provider, model) { root, p, id ->
            ((root[p] as? JsonObject)?.get("models") as? JsonArray)
                ?.firstOrNull { (it as? JsonObject)?.str("id") == id }
                ?.let { (it as JsonObject).longOrNull("contextWindow") }
        }
        return acc.build(base, limit ?: anyCatalogLimit(model)).let { if (tags.isEmpty()) it else it.copy(tags = tags.toList()) }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * STATS-2: the window of [model] by id alone, from the local Pi then OpenCode catalogs, whichever provider lists it.
     * Lets Claude (whose record states none) have the same limit line as the others; null when no catalog lists the
     * model, never guessed from its name.
     */
    private fun anyCatalogLimit(model: String?): Long? {
        if (model.isNullOrBlank()) return null
        val key = "any|$model"
        val files = listOf(File(home, "$PI_ROOT/agent/models-store.json"), File(home, ".cache/opencode/models.json"))
        val stamp = files.sumOf { if (it.isFile) it.lastModified() else 0L }
        limitCache[key]?.let { (cachedStamp, value) -> if (cachedStamp == stamp) return value }
        val value = files.firstNotNullOfOrNull { f ->
            try {
                if (!f.isFile) null else (f.bufferedReader().use { JSON.parseToJsonElement(it.readText()) } as? JsonObject)?.values?.firstNotNullOfOrNull { prov ->
                    when (val models = (prov as? JsonObject)?.get("models")) {
                        is JsonArray -> models.firstOrNull { (it as? JsonObject)?.str("id") == model }
                            ?.let { (it as JsonObject).longOrNull("contextWindow") }
                        is JsonObject -> ((models[model] as? JsonObject)?.get("limit") as? JsonObject)?.longOrNull("context")
                        else -> null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        limitCache[key] = stamp to value
        return value
    }

    /** A context window from a local catalog file, cached per (file, provider, model) until the file changes. */
    private fun catalogLimit(file: File, provider: String?, model: String?, lookup: (JsonObject, String, String) -> Long?): Long? {
        if (provider == null || model == null || !file.isFile) return null
        val key = "${file.path}|$provider|$model"
        val stamp = file.lastModified()
        limitCache[key]?.let { (cachedStamp, value) -> if (cachedStamp == stamp) return value }
        val value = try {
            (file.bufferedReader().use { JSON.parseToJsonElement(it.readText()) } as? JsonObject)?.let { lookup(it, provider, model) }
        } catch (e: Exception) {
            null
        }
        limitCache[key] = stamp to value
        return value
    }

    private fun parse(text: String?): JsonObject? = try {
        text?.let { JSON.parseToJsonElement(it) as? JsonObject }
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.long(key: String): Long = longOrNull(key) ?: 0L

    private fun isoMillis(text: String?): Long? = try {
        text?.let { Instant.parse(it).toEpochMilli() }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val CLAUDE = "claude"
        const val PI = "pi"
        const val OPENCODE = "opencode"
        const val OMP = "omp"

        private const val PI_ROOT = ".pi"
        private const val OMP_ROOT = ".omp"

        private const val SQLITE_OPEN_READONLY = 0x00000001
        private const val BUSY_TIMEOUT_MS = 2_000
        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,200}$")
        private val TIMESTAMP = Regex("\"timestamp\":\"([^\"]+)\"")
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
