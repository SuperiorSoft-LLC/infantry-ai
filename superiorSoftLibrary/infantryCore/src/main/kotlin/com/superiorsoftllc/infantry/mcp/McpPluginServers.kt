// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.protocol.McpServerInput

/**
 * MCP-PLUGIN-1: the MCP servers plugins declare in module.json `daemon.mcpServers`. The host starts nothing: a declared server is only
 * listed in the registry (source PLUGIN) and reaches an agent through a grant with a purpose, like any other (walled by default).
 * [validate] is the schema a declaration must meet; [declare] refuses a bad or clashing one with a clear error.
 */
class McpPluginServers {
    private val byPlugin = LinkedHashMap<String, List<McpServerInput>>()

    /** Refuse the declaration of [plugin] when it breaks the schema or clashes by name with another plugin's server. */
    @Synchronized
    fun declare(plugin: String, servers: List<McpServerInput>) {
        validate(plugin, servers)
        servers.forEach { s ->
            byPlugin.entries.firstOrNull { it.key != plugin && it.value.any { o -> o.name == s.name } }?.let {
                throw IllegalArgumentException("MCP server '${s.name}' of $plugin is already declared by ${it.key}")
            }
        }
        if (servers.isEmpty()) byPlugin.remove(plugin) else byPlugin[plugin] = servers
    }

    /** The plugin left (unloaded, disabled): its servers leave the registry. */
    @Synchronized fun drop(plugin: String) { byPlugin.remove(plugin) }

    /** Every declared server as a spec, secrets resolved through [secret]; one whose secret is not set is left out until it is. */
    @Synchronized
    fun specs(secret: (String, String?) -> String?): List<McpServerSpec> = byPlugin.values.flatten().mapNotNull { s ->
        McpOwnedServers.toSpec(OwnedServer(s, null), secret).getOrNull()?.copy(source = McpSource.PLUGIN, scope = null)
    }

    /** Who declared [name], or null. */
    @Synchronized fun owner(name: String): String? = byPlugin.entries.firstOrNull { e -> e.value.any { it.name == name } }?.key

    companion object {
        /** A capability tag: lower case words, e.g. "browser". */
        val TAG = Regex("[a-z][a-z0-9_.-]{0,31}")

        /** The schema: what an owned server must meet, plus unique names and at least one declared capability tag, all prefixed with [plugin] in the error. */
        fun validate(plugin: String, servers: List<McpServerInput>) {
            fun bad(why: String): Nothing = throw IllegalArgumentException("daemon.mcpServers of $plugin: $why")
            servers.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let { bad("server name '${it.key}' is declared twice") }
            for (s in servers) {
                runCatching { McpOwnedServers.validate(s) }.onFailure { bad(it.message ?: "invalid server '${s.name}'") }
                if (s.provides.isEmpty()) bad("server '${s.name}' declares no capability tag (provides, e.g. [\"browser\"])")
                s.provides.firstOrNull { !TAG.matches(it) }?.let { bad("capability tag '$it' of '${s.name}' must match ${TAG.pattern}") }
                if (!s.enabled) bad("server '${s.name}': a plugin server cannot be declared disabled")
            }
        }

        /** The registry the daemon shares with the plugin host (the host declares into it; the registry reads it). */
        val shared: McpPluginServers = McpPluginServers()
    }
}
