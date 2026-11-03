// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TICKET-COMMENTS-2: `infantry ticket comment <id> [--kind done|issue|note] <text...> | resolve <commentId> | list <id> [which] [kind]`.
 * Each is one call of Infantry_Board's `infantry_ticket_comment(s)` tool (`plugin_tool_call`): the CLI and MCP share one answer and one gate.
 */
internal object TicketCommentCli {
    const val USAGE = "infantry ticket comment <id> [done|issue|note] <text...> | infantry ticket comment resolve <commentId> | infantry ticket comment list <id> [latestOpen|latest|first|all] [done|issue|note]"
    private val kinds = setOf("done", "issue", "note", "handoff")

    /** [args] are the words after `ticket comment`. */
    fun usageError(args: List<String>): String? = when (args.firstOrNull()) {
        null -> USAGE
        "resolve" -> if (args.size != 2) USAGE else null
        "list" -> if (args.size !in 2..4) USAGE else null
        else -> if (args.size < 2 || (args[1] in kinds && args.size < 3)) USAGE else null
    }

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        fun str(v: String) = JsonPrimitive(v)
        val (tool, arguments) = when (args.first()) {
            "resolve" -> "infantry_ticket_comment" to mapOf("commentId" to str(args[1]))
            "list" -> "infantry_ticket_comments" to buildMap {
                put("ticketId", str(args[1]))
                args.drop(2).forEach { if (it in kinds) put("kind", str(it)) else put("which", str(it)) }
            }
            else -> {
                val kind = args[1].takeIf { it in kinds }
                "infantry_ticket_comment" to buildMap {
                    put("ticketId", str(args[0]))
                    kind?.let { put("kind", str(it)) }
                    put("body", str(args.drop(if (kind != null) 2 else 1).joinToString(" ")))
                }
            }
        }
        val result = McpPluginTools(getenv).call(stream, tool, JsonObject(arguments))
        if (result.text.startsWith("Unknown tool:")) {
            err.appendLine("error: this daemon has no ticket comment tools (Infantry_Board's daemon half is not loaded)")
            return@runBlocking 1
        }
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
