// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.settings.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.project.ProjectConfigPatch
import com.superiorsoftllc.infantry.daemon.api.project.ProjectConfigView
import com.superiorsoftllc.infantry.daemon.api.project.RoutingRuleRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-3 (docs/RUNTIME-LOAD.md, F4): Infantry_Settings' daemon half. LANE-CAPS' project config tools (per-role caps on
 * concurrent agents, autoRemoveDoneWorkers, task routing rules, experiment switches) were built into the CLI's MCP server; now
 * they send the daemon's `project_config` frame on the caller's connection. Same names, schemas, answers and gating
 * (`projects.save`, the action of the `project_config` frame, for the read as well, as before).
 */
class SettingsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(ProjectConfigGetTool)
        api.tools.register(ProjectConfigSetTool)
        api.tools.register(SettingsGetTool)
        api.tools.register(SettingsSetTool)
    }
}

private val json = Json { ignoreUnknownKeys = true }
private const val ANSWER_TIMEOUT_MS = 45_000L

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun refused(text: String) = ToolResult(text, isError = true)

private fun shape(readOnly: Boolean, props: List<Pair<String, String>>, required: List<String>): Pair<JsonObject, JsonObject> = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
    })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
} to buildJsonObject {
    put("readOnlyHint", readOnly)
    put("destructiveHint", false)
    put("idempotentHint", true)
    put("openWorldHint", false)
}

private val PROJECT = "projectId" to "The project id."

private fun ToolCall.send(patch: ProjectConfigPatch): ToolResult = try {
    val reply = request("project_config", json.encodeToString(ProjectConfigPatch.serializer(), patch), timeoutMs = ANSWER_TIMEOUT_MS)
    when (reply.type) {
        "project" -> {
            val project = json.decodeFromString(ProjectConfigView.serializer(), reply.data)
            ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("projectId", project.id)
                put("laneCaps", buildJsonObject { project.laneCaps.forEach { (role, cap) -> put(role, cap) } })
                put("autoRemoveDoneWorkers", project.autoRemoveDoneWorkers)
                put("routingRules", json.encodeToJsonElement(ListSerializer(RoutingRuleRecord.serializer()), project.routingRules))
                put("experiments", buildJsonObject { project.experiments.forEach { (k, v) -> put(k, v) } })
                put("data", buildJsonObject { project.data.forEach { (k, v) -> put(k, v) } })
                put("note", "routingRules: first match wins, the last is the default row; a ticket's own provider/model pin always wins. max concurrent agents per role; a role not listed runs 1. autoRemoveDoneWorkers: a done ticket's worker session is ended and removed")
            }))
        }
        "error" -> refused(reply.data.ifBlank { "project_config failed" })
        else -> refused("project_config: unexpected answer ${reply.type}")
    }
} catch (e: Exception) {
    refused(e.message ?: "project_config failed")
}

object ProjectConfigGetTool : DaemonTool {
    const val NAME = "infantry_project_config_get"
    private val shape = shape(true, listOf(PROJECT), listOf("projectId"))
    override val name = NAME
    override val description = "Read a project's per-role caps on concurrent agents (a role not listed runs 1) and its task routing rules."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val projectId = call.arguments.string("projectId") ?: return refused("$NAME needs a projectId")
        return call.send(ProjectConfigPatch(projectId))
    }
}

