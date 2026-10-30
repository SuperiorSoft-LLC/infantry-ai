package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.4): `agenthq agent-def list | show <id> | enable <id> | disable <id>`. Each
 * is one call of the Infantry_Agents tool of the same purpose (`plugin_tool_call`), so the CLI and MCP share one answer and one
 * gate. `agents` stays the live-sessions command; `agent-def` is the registry of definitions sessions are started from.
 */
internal object AgentDefCli {
    private val TOOLS = mapOf(
        "list" to "agenthq_agent_list",
        "show" to "agenthq_agent_get",
        "enable" to "agenthq_agent_enable",
        "disable" to "agenthq_agent_disable",
    )

    const val USAGE = "agenthq agent-def list | show <id> | enable <id> | disable <id>"

    fun usageError(args: List<String>): String? {
        val sub = args.firstOrNull() ?: return "agent-def needs a subcommand: $USAGE"
        if (sub !in TOOLS) return "unknown agent-def subcommand '$sub': $USAGE"
        val wanted = if (sub == "list") 1 else 2
        return if (args.size != wanted) "agent-def $sub ${if (wanted == 1) "takes no arguments" else "takes one agent id"}: $USAGE" else null
    }

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val tool = TOOLS.getValue(args.first())
        val arguments = JsonObject(args.getOrNull(1)?.let { mapOf("id" to JsonPrimitive(it.trim())) }.orEmpty())
        val result = McpPluginTools(getenv).call(stream, tool, arguments)
        if (result.text.startsWith("Unknown tool:")) {
            err.appendLine("error: this daemon has no agent registry tools (Infantry_Agents' daemon half is not loaded)")
            return@runBlocking 1
        }
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
