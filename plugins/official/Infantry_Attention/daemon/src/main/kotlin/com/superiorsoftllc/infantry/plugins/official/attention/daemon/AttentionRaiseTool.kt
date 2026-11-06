// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * BRIDGE-EVENTS-1: `infantry_attention_raise`, the script bridge to the owner's attention list. A process that used to raise an item from
 * core (RESUME-NOTIFY-1's restart summary) is a script now, so a script (or an agent) raises the item itself: the same store, kinds and
 * frames as the daemon's own [com.superiorsoftllc.infantry.daemon.api.attention.Attention.raise]. Returns the new item's id.
 */
class AttentionRaiseTool(private val raise: (kind: String, ticketId: String?, sessionId: String?, outcome: String, detail: String) -> String) : DaemonTool {
    override val name = NAME
    override val description = "Raise an item on the owner's attention list (kind, outcome, detail; optional ticketId / sessionId). For scripts and agents that report something the owner must see."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("kind", buildJsonObject { put("type", "string"); put("description", "An attention kind, e.g. session_resumed, stalled, unreported.") })
            put("outcome", buildJsonObject { put("type", "string"); put("description", "A short machine word for what happened, e.g. resumed_after_restart.") })
            put("detail", buildJsonObject { put("type", "string"); put("description", "What the owner reads, at most $DETAIL_MAX characters.") })
            put("ticketId", buildJsonObject { put("type", "string") })
            put("sessionId", buildJsonObject { put("type", "string") })
        })
        put("required", buildJsonArray { listOf("kind", "outcome", "detail").forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", false); put("destructiveHint", false); put("idempotentHint", false); put("openWorldHint", false)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    override fun call(call: ToolCall): ToolResult {
        val a = call.arguments
        val kind = a.string("kind") ?: return ToolResult("$NAME needs a kind", isError = true)
        val outcome = a.string("outcome") ?: return ToolResult("$NAME needs an outcome", isError = true)
        val detail = a.string("detail") ?: return ToolResult("$NAME needs a detail", isError = true)
        return runCatching { raise(kind, a.string("ticketId"), a.string("sessionId"), outcome, detail.take(DETAIL_MAX)) }
            .fold({ ToolResult(buildJsonObject { put("id", it) }.toString()) }, { ToolResult("$NAME refused: ${it.message}", isError = true) })
    }

    companion object {
        const val NAME = "infantry_attention_raise"
        const val DETAIL_MAX = 2000
    }
}
