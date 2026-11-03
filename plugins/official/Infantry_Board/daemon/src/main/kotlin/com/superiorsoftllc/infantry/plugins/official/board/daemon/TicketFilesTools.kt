// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * TICKET-FILES (contract 1.137.0), moved out of the CLI's MCP server by DAEMON-PLUGINS-1: `infantry_ticket_files` (a
 * ticket's changed files, or the tickets that changed a path glob) and `infantry_ticket_related_files` (the union of the
 * files of the top related tickets, naming them). Read-only, plain string / number arguments, so pi workers get them as
 * DIRECT tools. Answers are the daemon's capped compact text (`ticket_files_report`).
 */
object TicketFilesTools {
    const val FILES = "infantry_ticket_files"
    const val RELATED = "infantry_ticket_related_files"
    private const val TICKET_FILES = "ticket_files"
    private const val TICKET_FILES_REPORT = "ticket_files_report"
    private const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    object Files : DaemonTool {
        override val name = FILES
        override val description =
            "Files changed by a ticket (status A/M/D/R, +added -removed, path), or with `path` the tickets that " +
                "changed matching files (glob, `**` ok; no wildcard = any path containing it). Recorded when each " +
                "ticket went done, kept after its branch is deleted. Cheaper than grepping git history."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("ticketId", buildJsonObject { put("type", "string"); put("description", "ticket id, e.g. ticket-55476f3c or 55476f3c") })
                put("path", buildJsonObject { put("type", "string"); put("description", "path or glob, e.g. **/TicketBoard.kt") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "most lines (default: limits.ticketFiles.maxLines)") })
            })
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }
        override val annotations = READ_ONLY

        override fun call(call: ToolCall): ToolResult {
            val arguments = call.arguments
            val id = ticketId(arguments)
            val paths = when (val p = arguments["path"]) {
                is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                is JsonPrimitive -> listOfNotNull(p.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
                else -> emptyList()
            }
            if ((id == null) == paths.isEmpty()) return ToolResult("give exactly one of ticketId or path", isError = true)
            return answer(call, query(ticketId = id, paths = paths, limit = limit(arguments)))
        }
    }

    object Related : DaemonTool {
        override val name = RELATED
        override val description =
            "Call this BEFORE grepping: the tickets that changed the same files as this ticket (ranked by overlap), " +
                "and the union of the files they changed, each with the ticket ids that touched it."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("ticketId", buildJsonObject { put("type", "string"); put("description", "ticket id, e.g. ticket-55476f3c") })
                put("top", buildJsonObject { put("type", "integer"); put("description", "how many related tickets (default: limits.ticketFiles.relatedTop)") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "most file lines (default: limits.ticketFiles.maxLines)") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
            put("additionalProperties", false)
        }
        override val annotations = READ_ONLY

        override fun call(call: ToolCall): ToolResult {
            val id = ticketId(call.arguments) ?: return ToolResult("ticketId is required", isError = true)
            val top = (call.arguments["top"] as? JsonPrimitive)?.intOrNull
            return answer(call, query(ticketId = id, related = true, limit = limit(call.arguments), top = top))
        }
    }

    /** The `ticket_files` payload: only the keys that are set (the daemon's `TicketFilesQuery`, defaults left out). */
    internal fun query(ticketId: String? = null, paths: List<String> = emptyList(), related: Boolean = false, limit: Int? = null, top: Int? = null): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            ticketId?.let { put("ticketId", it) }
            if (paths.isNotEmpty()) put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
            if (related) put("related", true)
            limit?.let { put("limit", it) }
            top?.let { put("top", it) }
        })

    private fun answer(call: ToolCall, payload: String): ToolResult = try {
        val reply = call.request(TICKET_FILES, payload)
        when (reply.type) {
            TICKET_FILES_REPORT -> ToolResult(((json.parseToJsonElement(reply.data) as JsonObject)["text"] as JsonPrimitive).content)
            ERROR -> ToolResult(reply.data.ifBlank { "ticket_files failed" }, isError = true)
            else -> ToolResult("ticket_files: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "ticket_files failed", isError = true)
    }

    /** A bare short id (`55476f3c`) is the ticket `ticket-55476f3c`. */
    private fun ticketId(arguments: JsonObject): String? =
        (arguments["ticketId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("ticket-")) it else "ticket-$it" }

    private fun limit(arguments: JsonObject): Int? = (arguments["limit"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private val READ_ONLY = buildJsonObject {
        put("readOnlyHint", true)
        put("destructiveHint", false)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }
}
