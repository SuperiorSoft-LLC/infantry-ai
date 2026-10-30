package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-4 (F5): `agenthq_epic_base`, moved out of the CLI's MCP server (`McpTicketTools.epicBase`, BASE-1c). Same name,
 * descriptor, answer and refusal texts. It sends the very frame the old tool sent (`epic_base`) on the caller's own connection
 * ([ToolCall.request]), so the daemon runs the same set-time branch check and the same gates; a refusal is the frame's error text.
 */
object EpicBaseTool : DaemonTool {
    const val EPIC_BASE = "agenthq_epic_base"
    private const val FRAME = "epic_base"
    private const val EPIC = "epic"
    private const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    override val name = EPIC_BASE
    override val description =
        "Read, set or clear ONE epic's base branch: new tickets in the epic cut their branch from it, " +
            "overriding the project's base. A branch that does not resolve where tickets are cut is refused " +
            "with the reason. Tickets already started keep their recorded base."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("epicId", ToolArgs.prop("Full epic id."))
            put("branch", ToolArgs.prop("Branch to set; omit (with clear unset) to read."))
            put("clear", ToolArgs.boolProp("true removes the setting; the epic's tickets fall back to the project's base."))
        })
        put("required", buildJsonArray { add(JsonPrimitive("epicId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val epicId = ToolArgs.string(args, "epicId") ?: return ToolResult("$EPIC_BASE needs an epicId", isError = true)
        val clear = (args["clear"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.trim() == "true") } ?: false
        // The daemon's EpicBaseRequest as the old tool encoded it (defaults and nulls left out).
        val request = buildJsonObject {
            put("epicId", epicId)
            ToolArgs.string(args, "branch")?.let { put("branch", it) }
            if (clear) put("clear", true)
        }
        val reply = try {
            call.request(FRAME, json.encodeToString(JsonObject.serializer(), request))
        } catch (e: Exception) {
            return ToolResult("$EPIC_BASE refused: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        if (reply.type == ERROR) return ToolResult("$EPIC_BASE refused: ${reply.data.ifBlank { "$FRAME failed" }}", isError = true)
        if (reply.type != EPIC) return ToolResult("$EPIC_BASE: malformed epic answer from the daemon: unexpected ${reply.type}", isError = true)
        val epic = runCatching { json.parseToJsonElement(reply.data) as JsonObject }
            .getOrElse { return ToolResult("$EPIC_BASE: malformed epic answer from the daemon: ${it.message}", isError = true) }
        fun field(key: String) = (epic[key] as? JsonPrimitive)?.contentOrNull
        val id = field("id") ?: return ToolResult("$EPIC_BASE: malformed epic answer from the daemon: no id", isError = true)
        return ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("epicId", id)
            put("name", field("name").orEmpty())
            put("projectId", field("projectId").orEmpty())
            put("baseBranch", field("baseBranch").orEmpty())
        }))
    }
}
