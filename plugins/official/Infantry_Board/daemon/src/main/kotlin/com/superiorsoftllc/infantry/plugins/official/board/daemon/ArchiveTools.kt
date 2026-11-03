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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * ARCHIVE-1 / RESTORE (contract 1.149.0): the ticket archive as MCP tools. `infantry_ticket_archive` and `infantry_ticket_restore` move tickets
 * (by ids, an epic, or a lane filter `status` + `olderThanDays`); `infantry_archive` lists / shows / searches / counts the archive. Each is one
 * `archive_query` / `archive_apply` request answered with the daemon's capped text. An archived ticket leaves the live board; its changed files
 * still feed `infantry_ticket_related_files`.
 */
object ArchiveTools {
    const val ARCHIVE = "infantry_ticket_archive"
    const val RESTORE = "infantry_ticket_restore"
    const val QUERY = "infantry_archive"
    private const val APPLY_FRAME = "archive_apply"
    private const val QUERY_FRAME = "archive_query"
    private const val REPORT = "archive_report"
    private const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    private fun props(vararg extra: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            for ((k, v) in extra) put(k, v)
        })
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }

    private fun str(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun int(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }
    private fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
    private val ids = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", "ticket ids (ticket-xxxxxxxx or the 8 char suffix)")
    }

    private fun annotations(readOnly: Boolean) = buildJsonObject {
        put("readOnlyHint", readOnly)
        put("destructiveHint", false)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }

    object Archive : DaemonTool {
        override val name = ARCHIVE
        override val description =
            "Archive tickets out of the live board (reversible with infantry_ticket_restore): give ids, or epicId (an epic with its tickets), " +
                "or status + olderThanDays (a lane filter, e.g. done older than 30 days). In-progress tickets are never archived. " +
                "Archived tickets keep everything and still show in related files."
        override val inputSchema = props(
            "ids" to ids, "epicId" to str("archive every ticket of this epic"), "projectId" to str("limit a lane filter to this project"),
            "status" to str("lane to archive, e.g. done"), "olderThanDays" to int("only tickets last changed more than N days ago"),
            "dryRun" to bool("count what would move, move nothing"),
        )
        override val annotations = annotations(false)
        override fun call(call: ToolCall) = apply(call, "archive")
    }

    object Restore : DaemonTool {
        override val name = RESTORE
        override val description =
            "Restore archived tickets to the live board exactly as they were (lane, position, links, branch, commits, files, project, epic): " +
                "give ids, or epicId to restore every archived ticket of an epic."
        override val inputSchema = props(
            "ids" to ids, "epicId" to str("restore every archived ticket of this epic"), "projectId" to str("limit to this project"),
            "dryRun" to bool("count what would move, move nothing"),
        )
        override val annotations = annotations(false)
        override fun call(call: ToolCall) = apply(call, "restore")
    }

    object Query : DaemonTool {
        override val name = QUERY
        override val description = "Read the ticket archive: op list (newest first), show (one id, full text), search (text in title / description / id), count."
        override val inputSchema = props(
            "op" to str("list | show | search | count (default list)"), "ids" to ids, "text" to str("search text"),
            "projectId" to str("limit to this project"), "epicId" to str("limit a list to this epic"),
            "limit" to int("most rows (default 100, max 500)"), "offset" to int("skip rows (list)"),
        )
        override val annotations = annotations(true)
        override fun call(call: ToolCall): ToolResult {
            val a = call.arguments
            val op = (a["op"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: "list"
            return send(call, QUERY_FRAME, payload(a, op))
        }
    }

    private fun apply(call: ToolCall, op: String): ToolResult = send(call, APPLY_FRAME, payload(call.arguments, op))

    private fun payload(a: JsonObject, op: String): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("op", op)
        val list = (a["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?: (a["ids"] as? JsonPrimitive)?.contentOrNull?.let { listOf(it.trim()) }.orEmpty()
        if (list.isNotEmpty()) put("ids", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
        for (k in listOf("projectId", "epicId", "status", "text")) (a[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put(k, it) }
        for (k in listOf("olderThanDays", "limit", "offset")) (a[k] as? JsonPrimitive)?.intOrNull?.let { put(k, it) }
        if ((a["dryRun"] as? JsonPrimitive)?.booleanOrNull == true) put("dryRun", true)
    })

    private fun send(call: ToolCall, frame: String, payload: String): ToolResult = try {
        val reply = call.request(frame, payload)
        when (reply.type) {
            REPORT -> ToolResult(((json.parseToJsonElement(reply.data) as JsonObject)["text"] as JsonPrimitive).content)
            ERROR -> ToolResult(reply.data.ifBlank { "archive failed" }, isError = true)
            else -> ToolResult("archive: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "archive failed", isError = true)
    }
}
