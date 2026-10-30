package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 3): the daemon plugins' tools, served by this MCP server next to its
 * own. `tools/list` appends the daemon's `plugin_tools` (none when the daemon has none, or is unreachable); `tools/call`
 * of such a name is one `plugin_tool_call`, answered by `plugin_tool_result {text, isError}`. The daemon gates the call by
 * the tool's own policy action and this connection's token, so this side holds no rule of its own.
 */
internal class McpPluginTools {

    /** The daemon's plugin tools, as MCP descriptors (the daemon's bookkeeping keys stripped). */
    suspend fun descriptors(stream: FrameStream): JsonArray {
        val answer = stream.request(Envelope(PLUGIN_TOOLS), PLUGIN_TOOLS, PLUGIN_TOOLS, idempotent = true)
        val all = json.parseToJsonElement(answer.data) as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(all.mapNotNull { (it as? JsonObject)?.let { d -> JsonObject(d - setOf("module", "action")) } })
    }

    suspend fun call(stream: FrameStream, name: String, arguments: JsonObject): ToolResult {
        val payload = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to arguments))
        val answer = stream.request(
            Envelope(PLUGIN_TOOL_CALL, json.encodeToString(JsonObject.serializer(), payload)), PLUGIN_TOOL_RESULT, PLUGIN_TOOL_CALL,
            // A tool may change state; never sent twice.
            idempotent = false, timeoutMs = answerWaitMs(),
        )
        val result = json.parseToJsonElement(answer.data) as? JsonObject ?: return ToolResult("malformed plugin_tool_result", isError = true)
        return ToolResult(
            (result["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    companion object {
        const val PLUGIN_TOOLS = "plugin_tools"
        const val PLUGIN_TOOL_CALL = "plugin_tool_call"
        const val PLUGIN_TOOL_RESULT = "plugin_tool_result"
        /** The daemon disables a tool past limits.plugins.toolTimeoutMs; this waits a quarter longer for its answer. */
        private fun answerWaitMs(): Long = LimitRegistry.fromEnv(LimitRegistry.PLUGIN_TOOL_TIMEOUT_MS, System::getenv).let { it + it / 4 }
        private val json = Json { ignoreUnknownKeys = true }
    }
}
