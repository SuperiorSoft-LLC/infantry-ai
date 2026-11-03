package com.superiorsoftllc.infantry.stats

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Contract 1.113.0 (STATS-2): the ONE harness-neutral record of a model request. Every harness has an adapter
 * ([UsageAdapters]) that turns its own shape into this; [StatsAccumulator] folds only this, so the numbers mean the
 * same for every harness.
 *
 * - [contextTokens] = input + cacheRead + cacheWrite: what the model saw.
 * - [billedTokens]  = contextTokens + output (reasoning is never added: harnesses disagree on whether output has it).
 * - [cost] is what the harness itself REPORTED for this request, null when it reports none. Never computed here.
 */
internal data class RequestUsage(
    val at: Long?,
    val model: String?,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val cost: Double? = null,
) {
    val contextTokens: Long get() = input + cacheRead + cacheWrite
    val billedTokens: Long get() = contextTokens + output

    /** A request that reports no tokens at all (an aborted or synthetic message) is not a request: it is skipped. */
    val isEmpty: Boolean get() = billedTokens == 0L
}

/** Per-harness adapters: a raw usage object or row in, a [RequestUsage] out. */
internal object UsageAdapters {
    /** Claude Code `message.usage`: `input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, `output_tokens`. Reports no cost. */
    fun claude(u: JsonObject, at: Long?, model: String?) = RequestUsage(
        at, model, u.long("input_tokens"), u.long("output_tokens"),
        u.long("cache_read_input_tokens"), u.long("cache_creation_input_tokens"),
    )

    /** Pi / Oh My Pi `message.usage`: `input`, `output`, `cacheRead`, `cacheWrite`, `cost.total`. */
    fun pi(u: JsonObject, at: Long?, model: String?) = RequestUsage(
        at, model, u.long("input"), u.long("output"), u.long("cacheRead"), u.long("cacheWrite"),
        (u["cost"] as? JsonObject)?.double("total"),
    )

    /** OpenCode `step-finish` part: `tokens{input,output,cache{read,write}}` and `cost`. */
    fun openCode(tokens: JsonObject, cost: Double?, at: Long?, model: String?): RequestUsage {
        val cache = tokens["cache"] as? JsonObject
        return RequestUsage(at, model, tokens.long("input"), tokens.long("output"), cache?.long("read") ?: 0L, cache?.long("write") ?: 0L, cost)
    }

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
    fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
