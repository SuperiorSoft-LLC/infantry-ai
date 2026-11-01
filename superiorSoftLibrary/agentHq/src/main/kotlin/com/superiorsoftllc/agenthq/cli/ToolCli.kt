package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * SCRIPT-4: `agenthq tool call <tool> [--payload <json object>|-]`: one call of ANY registered daemon tool (the same `plugin_tool_call`
 * an agent's MCP client sends, so the same gates), for tools that have no verb of their own. Name-neutral: it knows no tool.
 */
internal object ToolCli {
    const val USAGE = "usage: agenthq tool call <tool> [--payload <json object>|-]"

    fun usageError(args: List<String>): String? = if (args.size == 2 && args[0] == "call") null else USAGE

    fun run(stream: FrameStream, args: List<String>, payload: String?, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val arguments = try {
            if (payload.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(payload) as? JsonObject ?: throw IllegalArgumentException("not an object")
        } catch (e: Exception) {
            err.appendLine("--payload must be a JSON object (${e.message?.take(100)})")
            return 1
        }
        val result = runBlocking { McpPluginTools(getenv).call(stream, args[1], arguments) }
        (if (result.isError) err else out).appendLine(result.text)
        return if (result.isError) 1 else 0
    }
}