object ProjectConfigSetTool : DaemonTool {
    const val NAME = "infantry_project_config_set"
    private val shape = shape(
        false,
        listOf(
            PROJECT, "role" to "The role id, e.g. backend (with cap).", "cap" to "The most concurrent agents of the role, 1 or more.",
            "autoRemoveDoneWorkers" to "true or false: end and remove a done ticket's worker session.",
            "routingRules" to "A JSON array (as text) of rules, in order, first match wins: {name, weights?: [1..5], ticketTypes?: " +
                "[type], role?, provider, model, effort?, setRole?}. The last rule must be the default row (no weights, " +
                "ticketTypes or role). A provider/model its harness does not list is refused; Opus only as pi + " +
                "anthropic/claude-opus-5-5. \"[]\" clears the rules.",
            "experiment" to "an experiment switch name (a name the daemon knows); needs value.",
            "value" to "on or off, for experiment; or the string to store, for dataKey.",
            "dataKey" to "a generic project data key, e.g. build.root (letters, digits, . _ -; max 80); needs value to set it.",
            "dataUnset" to "a generic project data key to remove.",
        ),
        listOf("projectId"),
    )
    override val name = NAME
    override val description =
        "Set how many agents of one role may run at once in a project (1 = the default; lowering it never stops a running " +
            "agent), and/or autoRemoveDoneWorkers: when true, a ticket moved to done ends and removes its worker session " +
            "once the worker's turn is over (default false), and/or routingRules: REPLACE the project's task routing rules."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val arguments = call.arguments
        val projectId = arguments.string("projectId") ?: return refused("$NAME needs a projectId")
        // BASICS-KILL-1 (1.118.0): autoRemoveDoneWorkers alone is a valid change; a role and a cap still go together.
        val autoRaw = arguments.string("autoRemoveDoneWorkers")?.trim()?.lowercase()
        val autoRemove = when (autoRaw) {
            null, "" -> null
            "true" -> true
            "false" -> false
            else -> return refused("$NAME: autoRemoveDoneWorkers must be true or false, was '${autoRaw.take(20)}'")
        }
        // PROJECT-DATA-1 (1.129.0): `dataKey` with `value` stores generic project data; `dataUnset` removes a key.
        arguments.string("dataUnset")?.let { return call.send(ProjectConfigPatch(projectId, unsetData = listOf(it))) }
        arguments.string("dataKey")?.let { key ->
            val value = arguments.string("value") ?: return refused("$NAME: dataKey needs a value")
            return call.send(ProjectConfigPatch(projectId, data = mapOf(key to value)))
        }
        // EXPERIMENTS-1 (1.126.0): `experiment` (a key) with `value` (on/off) is a change of its own.
        arguments.string("experiment")?.let { key ->
            val value = arguments.string("value") ?: return refused("$NAME: experiment needs a value of on or off")
            return call.send(ProjectConfigPatch(projectId, experiments = mapOf(key to value)))
        }
        // ROUTING-1 (1.128.0): routingRules, a JSON array (as text), REPLACES the project's rules; "[]" clears them.
        val rulesRaw = arguments.string("routingRules")?.trim()?.takeIf { it.isNotEmpty() }
        val rules = rulesRaw?.let {
            try {
                json.decodeFromString(ListSerializer(RoutingRuleRecord.serializer()), it)
            } catch (e: Exception) {
                return refused("$NAME: routingRules must be a JSON array of {name, weights?, ticketTypes?, role?, provider, model, effort?, setRole?}: ${e.message?.take(200)}")
            }
        }
        val role = arguments.string("role")
        if (role == null && rules != null && arguments.string("cap") == null) {
            return call.send(ProjectConfigPatch(projectId, autoRemoveDoneWorkers = autoRemove, routingRules = rules))
        }
        if (role == null && autoRemove != null && arguments.string("cap") == null) {
            return call.send(ProjectConfigPatch(projectId, autoRemoveDoneWorkers = autoRemove))
        }
        role ?: return refused("$NAME needs a role (with a cap), autoRemoveDoneWorkers, or routingRules")
        val cap = arguments.string("cap")?.toIntOrNull()?.takeIf { it >= 1 } ?: return refused("$NAME needs a cap of 1 or more")
        return call.send(ProjectConfigPatch(projectId, mapOf(role to cap), autoRemove, rules))
    }
}

/** SETTINGS-SCOPES-1: send a `settings_get` / `settings_set` frame; the answer is the daemon's JSON text. */
private fun ToolCall.settingsFrame(type: String, data: JsonObject): ToolResult = try {
    val reply = request(type, data.toString(), timeoutMs = ANSWER_TIMEOUT_MS)
    when (reply.type) {
        "settings" -> ToolResult(reply.data)
        "error" -> refused(reply.data.ifBlank { "$type failed" })
        else -> refused("$type: unexpected answer ${reply.type}")
    }
} catch (e: Exception) {
    refused(e.message ?: "$type failed")
}

private val SCOPE_PROPS = listOf(
    "scope" to "app (default) | project | role | session. Resolution: session > role > project > app > default.",
    "project" to "The project id (scope project, or the project of a role).",
    "role" to "The role id (scope role, with project).",
    "session" to "The session id (scope session).",
)

object SettingsGetTool : DaemonTool {
    const val NAME = "infantry_settings_get"
    private val shape = shape(true, listOf("key" to "The setting key, e.g. related.brief.", "explain" to "true: also print which scope wins and every layer.") + SCOPE_PROPS.drop(1), listOf("key"))
    override val name = NAME
    override val description = "Read a setting's effective value and the scope it comes from (session > role > project > app > default) for the given project, role and session."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val key = call.arguments.string("key") ?: return refused("$NAME needs a key")
        return call.settingsFrame("settings_get", buildJsonObject {
            put("key", key)
            listOf("project", "role", "session", "explain").forEach { f -> call.arguments.string(f)?.let { put(f, it) } }
        })
    }
}

object SettingsSetTool : DaemonTool {
    const val NAME = "infantry_settings_set"
    private val shape = shape(false, listOf("key" to "The setting key.", "value" to "The value as JSON text (true, 5, \"notify\", [..]); null clears it at a non-app scope.") + SCOPE_PROPS, listOf("key", "value"))
    override val name = NAME
    override val description = "Set a setting at a scope (app, project, role or session) with the target ids; a scope the setting does not allow is refused. Answers with the effective value and its scope."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val key = call.arguments.string("key") ?: return refused("$NAME needs a key")
        val raw = call.arguments.string("value") ?: return refused("$NAME needs a value")
        val value = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: JsonPrimitive(raw)
        return call.settingsFrame("settings_set", buildJsonObject {
            put("key", key)
            put("value", value)
            listOf("scope", "project", "role", "session").forEach { f -> call.arguments.string(f)?.let { put(f, it) } }
        })
    }
}
