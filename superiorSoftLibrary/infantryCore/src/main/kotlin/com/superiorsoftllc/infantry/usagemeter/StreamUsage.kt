package com.superiorsoftllc.infantry.usagemeter

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

/** One model request/response (a "turn"), as the harness reported it. A field the harness did not report is null, never 0. */
data class TurnUsage(
    val model: String?,
    val input: Long?,
    val output: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val cost: Double?,
    /** USAGE-OUT-1: the part of [output] the provider reports as thinking/reasoning, when it reports it separately; null = not reported. */
    val thinking: Long? = null,
    /** The provider's message id, when the stream has one (later lines of the same message refine [output]). */
    val messageId: String? = null,
) {
    /** Tokens the model re-read this request (input + cache read + cache write); null when none of the three was reported. */
    val context: Long? get() = if (input == null && cacheRead == null && cacheWrite == null) null else (input ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
}

/** A compaction / context rotation the harness announced. [tokensBefore] null = not reported. */
data class CompactionUsage(val trigger: String?, val tokensBefore: Long?)

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
    /** STATS-ACC-1: called once per recorded turn, as the line arrives (the push path). */
    var onTurn: ((TurnUsage) -> Unit)? = null
    var onCompaction: ((CompactionUsage) -> Unit)? = null
    /** USAGE-OUT-1: a later line of an already-recorded message carried a larger (final) output count: (messageId, output, thinking). */
    var onOutputUpdate: ((String, Long, Long?) -> Unit)? = null
    private val outById = HashMap<String, Long>()

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
        if ('{' !in line || ("usage" !in line && "tokens" !in line && "compact" !in line)) return
        val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (o.str("type")) {
            "assistant" -> claudeAssistant(o)
            "result" -> claudeResult(o)
            "message_end" -> piMessage(o["message"] as? JsonObject)
            "step_finish" -> openCodeStep(o["part"] as? JsonObject)
            "system" -> if (o.str("subtype") == "compact_boundary") {
                val md = o["compact_metadata"] as? JsonObject
                onCompaction?.invoke(CompactionUsage(md?.str("trigger"), md?.ln("pre_tokens")))
            }
            "auto_compaction_end", "compaction_end" -> {
                val r = o["result"] as? JsonObject
                onCompaction?.invoke(CompactionUsage(o.str("reason") ?: "auto", r?.ln("tokensBefore") ?: o.ln("tokensBefore")))
            }
        }
    }

    private fun claudeAssistant(o: JsonObject) {
        val m = o["message"] as? JsonObject ?: return
        val u = m["usage"] as? JsonObject ?: return
        val id = m.str("id")
        if (id != null && !seen.add(id)) {
            // Later lines of one message: the final usage replaces the streaming placeholder of the first line.
            val out = u.ln("output_tokens")
            val prev = outById[id]
            if (out != null && prev != null && out > prev) {
                outById[id] = out; output += out - prev
                onOutputUpdate?.invoke(id, out, u.thinking())
            }
            return
        }
        if (id != null) u.ln("output_tokens")?.let { outById[id] = it }
        m.str("model")?.takeIf { it != "<synthetic>" }?.let { model = it }
        turn(u.l("input_tokens"), u.l("output_tokens"), u.l("cache_read_input_tokens"), u.l("cache_creation_input_tokens"), null,
            TurnUsage(m.str("model")?.takeIf { it != "<synthetic>" }, u.ln("input_tokens"), u.ln("output_tokens"), u.ln("cache_read_input_tokens"), u.ln("cache_creation_input_tokens"), null, u.thinking(), id))
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
        val cost = ((u["cost"] as? JsonObject)?.get("total") as? JsonPrimitive)?.doubleOrNull
        turn(u.l("input"), u.l("output"), u.l("cacheRead"), u.l("cacheWrite"), cost,
            TurnUsage(model, u.ln("input"), u.ln("output"), u.ln("cacheRead"), u.ln("cacheWrite"), cost, u.ln("reasoning") ?: u.ln("thinking")))
    }

    private fun openCodeStep(p: JsonObject?) {
        val t = p?.get("tokens") as? JsonObject ?: return
        val c = t["cache"] as? JsonObject
        val cost = (p["cost"] as? JsonPrimitive)?.doubleOrNull
        turn(t.l("input"), t.l("output"), c?.l("read") ?: 0, c?.l("write") ?: 0, cost,
            TurnUsage(model, t.ln("input"), t.ln("output"), c?.ln("read"), c?.ln("write"), cost, t.ln("reasoning")))
    }

    private fun turn(i: Long, out: Long, r: Long, w: Long, c: Double?, reported: TurnUsage) {
        if (i + out + r + w == 0L) return
        onTurn?.invoke(reported)
        any = true
        turns++
        input += i; output += out; read += r; write += w
        context += i + r + w
        if (c != null) cost = (cost ?: 0.0) + c
    }

    /** H5b: usage a plugin harness's translator reported as a neutral event (its stdout is in no format [accept] knows). */
    fun acceptEvent(input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0) =
        turn(input, output, cacheRead, cacheWrite, null, TurnUsage(model, input, output, cacheRead, cacheWrite, null))

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

    /** Reasoning/thinking tokens a provider reports inside its usage object (generic key names, no model names). */
    private fun JsonObject.thinking(): Long? = ln("thinking_tokens") ?: ln("reasoning_tokens")
        ?: (this["output_tokens_details"] as? JsonObject)?.let { it.ln("reasoning_tokens") ?: it.ln("thinking_tokens") }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.ln(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.l(k: String): Long = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
}
