// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ConfigParts
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * HARNESS H8b-2: the inline `OPENCODE_CONFIG_CONTENT` merges the daemon used to make for opencode (MCP-1 `InfantryMcp.mergeOpenCode`, STALL-2
 * `WatchHooks.appendOpenCodePlugin`), as the plugin's API 2.11 [com.superiorsoftllc.infantry.harness.api.Harness.mergeConfig]. Merge, never overwrite.
 */
object OpenCodeConfig {
    /** The name of the session's own MCP server (opencode names its tools `<server>_<tool>`). */
    const val SERVER_NAME = "infantry"

    /** opencode's permission key for every tool of that server. */
    const val TOOL_PATTERN = "${SERVER_NAME}_*"

    private val json = Json { ignoreUnknownKeys = true }

    /** [existing] with [parts] merged in: `mcp.infantry` + `permission."infantry_*"`, and the watch plugin in the `plugin` list. */
    fun merge(existing: String?, parts: ConfigParts): String {
        var c = existing ?: "{}"
        parts.mcp?.let { c = mergeMcp(c, it) }
        parts.watchPlugin?.let { c = appendPlugin(c, it) }
        return c
    }

    /**
     * [existing] with `mcp.infantry` set to [launch] as a local server and `permission."infantry_*"` = `allow` (every Tool approval mode).
     * Every other key is kept; an `mcp.infantry` the user already defined is kept as is. The permission rule goes LAST (opencode resolves the
     * last matching pattern); a `permission` given as one string becomes `{"*": <it>, "infantry_*": "allow"}`.
     */
    fun mergeMcp(existing: String?, launch: HarnessProcess): String {
        val root = parse(existing) ?: JsonObject(emptyMap())
        val mcp = (root["mcp"] as? JsonObject) ?: JsonObject(emptyMap())
        val mergedMcp = if (SERVER_NAME in mcp) mcp else JsonObject(mcp + (SERVER_NAME to JsonObject(mapOf(
            "type" to JsonPrimitive("local"),
            "command" to JsonArray(launch.command.map(::JsonPrimitive)),
            "environment" to JsonObject(launch.env.mapValues { JsonPrimitive(it.value) }),
            "enabled" to JsonPrimitive(true),
        ))))
        val allow = TOOL_PATTERN to JsonPrimitive("allow")
        val permission: JsonElement = when (val p = root["permission"]) {
            is JsonObject -> JsonObject((p - TOOL_PATTERN) + allow)
            is JsonPrimitive -> JsonObject(mapOf("*" to p, allow))
            else -> JsonObject(mapOf(allow))
        }
        return JsonObject(root + ("mcp" to mergedMcp) + ("permission" to permission)).toString()
    }

    /** [config] with [plugin] appended to its `plugin` list (a local plugin is named by its file URL). */
    fun appendPlugin(config: String, plugin: File): String {
        val root = parse(config) ?: JsonObject(emptyMap())
        val spec = plugin.absoluteFile.toPath().toUri().toString()
        val list = (root["plugin"] as? JsonArray).orEmpty().filterNot { (it as? JsonPrimitive)?.contentOrNull == spec }
        return JsonObject(root + ("plugin" to JsonArray(list + JsonPrimitive(spec)))).toString()
    }

    /** Whether an opencode permission ask names one of the session's own MCP tools (allowed in every mode, never asked). */
    fun isInfantryTool(permission: String?): Boolean = permission?.trim()?.lowercase()?.startsWith("${SERVER_NAME}_") == true

    private fun parse(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}

/**
 * MCP-OPENCODE-1: the plan's non-infantry servers (Infantry-owned, plugin) become `mcp.<name>` entries; every opencode user server in [disable]
 * is switched off with `enabled:false` (opencode's documented override), so only infantry and the granted servers load. Merged, no file edits.
 */
fun OpenCodeConfig.mergePlan(existing: String, plan: McpPlan, disable: Set<String>): String {
    val root = (runCatching { Json.parseToJsonElement(existing) as? JsonObject }.getOrNull()) ?: JsonObject(emptyMap())
    val mcp = ((root["mcp"] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
    val perms = mutableMapOf<String, JsonElement>()
    for (spec in plan.delivered) {
        if (spec.name == SERVER_NAME || spec.source == McpSource.HARNESS || spec.name in mcp) continue
        mcp[spec.name] = when (val t = spec.transport) {
            is McpTransport.Stdio -> JsonObject(mapOf(
                "type" to JsonPrimitive("local"), "command" to JsonArray(t.command.map(::JsonPrimitive)),
                "environment" to JsonObject(t.env.mapValues { JsonPrimitive(it.value) }), "enabled" to JsonPrimitive(true)))
            is McpTransport.Http -> JsonObject(buildMap {
                put("type", JsonPrimitive("remote")); put("url", JsonPrimitive(t.url)); put("enabled", JsonPrimitive(true))
                if (t.headers.isNotEmpty()) put("headers", JsonObject(t.headers.mapValues { JsonPrimitive(it.value) }))
            })
        }
    }
    for (name in disable) if (name != SERVER_NAME) {
        val prior = mcp[name] as? JsonObject
        mcp[name] = JsonObject((prior ?: JsonObject(emptyMap())) + ("enabled" to JsonPrimitive(false)))
    }
    return JsonObject(root + ("mcp" to JsonObject(mcp))).toString()
}
