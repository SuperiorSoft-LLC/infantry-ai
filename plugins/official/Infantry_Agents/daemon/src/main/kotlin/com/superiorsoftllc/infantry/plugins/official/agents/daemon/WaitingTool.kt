// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * WAITING-FLAG-1: `infantry_waiting`, the MCP twin of `infantry waiting on|off|show`. It sends the same `waiting` frame for the
 * session the MCP process runs in ([ToolCall.clientSessionId]); the CLI does the same with no MCP at all. Gated by the frame's
 * own action, `agents.report`.
 */
object WaitingTool : DaemonTool {
    const val WAITING = "infantry_waiting"
    private const val FRAME = "waiting"
    private const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    override val name = WAITING
    override val description =
        "Flag YOUR session 'active but waiting' (on) or not waiting (off). Use it when your turn is about to end while you wait on a " +
            "process (a build, a long test, a background job): your card shows 'waiting' with your reason instead of looking idle, and " +
            "no watcher nudges you. It turns itself off when your next turn starts, and expires after untilMinutes (+ a grace). " +
            "get = read the flag. The CLI does the same without MCP: infantry waiting on --reason \"...\" [--minutes N] | off."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { listOf("on", "off", "get").forEach { add(JsonPrimitive(it)) } })
            })
            put("reason", buildJsonObject { put("type", "string"); put("description", "mode on: what you are waiting for (required).") })
            put("untilMinutes", buildJsonObject { put("type", "integer"); put("description", "mode on: how long you expect to wait (omit = the default setting).") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("mode")) })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", false)
        put("destructiveHint", false)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }

    override fun call(call: ToolCall): ToolResult {
        val a = call.arguments
        val mode = (a["mode"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (mode !in listOf("on", "off", "get")) return ToolResult("$WAITING: needs mode (on, off, get)", isError = true)
        val body = buildJsonObject {
            put("op", mode!!)
            (a["reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            (a["untilMinutes"] as? JsonPrimitive)?.intOrNull?.let { put("minutes", it) }
        }
        val session = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("not running inside an Infantry AI session (no INFANTRY_SESSION_ID): nothing flagged", isError = true)
        val answer = try {
            call.request(FRAME, body.toString(), session)
        } catch (e: Exception) {
            return ToolResult("$WAITING refused: ${e.message ?: "waiting failed"}", isError = true)
        }
        if (answer.type == ERROR) return ToolResult("$WAITING refused: ${answer.data.ifBlank { "waiting failed" }}", isError = true)
        return ToolResult(line(answer.data, System.currentTimeMillis()))
    }

    internal fun line(data: String, now: Long): String {
        val w: JsonObject = data.takeIf { it.isNotBlank() && it.trim() != "null" }?.let { json.parseToJsonElement(it).jsonObject } ?: return "not waiting"
        fun long(k: String) = (w[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val reason = (w["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (now > long("expiresAt")) "waiting EXPIRED ${(now - long("untilAt")) / 60_000} min past the expected end: $reason"
        else "waiting ${(now - long("since")) / 60_000} min of ~${(long("untilAt") - long("since")) / 60_000}: $reason"
    }
}
