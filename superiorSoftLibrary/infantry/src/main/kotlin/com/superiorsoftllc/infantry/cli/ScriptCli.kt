// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SCRIPT-5: `infantry script hook list | add <on> <script> [--payload <args json>|-] | remove <id>`: one call of the Scripting plugin's
 * `infantry_script_hook` tool (`plugin_tool_call`), so the CLI and MCP share one answer and one gate. Name-neutral about what a hook
 * does: `<on>` is `"lane enter <lane>"`, `"lane exit <lane>"` or a named event. Open to every role.
 */
internal object ScriptCli {
    const val USAGE = "usage: infantry script hook list | add <on> <script> [--payload <args json>|-] | remove <id> | save <name> [--file <path> | --body -] | list | delete <name>   (<on>: \"lane enter done\", \"lane exit in_progress\" or an event name like ticket.commented)"

    fun usageError(args: List<String>): String? = when {
        args.size == 2 && args[0] == "save" -> null
        args.size == 1 && args[0] == "list" -> null
        args.size == 2 && args[0] == "delete" -> null
        args.size >= 2 && args[0] == "hook" && args[1] == "list" && args.size == 2 -> null
        args.size == 4 && args[0] == "hook" && args[1] == "add" -> null
        args.size == 3 && args[0] == "hook" && args[1] == "remove" -> null
        else -> USAGE
    }

    fun run(stream: FrameStream, args: List<String>, payload: String?, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        // SCRIPT-SAVE-1: save / list / delete are one call each of infantry_script_save | _list | _delete.
        if (args[0] != "hook") {
            if (args[0] == "save" && payload == null) { err.appendLine("infantry script save <name> needs the source: --file <path> or --body -"); return@runBlocking 2 }
            val tool = "infantry_script_${args[0]}"
            val a = buildMap<String, JsonPrimitive> {
                if (args.size > 1) put("name", JsonPrimitive(args[1]))
                if (args[0] == "save") put("source", JsonPrimitive(payload!!))
            }
            val res = McpPluginTools(getenv).call(stream, tool, JsonObject(a))
            (if (res.isError) err else out).appendLine(res.text)
            return@runBlocking if (res.isError) 1 else 0
        }
        val arguments = buildMap<String, JsonPrimitive> {
            put("action", JsonPrimitive(args[1]))
            when (args[1]) {
                "add" -> { put("on", JsonPrimitive(args[2])); put("script", JsonPrimitive(args[3])); payload?.let { put("args", JsonPrimitive(it)) } }
                "remove" -> put("id", JsonPrimitive(args[2]))
            }
        }
        val result = McpPluginTools(getenv).call(stream, "infantry_script_hook", JsonObject(arguments))
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
