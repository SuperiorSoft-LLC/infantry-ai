package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * HARNESS H8b-2: the inline `OPENCODE_CONFIG_CONTENT` merges the daemon used to make for opencode (MCP-1 `AgentHqMcp.mergeOpenCode`, STALL-2
 * `WatchHooks.appendOpenCodePlugin`), as the plugin's API 2.11 [com.superiorsoftllc.agenthq.harness.api.Harness.mergeConfig]. Merge, never overwrite.
 */
object OpenCodeConfig {
    /** The name of the session's own MCP server (opencode names its tools `<server>_<tool>`). */
    const val SERVER_NAME = "agenthq"

    /** opencode's permission key for every tool of that server. */
    const val TOOL_PATTERN = "${SERVER_NAME}_*"

    private val json = Json { ignoreUnknownKeys = true }

    /** [existing] with [parts] merged in: `mcp.agenthq` + `permission."agenthq_*"`, and the watch plugin in the `plugin` list. */
    fun merge(existing: String?, parts: ConfigParts): String {
        var c = existing ?: "{}"
        parts.mcp?.let { c = mergeMcp(c, it) }
        parts.watchPlugin?.let { c = appendPlugin(c, it) }
        return c
    }

    /**
     * [existing] with `mcp.agenthq` set to [launch] as a local server and `permission."agenthq_*"` = `allow` (every Tool approval mode).
     * Every other key is kept; an `mcp.agenthq` the user already defined is kept as is. The permission rule goes LAST (opencode resolves the
     * last matching pattern); a `permission` given as one string becomes `{"*": <it>, "agenthq_*": "allow"}`.
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
    fun isAgentHqTool(permission: String?): Boolean = permission?.trim()?.lowercase()?.startsWith("${SERVER_NAME}_") == true

    private fun parse(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}
