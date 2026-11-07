// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLUGIN-TOOLS-GUARD-1: every tool name a repo daemon plugin's source mentions is declared in that plugin's module.json
 * `daemon.tools`, unless it is on [references]. The host refuses a plugin at start when it registers a tool that
 * `daemon.tools` does not declare (DaemonPluginHost), and nothing else caught that before a packaged build
 * (LAUNCH-VERIFY-1: infantry_plugin_list_import). A name on [references] is mentioned but not registered by that plugin
 * (an invoke of another plugin's tool, a table name); a new entry needs its reason.
 */
class PluginToolsDeclaredGuardTest {
    private val official = File(TestDaemonPlugins.repo, "plugins/official")
    private val toolName = Regex("\"(infantry_[a-z0-9_]+)\"")

    /** plugin to (name to why it is not a tool this plugin registers). */
    private val references: Map<String, Map<String, String>> = mapOf(
        "Infantry_Agents" to mapOf("infantry_ticket_start" to "invoked/named, a board tool"),
        "Infantry_Approvals" to mapOf("infantry_approvals_rules" to "a database table name"),
        "Infantry_Chat" to mapOf("infantry_chat_note" to "invoked through api.tools, a core tool"),
        "Infantry_Lua" to mapOf("infantry_ticket_move" to "named for the script bridge"),
        "Infantry_Scripting" to mapOf(
            "infantry_chat_image" to "script alias target", "infantry_message" to "script alias target",
            "infantry_progress" to "script alias target", "infantry_ticket_comment" to "script alias target",
            "infantry_ticket_get" to "script alias target",
        ),
    )

    @Test fun `every tool a daemon plugin names is declared in its module json`() {
        val failures = mutableListOf<String>()
        official.listFiles { f -> File(f, "daemon/src/main").isDirectory }.orEmpty().sortedBy { it.name }.forEach { plugin ->
            val manifest = File(plugin, "module.json").readText()
            val allowed = references[plugin.name].orEmpty()
            File(plugin, "daemon/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { f -> toolName.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet().forEach { t ->
                    if ("\"$t\"" !in manifest && t !in allowed) failures += "${plugin.name}: tool $t is not declared in module.json daemon.tools"
                }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
