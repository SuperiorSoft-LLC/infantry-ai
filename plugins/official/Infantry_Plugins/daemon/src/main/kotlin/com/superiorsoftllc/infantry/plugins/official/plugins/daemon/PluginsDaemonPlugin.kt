// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.plugins.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.plugins.CatalogSummary
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * PLUGIN-MCP-1: the agent tools over the plugin manager. The first four only read (`api.plugins`); `infantry_plugin_request`
 * raises an owner question and installs nothing itself: the daemon installs only on the owner's yes. Not on the core built-in list.
 */
class PluginsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        for (tool in tools { api.plugins }) api.tools.register(tool)
        // PLUGIN-BRIDGES-1: the daemon-side capability proof. `plugins.status` (module.json `capabilities`) answers a script's
        // `ih.call("plugins.status", {id=...})`, an agent's infantry_capability_call and another plugin's capabilities.call alike.
        api.capabilities.handle(STATUS_CAPABILITY) { call -> status({ api.plugins }, call.args) }
    }

    companion object {
        const val STATUS_CAPABILITY = "plugins.status"

        /** One installed plugin's state for [STATUS_CAPABILITY]: `{installed:false}` when it is not on this host. */
        fun status(reader: () -> PluginCatalogReader, args: JsonObject): JsonObject {
            val id = args.str("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("id is required")
            val s = reader().status(id) ?: return buildJsonObject { put("id", id); put("installed", false) }
            return JsonObject(summary(s) + ("installed" to JsonPrimitive(true)))
        }

        fun tools(reader: () -> PluginCatalogReader): List<DaemonTool> = listOf(
            PluginTool("infantry_plugin_list", "Every plugin on this host: version, class, tier, enabled, grants, licence, update offer.", emptyMap(), emptyList(), true) { _, _ ->
                JsonArray(reader().list().map(::summary)).toString()
            },
            PluginTool("infantry_plugin_search", "Search the plugin catalogs by text (id, description or tag) and optional tag.", mapOf("query" to "Text to find.", "tag" to "A tag every hit must carry."), emptyList(), true) { a, _ ->
                JsonArray(reader().search(a.str("query").orEmpty(), a.str("tag").orEmpty()).map(::catalog)).toString()
            },
            PluginTool("infantry_plugin_show", "Check a plugin reference ([source:]id[@version], a path or a URL) and show its class, signer, licence and requested grants. Installs nothing.", mapOf("ref" to "The plugin reference."), listOf("ref"), true) { a, _ ->
                val r = reader().show(a.str("ref") ?: throw IllegalArgumentException("ref is required"))
                r.problem?.let { throw IllegalArgumentException(it) }
                buildJsonObject {
                    put("id", r.id); put("version", r.version); put("class", r.cls); put("tier", r.tier)
                    put("license", r.license); put("signer", r.signer); put("source", r.source)
                    put("grants", strings(r.grants)); put("warnings", strings(r.warnings))
                }.toString()
            },
            PluginTool("infantry_plugin_status", "One installed plugin's state (version, enabled, grants, update offer).", mapOf("id" to "The plugin id (Vendor_Module)."), listOf("id"), true) { a, _ ->
                val id = a.str("id") ?: throw IllegalArgumentException("id is required")
                reader().status(id)?.let { summary(it).toString() } ?: throw IllegalArgumentException("no plugin '$id' on this host")
            },
            PluginTool(
                "infantry_plugin_list_import",
                "Import a plugin list (a JSON file path or URL listing plugin repos) as a community catalog source; its plugins then show in infantry_plugin_search. Installs nothing.",
                mapOf("location" to "The list's file path or http(s)/file URL."), listOf("location"), false,
            ) { a, _ ->
                val r = reader().importList(a.str("location") ?: throw IllegalArgumentException("location is required"))
                buildJsonObject { put("message", r) }.toString()
            },
            PluginTool(
                "infantry_plugin_request",
                "Ask the owner to install a plugin with exactly the grants it requests. Returns the owner question id at once; nothing is installed until the owner answers \"Install with these grants\". You never install or grant directly.",
                mapOf("ref" to "The plugin reference ([source:]id[@version]).", "reason" to "Why the plugin is needed."), listOf("ref", "reason"), false,
            ) { a, call ->
                val ref = a.str("ref") ?: throw IllegalArgumentException("ref is required")
                val r = reader().request(ref, a.str("reason").orEmpty(), call.clientSessionId)
                val id = r.questionId ?: throw IllegalArgumentException(r.message)
                buildJsonObject { put("questionId", id); put("message", r.message) }.toString()
            },
        )

        private fun strings(l: List<String>) = buildJsonArray { l.forEach { add(JsonPrimitive(it)) } }
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun summary(s: PluginSummary): JsonObject = buildJsonObject {
            put("id", s.id); put("version", s.version); put("class", s.cls); put("tier", s.tier); put("bundled", s.bundled); put("enabled", s.enabled)
            put("grants", strings(s.grants)); put("requested", strings(s.requested)); put("license", s.license); put("source", s.source)
            put("yanked", s.yanked); put("updateTo", s.updateTo)
        }

        private fun catalog(c: CatalogSummary): JsonObject = buildJsonObject {
            put("id", c.id); put("description", c.description); put("tags", strings(c.tags)); put("class", c.cls); put("source", c.source)
            put("license", c.license); put("latest", c.latest); put("grants", strings(c.grants)); put("signer", c.signer)
            put("name", c.name); put("repo", c.repo); put("list", c.listName)
        }
    }
}

private class PluginTool(
    override val name: String,
    override val description: String,
    props: Map<String, String>,
    required: List<String>,
    readOnly: Boolean,
    private val run: (JsonObject, ToolCall) -> String,
) : DaemonTool {
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, d) -> put(k, buildJsonObject { put("type", "string"); put("description", d) }) } })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    override val annotations: JsonObject = buildJsonObject { put("readOnlyHint", readOnly); put("destructiveHint", false); put("openWorldHint", false) }

    override fun call(call: ToolCall): ToolResult = try {
        ToolResult(run(call.arguments, call))
    } catch (e: IllegalArgumentException) {
        ToolResult("$name: ${e.message}", isError = true)
    } catch (e: Exception) {
        ToolResult("$name failed: ${e.message ?: e::class.simpleName}", isError = true)
    }
}
