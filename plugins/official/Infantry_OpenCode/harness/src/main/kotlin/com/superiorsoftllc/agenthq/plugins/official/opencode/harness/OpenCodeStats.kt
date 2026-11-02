package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessStatsRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * HARNESS H8b-3: reads one opencode session from its own store, `<home>/.local/share/opencode/opencode.db`, through the daemon's read-only query
 * ([HarnessHost.queryDb]; the plugin carries no database driver). Moved from the core `SessionStatsService.readOpenCode`. A step's context is
 * input + cache read + cache write, billed adds output (reasoning is never added), cost is only what opencode reported. Null when there is no db
 * or no such session.
 */
object OpenCodeStats {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(host: HarnessHost, home: File, psid: String): HarnessStatsRow? {
        val db = File(home, ".local/share/opencode/opencode.db")
        if (!db.isFile) return null
        fun q(sql: String) = host.queryDb(db, sql, listOf(psid)) ?: throw java.sql.SQLException("opencode.db unreadable")
        var first: Long? = null
        var last: Long? = null
        fun seen(at: Long) { if (first == null || at < first!!) first = at; if (last == null || at > last!!) last = at }
        val head = q("SELECT time_created AS a, time_updated AS b FROM session WHERE id = ?").firstOrNull() ?: return null
        seen((head["a"] as Number).toLong()); seen((head["b"] as Number).toLong())
        var prompts = 0
        var turns = 0
        val models = LinkedHashSet<String>()
        for (row in q("SELECT data FROM message WHERE session_id = ? ORDER BY time_created, id")) {
            val m = parse(row["data"] as? String) ?: continue
            when (m.str("role")) {
                "user" -> prompts++
                "assistant" -> { turns++; m.str("modelID")?.takeIf { it.isNotBlank() }?.let { models += it } }
            }
        }
        var tools = 0
        var billed = 0L
        var lastContext: Long? = null
        var cost = 0.0
        var anyCost = false
        for (row in q("SELECT time_created AS at, data FROM part WHERE session_id = ? ORDER BY time_created, id")) {
            val p = parse(row["data"] as? String) ?: continue
            when (p.str("type")) {
                "tool" -> tools++
                "step-finish" -> {
                    val t = p["tokens"] as? JsonObject ?: continue
                    val cache = t["cache"] as? JsonObject
                    val context = t.long("input") + (cache?.long("read") ?: 0L) + (cache?.long("write") ?: 0L)
                    val total = context + t.long("output")
                    if (total == 0L) continue // an all-zero request is not a request
                    billed += total
                    lastContext = context
                    (p["cost"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { cost += it; anyCost = true }
                }
            }
        }
        return HarnessStatsRow(
            startedAt = first, endedAt = last, userPrompts = prompts, assistantTurns = turns, toolUses = tools,
            models = models.toList(), lastContextTokens = lastContext, billedTokens = billed, costTotal = if (anyCost) cost else null,
        )
    }

    private fun parse(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
}
