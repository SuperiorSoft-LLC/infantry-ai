package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.TicketFilesCli
import com.superiorsoftllc.agenthq.ticketfiles.TicketFilesQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * TICKET-FILES (contract 1.137.0): `agenthq_ticket_files` (a ticket's changed files, or the tickets that changed a path
 * glob) and `agenthq_ticket_related_files` (the union of the files of the top related tickets, naming them). Read-only,
 * plain string / number arguments, so pi workers get them as DIRECT tools. Answers are capped compact text.
 */
internal class McpTicketFilesTools {

    suspend fun files(stream: FrameStream, arguments: JsonObject): ToolResult {
        val id = ticketId(arguments)
        val paths = when (val p = arguments["path"]) {
            is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> listOfNotNull(p.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
            else -> emptyList()
        }
        if ((id == null) == paths.isEmpty()) return ToolResult("give exactly one of ticketId or path", isError = true)
        return answer(stream, TicketFilesQuery(ticketId = id, paths = paths, limit = limit(arguments)))
    }

    suspend fun related(stream: FrameStream, arguments: JsonObject): ToolResult {
        val id = ticketId(arguments) ?: return ToolResult("ticketId is required", isError = true)
        val top = (arguments["top"] as? JsonPrimitive)?.intOrNull
        return answer(stream, TicketFilesQuery(ticketId = id, related = true, limit = limit(arguments), top = top))
    }

    private suspend fun answer(stream: FrameStream, query: TicketFilesQuery): ToolResult = try {
        ToolResult(TicketFilesCli.request(stream, query))
    } catch (e: Exception) {
        ToolResult(e.message ?: "ticket_files failed", isError = true)
    }

    /** A bare short id (`55476f3c`) is the ticket `ticket-55476f3c`. */
    private fun ticketId(arguments: JsonObject): String? =
        (arguments["ticketId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("ticket-")) it else "ticket-$it" }

    private fun limit(arguments: JsonObject): Int? = (arguments["limit"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    companion object {
        const val FILES = "agenthq_ticket_files"
        const val RELATED = "agenthq_ticket_related_files"
        val NAMES = listOf(FILES, RELATED)

        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", FILES)
                put(
                    "description",
                    "Files changed by a ticket (status A/M/D/R, +added -removed, path), or with `path` the tickets that " +
                        "changed matching files (glob, `**` ok; no wildcard = any path containing it). Recorded when each " +
                        "ticket went done, kept after its branch is deleted. Cheaper than grepping git history.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("ticketId", buildJsonObject { put("type", "string"); put("description", "ticket id, e.g. ticket-55476f3c or 55476f3c") })
                        put("path", buildJsonObject { put("type", "string"); put("description", "path or glob, e.g. **/TicketBoard.kt") })
                        put("limit", buildJsonObject { put("type", "integer"); put("description", "most lines (default: limits.ticketFiles.maxLines)") })
                    })
                    put("required", buildJsonArray {})
                    put("additionalProperties", false)
                })
                readOnly()
            })
            add(buildJsonObject {
                put("name", RELATED)
                put(
                    "description",
                    "Call this BEFORE grepping: the tickets that changed the same files as this ticket (ranked by overlap), " +
                        "and the union of the files they changed, each with the ticket ids that touched it.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("ticketId", buildJsonObject { put("type", "string"); put("description", "ticket id, e.g. ticket-55476f3c") })
                        put("top", buildJsonObject { put("type", "integer"); put("description", "how many related tickets (default: limits.ticketFiles.relatedTop)") })
                        put("limit", buildJsonObject { put("type", "integer"); put("description", "most file lines (default: limits.ticketFiles.maxLines)") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
                    put("additionalProperties", false)
                })
                readOnly()
            })
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.readOnly() {
            put("annotations", buildJsonObject {
                put("readOnlyHint", true)
                put("destructiveHint", false)
                put("idempotentHint", true)
                put("openWorldHint", false)
            })
        }
    }
}
