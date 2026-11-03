// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.protocol.TicketWeight
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** TICKET-WEIGHT: the executor one weight maps to. A null field leaves that choice to the normal resolution. */
data class WeightExecutor(val provider: String? = null, val model: String? = null)

/**
 * TICKET-WEIGHT: the project's weight -> {provider, model} mapping (setting [SETTING], a JSON object keyed "1".."5").
 * It applies ONLY to a weighted ticket that pins nothing of its own; see [applies].
 */
class WeightExecutors(private val byWeight: Map<Int, WeightExecutor>) {
    /** The executor for [weight], or null when the weight is unset or the mapping has no usable entry for it. */
    fun forWeight(weight: Int?): WeightExecutor? =
        weight?.let(byWeight::get)?.takeIf { it.provider != null || it.model != null }

    fun toJson(): JsonObject = buildJsonObject {
        for (w in TicketWeight.RANGE) {
            val e = byWeight[w] ?: continue
            put(
                w.toString(),
                buildJsonObject {
                    put("provider", e.provider?.let(::JsonPrimitive) ?: JsonNull)
                    put("model", e.model?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }

    companion object {
        const val SETTING = "tickets.weightExecutors"

        /** Low (1-2) = cheap, mid (3) = sonnet-class, high (4-5) = opus-class, which runs only on pi (the Opus safety rule). The owner edits these in project settings. */
        val DEFAULT = WeightExecutors(
            mapOf(
                1 to WeightExecutor("claude", "claude-haiku-5-5"),
                2 to WeightExecutor("claude", "claude-haiku-5-5"),
                3 to WeightExecutor("claude", "claude-sonnet-5-5"),
                4 to WeightExecutor(RoutingRules.OPUS_PROVIDER, RoutingRules.OPUS_MODEL),
                5 to WeightExecutor(RoutingRules.OPUS_PROVIDER, RoutingRules.OPUS_MODEL),
            ),
        )

        /**
         * Precedence: the mapping is used only when the ticket has a weight, pins no provider and no model, and its profile /
         * role binding resolves to neither ([boundProvider], [boundModel]). An explicit role, provider or model always wins.
         */
        fun applies(weight: Int?, ticketProvider: String?, ticketModel: String?, boundProvider: String?, boundModel: String?): Boolean =
            weight != null && ticketProvider == null && ticketModel == null && boundProvider == null && boundModel == null

        /** Parses a stored / submitted mapping; throws IllegalArgumentException naming the problem. Missing weights keep the [DEFAULT]. */
        fun parse(value: JsonElement): WeightExecutors {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("must be a JSON object keyed by weight ${TicketWeight.RANGE}")
            val out = DEFAULT.byWeight.toMutableMap()
            for ((key, entry) in obj) {
                val w = key.toIntOrNull()?.takeIf { it in TicketWeight.RANGE }
                    ?: throw IllegalArgumentException("key '${key.take(40)}' is not a weight in ${TicketWeight.RANGE}")
                val o = entry as? JsonObject ?: throw IllegalArgumentException("weight $w must be an object {provider, model}")
                fun field(name: String): String? = when (val v = o[name]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> v.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("weight $w: $name must be a non-empty string or null")
                    else -> throw IllegalArgumentException("weight $w: $name must be a string or null")
                }
                val provider = field("provider")?.lowercase()
                val model = field("model")
                require(provider != null || model != null) { "weight $w needs a provider or a model" }
                // ROUTING-1: the Opus safety line holds for the legacy mapping too (Opus only as pi + anthropic/claude-opus-5-5).
                RoutingRules.requireOpusSafe("weight $w", provider, model)
                out[w] = WeightExecutor(provider, model)
            }
            return WeightExecutors(out)
        }
    }
}
