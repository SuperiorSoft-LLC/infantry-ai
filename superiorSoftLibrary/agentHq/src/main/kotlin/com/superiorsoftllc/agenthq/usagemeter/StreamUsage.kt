package com.superiorsoftllc.agenthq.usagemeter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What one run reported, summed. [contextSum] / [turns] is the average context per turn. */
data class RunTotals(
    val turns: Long,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val contextSum: Long,
    val cost: Double?,
    val model: String?,
)

/**
 * Folds one run's stdout lines into [RunTotals]. Provider-reported usage only, no model calls, nothing estimated.
 *
 * - Claude `stream-json`: each `assistant` line (deduped by message id) is a turn and gives the context it saw;
 *   the closing `result` line carries the run's final token totals and `total_cost_usd` (assistant lines carry
 *   streaming placeholders for output, so the result wins when present).
 * - Pi (`message_end`, role assistant): `usage{input,output,cacheRead,cacheWrite,cost.total}` per turn.
 * - OpenCode `step_finish`: `part.tokens{input,output,cache{read,write}}`, `part.cost`.
 */
class StreamUsageAccumulator {
    private val seen = HashSet<String>()
    private var turns = 0L
    private var input = 0L
    private var output = 0L
    private var read = 0L
    private var write = 0L
    private var context = 0L
    private var cost: Double? = null
    private var model: String? = null
    private var any = false

    // Claude `result` totals, which override the summed assistant usage.
    private var resultUsage: LongArray? = null
    private var resultCost: Double? = null
    private var resultTurns: Long? = null

    fun accept(line: String) {
        if ('{' !in line || ("usage" !in line && "tokens" !in line)) return
        val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (o.str("type")) {
            "assistant" -> claudeAssistant(o)
            "result" -> claudeResult(o)
            "message_end" -> piMessage(o["message"] as? JsonObject)
            "step_finish" -> openCodeStep(o["part"] as? JsonObject)
        }
    }

    private fun claudeAssistant(o: JsonObject) {
        val m = o["message"] as? JsonObject ?: return
        val u = m["usage"] as? JsonObject ?: return
        val id = m.str("id")
        if (id != null && !seen.add(id)) return
        m.str("model")?.takeIf { it != "<synthetic>" }?.let { model = it }
        turn(u.l("input_tokens"), u.l("output_tokens"), u.l("cache_read_input_tokens"), u.l("cache_creation_input_tokens"), null)
    }

    private fun claudeResult(o: JsonObject) {
        val u = o["usage"] as? JsonObject
        if (u != null) {
            resultUsage = longArrayOf(u.l("input_tokens"), u.l("output_tokens"), u.l("cache_read_input_tokens"), u.l("cache_creation_input_tokens"))
            any = true
        }
        resultCost = (o["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull
        resultTurns = (o["num_turns"] as? JsonPrimitive)?.longOrNull
        if (model == null) model = (o["modelUsage"] as? JsonObject)?.keys?.firstOrNull()
    }

    private fun piMessage(m: JsonObject?) {
        if (m == null || m.str("role") != "assistant") return
        val u = m["usage"] as? JsonObject ?: return
        val provider = m.str("provider")
        m.str("model")?.let { model = if (provider != null) "$provider/$it" else it }
        turn(u.l("input"), u.l("output"), u.l("cacheRead"), u.l("cacheWrite"), ((u["cost"] as? JsonObject)?.get("total") as? JsonPrimitive)?.doubleOrNull)
    }

    private fun openCodeStep(p: JsonObject?) {
        val t = p?.get("tokens") as? JsonObject ?: return
        val c = t["cache"] as? JsonObject
        turn(t.l("input"), t.l("output"), c?.l("read") ?: 0, c?.l("write") ?: 0, (p["cost"] as? JsonPrimitive)?.doubleOrNull)
    }

    private fun turn(i: Long, out: Long, r: Long, w: Long, c: Double?) {
        if (i + out + r + w == 0L) return
        any = true
        turns++
        input += i; output += out; read += r; write += w
        context += i + r + w
        if (c != null) cost = (cost ?: 0.0) + c
    }

    /** Null when the run reported no usage at all (never a zero row). */
    fun totals(): RunTotals? {
        if (!any) return null
        val ru = resultUsage
        val t = if (turns > 0) turns else resultTurns ?: 1L
        return if (ru != null) {
            // Per-turn context from the assistant lines when there are any, else the result's total over its turns.
            RunTotals(t, ru[0], ru[1], ru[2], ru[3], if (turns > 0) context else ru[0] + ru[2] + ru[3], resultCost ?: cost, model)
        } else RunTotals(t, input, output, read, write, context, cost, model)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.l(k: String): Long = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
}
