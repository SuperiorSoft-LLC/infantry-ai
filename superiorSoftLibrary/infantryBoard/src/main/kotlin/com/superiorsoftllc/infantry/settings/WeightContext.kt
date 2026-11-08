// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.protocol.TicketWeight
import com.superiorsoftllc.infantry.daemon.api.board.ContextLimits
import com.superiorsoftllc.infantry.daemon.api.board.EffortLevels
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** TOKEN-7: the context limit (auto-compact window, in tokens) and effort level one weight runs with. A null field sets nothing. */
data class WeightContextEntry(val contextLimit: Int? = null, val effort: String? = null)

/**
 * TOKEN-7: the project's weight -> {contextLimit, effort} defaults (setting [SETTING], a JSON object keyed "1".."5").
 * They fill only what the ticket (and its role, for effort) leaves unset; an explicit pin always wins. The stored object
 * replaces [DEFAULT] whole, so `{}` turns the feature off. How a harness honours either value is its own capability.
 */
class WeightContext(private val byWeight: Map<Int, WeightContextEntry>) {
    fun forWeight(weight: Int?): WeightContextEntry? =
        weight?.let(byWeight::get)?.takeIf { it.contextLimit != null || it.effort != null }

    fun toJson(): JsonObject = buildJsonObject {
        for (w in TicketWeight.RANGE) {
            val e = byWeight[w] ?: continue
            put(
                w.toString(),
                buildJsonObject {
                    put("contextLimit", e.contextLimit?.let(::JsonPrimitive) ?: JsonNull)
                    put("effort", e.effort?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }

    companion object {
        const val SETTING = "tickets.weightContext"

        /**
         * CONTEXT-DEFAULT-1: no weight sets a context window of its own; every ticket gets `limits.session.defaultContextTokens`
         * (500k) unless the owner adds a contextLimit to a weight here. Efforts stay low for weights 1-2 and the role's own
         * for 3-5. A lower contextLimit compacts earlier, and each compaction costs a full-context summary plus a cache rebuild.
         */
        val DEFAULT = WeightContext(
            mapOf(
                1 to WeightContextEntry(null, "low"),
                2 to WeightContextEntry(null, "low"),
            ),
        )

        /** Parses a stored / submitted mapping; throws IllegalArgumentException naming the problem. */
        fun parse(value: JsonElement): WeightContext {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("must be a JSON object keyed by weight ${TicketWeight.RANGE}")
            val out = mutableMapOf<Int, WeightContextEntry>()
            for ((key, entry) in obj) {
                val w = key.toIntOrNull()?.takeIf { it in TicketWeight.RANGE }
                    ?: throw IllegalArgumentException("key '${key.take(40)}' is not a weight in ${TicketWeight.RANGE}")
                val o = entry as? JsonObject ?: throw IllegalArgumentException("weight $w must be an object {contextLimit, effort}")
                val limit = when (val v = o["contextLimit"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> v.content.toIntOrNull()?.also { n -> ContextLimits.problem(n)?.let { throw IllegalArgumentException("weight $w: $it") } }
                        ?: throw IllegalArgumentException("weight $w: contextLimit must be a token count or null")
                    else -> throw IllegalArgumentException("weight $w: contextLimit must be a token count or null")
                }
                val effort = when (val v = o["effort"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> v.takeIf { it.isString }?.content?.trim()?.lowercase()?.also { e ->
                        EffortLevels.problem(e)?.let { throw IllegalArgumentException("weight $w: $it") }
                    } ?: throw IllegalArgumentException("weight $w: effort must be a level name or null")
                    else -> throw IllegalArgumentException("weight $w: effort must be a level name or null")
                }
                require(limit != null || effort != null) { "weight $w needs a contextLimit or an effort" }
                out[w] = WeightContextEntry(limit, effort)
            }
            return WeightContext(out)
        }
    }
}
