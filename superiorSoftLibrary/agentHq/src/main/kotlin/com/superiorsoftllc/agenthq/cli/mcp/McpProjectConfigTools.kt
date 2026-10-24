package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * LANE-CAPS (contract 1.111.0): a project's per-role cap on concurrent agents over MCP, on the daemon's `project_config`
 * frame. Lowering a cap never stops a running agent; it only stops new ones from being added.
 */
internal class McpProjectConfigTools {

    suspend fun get(stream: FrameStream, arguments: JsonObject): ToolResult {
        val projectId = arguments.string("projectId") ?: return refused("$GET needs a projectId")
        return request(stream, ProjectConfigRequest(projectId))
    }

    suspend fun set(stream: FrameStream, arguments: JsonObject): ToolResult {
        val projectId = arguments.string("projectId") ?: return refused("$SET needs a projectId")
        // BASICS-KILL-1 (1.118.0): autoRemoveDoneWorkers alone is a valid change; a role and a cap still go together.
        val autoRaw = arguments.string("autoRemoveDoneWorkers")?.trim()?.lowercase()
        val autoRemove = when (autoRaw) {
            null, "" -> null
            "true" -> true
            "false" -> false
            else -> return refused("$SET: autoRemoveDoneWorkers must be true or false, was '${autoRaw.take(20)}'")
        }
        // EXPERIMENTS-1 (1.126.0): `experiment` (a key) with `value` (on/off) is a change of its own.
        arguments.string("experiment")?.let { key ->
            val value = arguments.string("value") ?: return refused("$SET: experiment needs a value of on or off")
            return request(stream, ProjectConfigRequest(projectId, experiments = mapOf(key to value)))
        }
        val role = arguments.string("role")
        if (role == null && autoRemove != null && arguments.string("cap") == null) {
            return request(stream, ProjectConfigRequest(projectId, autoRemoveDoneWorkers = autoRemove))
        }
        role ?: return refused("$SET needs a role (with a cap), or autoRemoveDoneWorkers")
        val cap = arguments.string("cap")?.toIntOrNull()?.takeIf { it >= 1 } ?: return refused("$SET needs a cap of 1 or more")
        return request(stream, ProjectConfigRequest(projectId, mapOf(role to cap), autoRemove))
    }

    private suspend fun request(stream: FrameStream, request: ProjectConfigRequest): ToolResult = try {
        val payload = json.encodeToString(ProjectConfigRequest.serializer(), request)
        val answer = stream.request(Envelope(Envelope.PROJECT_CONFIG, payload), Envelope.PROJECT, "project_config", idempotent = true)
        val project = json.decodeFromString(Project.serializer(), answer.data)
        ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("projectId", project.id)
            put("laneCaps", buildJsonObject { project.laneCaps.forEach { (role, cap) -> put(role, cap) } })
            put("autoRemoveDoneWorkers", project.autoRemoveDoneWorkers)
            put("experiments", buildJsonObject { project.experiments.forEach { (k, v) -> put(k, v) } })
            put("note", "max concurrent agents per role; a role not listed runs 1. autoRemoveDoneWorkers: a done ticket's worker session is ended and removed")
        }))
    } catch (e: Exception) {
        refused(e.message ?: "project_config failed")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val GET = "agenthq_project_config_get"
        const val SET = "agenthq_project_config_set"
        val NAMES = listOf(GET, SET)

        private val json = Json { ignoreUnknownKeys = true }

        private fun tool(name: String, description: String, readOnly: Boolean, props: List<Pair<String, String>>, required: List<String>) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
                    })
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", readOnly)
                    put("destructiveHint", false)
                    put("idempotentHint", true)
                    put("openWorldHint", false)
                })
            }

        private val PROJECT = "projectId" to "The project id."

        fun descriptors() = buildJsonArray {
            add(tool(GET, "Read a project's per-role caps on concurrent agents (a role not listed runs 1).", true, listOf(PROJECT), listOf("projectId")))
            add(tool(
                SET,
                "Set how many agents of one role may run at once in a project (1 = the default; lowering it never stops a running " +
                    "agent), and/or autoRemoveDoneWorkers: when true, a ticket moved to done ends and removes its worker session " +
                    "once the worker's turn is over (default false).",
                false,
                listOf(
                    PROJECT, "role" to "The role id, e.g. backend (with cap).", "cap" to "The most concurrent agents of the role, 1 or more.",
                    "autoRemoveDoneWorkers" to "true or false: end and remove a done ticket's worker session.",
                    "experiment" to "an experiment switch name (a name the daemon knows); needs value.",
                    "value" to "on or off, for experiment.",
                ),
                listOf("projectId"),
            ))
        }
    }
}
