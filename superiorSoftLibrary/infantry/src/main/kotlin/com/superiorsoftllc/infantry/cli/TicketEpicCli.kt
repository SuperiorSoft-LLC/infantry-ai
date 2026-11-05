// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * EPIC-MOVE-1: `infantry ticket epic <id...> <epic|none> [--force]`, one call of the board plugin's `infantry_ticket_epic` tool
 * (`plugin_tool_call`), so the CLI, MCP and the board UI share one answer and one rule.
 */
internal object TicketEpicCli {
    const val USAGE = "infantry ticket epic <id|prefix>... <epic id|epic name|none> [--force]"
    private const val TOOL = "infantry_ticket_epic"

    /** [args] are the words after `ticket epic`. */
    fun usageError(args: List<String>): String? = if (args.size < 2 || args.any { it.isBlank() }) USAGE else null

    fun run(stream: FrameStream, args: List<String>, force: Boolean, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val arguments = buildMap {
            put("tickets", JsonArray(args.dropLast(1).map { JsonPrimitive(it.trim()) }))
            put("epic", JsonPrimitive(args.last().trim()))
            if (force) put("force", JsonPrimitive(true))
        }
        val result = McpPluginTools(getenv).call(stream, TOOL, JsonObject(arguments))
        if (result.text.startsWith("Unknown tool:")) {
            err.appendLine("error: this daemon has no $TOOL tool (Infantry_Board's daemon half is not loaded, or it is older)")
            return@runBlocking 1
        }
        // The tool answers JSON with a `message`; print that line (a refusal is plain text).
        val message = runCatching { (Json.parseToJsonElement(result.text) as JsonObject)["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        (if (result.isError) err else out).appendLine(message ?: result.text)
        if (result.isError) 1 else 0
    }
}
