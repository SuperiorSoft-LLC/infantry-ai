package com.superiorsoftllc.agenthq.cli.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * RUNTIME-LOAD-7: the guardrail values of one `agenthq mcp serve` process (docs/RUNTIME-LOAD.md section 13). They replace the old
 * `McpGuardrails`: nothing is enforced here any more, the host's spawn service enforces the daemon's limits and only NARROWS them with
 * these (min of the limits, max of the depth). [clientKey] is one random id per process, so an owner connection without a session
 * still has its open spawns counted.
 */
internal data class McpNarrowing(
    /** Null = no claim: the host knows the caller's depth itself. */
    val depth: Int? = null,
    /** Null = follow the daemon setting `limits.concurrency.maxDepth`. */
    val maxDepth: Int? = null,
    /** Null = follow the daemon setting `limits.concurrency.mcpMaxSpawns`. */
    val maxSpawns: Int? = null,
    val clientKey: String? = null,
) {
    /** The keys of the `plugin_tool_call` client object. */
    fun toClient(): Map<String, JsonElement> = listOfNotNull(
        depth?.let { "depth" to JsonPrimitive(it) },
        maxDepth?.let { "maxDepth" to JsonPrimitive(it) },
        maxSpawns?.let { "maxSpawns" to JsonPrimitive(it) },
        clientKey?.let { "clientKey" to JsonPrimitive(it) },
    ).toMap()

    companion object {
        /** Explicit depth of the server process (overridden by `--mcp-depth`). */
        const val DEPTH_ENV = "AGENTHQ_MCP_DEPTH"
    }
}
