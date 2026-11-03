package com.superiorsoftllc.infantry.tokens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TokenCounts(val tokensIn: Long, val tokensOut: Long)

/**
 * Sums `part.tokens.input` / `part.tokens.output` over every `step_finish` line of an
 * `opencode run --format json` stream. Reasoning and cache tokens are excluded.
 * Returns null when no step_finish carried counts (unknown, never 0).
 */
object OpenCodeUsageParser {
    fun parse(lines: Sequence<String>): TokenCounts? {
        var tokensIn = 0L
        var tokensOut = 0L
        var seen = false
        for (line in lines) {
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (obj["type"]?.jsonPrimitive?.contentOrNull() != "step_finish") continue
            val tokens = (obj["part"] as? JsonObject)?.get("tokens") as? JsonObject ?: continue
            val input = tokens["input"]?.jsonPrimitive?.longOrNull ?: continue
            val output = tokens["output"]?.jsonPrimitive?.longOrNull ?: continue
            tokensIn += input
            tokensOut += output
            seen = true
        }
        return if (seen) TokenCounts(tokensIn, tokensOut) else null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null
}
