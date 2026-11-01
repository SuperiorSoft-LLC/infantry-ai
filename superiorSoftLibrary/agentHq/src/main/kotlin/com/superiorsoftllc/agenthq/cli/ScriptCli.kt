package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SCRIPT-5: `agenthq script hook list | add <on> <script> [--payload <args json>|-] | remove <id>`: one call of the Scripting plugin's
 * `agenthq_script_hook` tool (`plugin_tool_call`), so the CLI and MCP share one answer and one gate. Name-neutral about what a hook
 * does: `<on>` is `"lane enter <lane>"`, `"lane exit <lane>"` or a named event. Open to every role.
 */
internal object ScriptCli {
    const val USAGE = "usage: agenthq script hook list | add <on> <script> [--payload <args json>|-] | remove <id>   (<on>: \"lane enter done\", \"lane exit in_progress\" or an event name like ticket.commented)"

    fun usageError(args: List<String>): String? = when {
        args.size >= 2 && args[0] == "hook" && args[1] == "list" && args.size == 2 -> null
        args.size == 4 && args[0] == "hook" && args[1] == "add" -> null
        args.size == 3 && args[0] == "hook" && args[1] == "remove" -> null
        else -> USAGE
    }

    fun run(stream: FrameStream, args: List<String>, payload: String?, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val arguments = buildMap<String, JsonPrimitive> {
            put("action", JsonPrimitive(args[1]))
            when (args[1]) {
                "add" -> { put("on", JsonPrimitive(args[2])); put("script", JsonPrimitive(args[3])); payload?.let { put("args", JsonPrimitive(it)) } }
                "remove" -> put("id", JsonPrimitive(args[2]))
            }
        }
        val result = McpPluginTools(getenv).call(stream, "agenthq_script_hook", JsonObject(arguments))
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
