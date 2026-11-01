package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * FULLSUITE-2: `agenthq tests run|status|stop [projectId]`: one call of the built-in `agenthq_tests` tool (`plugin_tool_call`), so the CLI
 * and MCP share one answer and one gate. The project defaults to the caller's own.
 */
internal object TestsCli {
    const val USAGE = "usage: agenthq tests run|status|stop [projectId]"

    fun usageError(args: List<String>): String? = if (args.size in 1..2 && args[0] in setOf("run", "status", "stop")) null else USAGE

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val arguments = buildMap<String, JsonPrimitive> {
            put("action", JsonPrimitive(args[0]))
            args.getOrNull(1)?.let { put("projectId", JsonPrimitive(it)) }
        }
        val result = McpPluginTools(getenv).call(stream, "agenthq_tests", JsonObject(arguments))
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
