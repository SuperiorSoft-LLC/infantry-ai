// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DAEMON-PLUGINS-2 ratchet: every built-in agent tool still in core (`cli/mcp`) is on an explicit list with its reason.
 * A new tool in core fails here (write it as a daemon plugin, docs/DAEMON-PLUGINS.md section 8); a migrated tool must leave
 * the list. The list may only shrink: [CEILING] is lowered with each migration.
 */
class BuiltinToolsListGuardTest {
    private val mcp = File(TestDaemonPlugins.repo, "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/mcp")

    /** tool -> why it is still in core. All are "migration pending" (family + blocker, docs/DAEMON-PLUGINS.md section 6). */
    private val inCore: Map<String, String> = buildMap {
        fun family(reason: String, vararg tools: String) = tools.forEach { put(it, reason) }
        family("pending: the watcher must run where the files are (the MCP client), needs a client-run tool seam in DaemonApi", "infantry_watch_file")
    }

    /** RUNTIME-LOAD-1: `infantry_harness_status` is Infantry_Agents' daemon half; a second copy in core would shadow the plugin's tool. */
    @Test fun `harness status is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { "infantry_harness_status" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "infantry_harness_status must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"infantry_harness_status\"" in manifest, "Infantry_Agents/module.json must declare the tool")
    }

    /** RUNTIME-LOAD-2: install joined status in Infantry_Agents; its tool timeout is the tool's own data, not a module-wide limit. */
    @Test fun `harness install is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { "infantry_harness_install" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "infantry_harness_install must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"infantry_harness_install\"" in manifest, "Infantry_Agents/module.json must declare the tool")
        assertTrue("toolTimeoutMs" !in manifest, "a long tool declares DaemonTool.timeoutMs instead of raising the whole module's limit")
    }

    @Test fun `list harnesses is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { Regex("\"infantry_list_harnesses\"").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "infantry_list_harnesses must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"infantry_list_harnesses\"" in manifest, "Infantry_Agents/module.json must declare the tool")
    }

    /** RUNTIME-LOAD-3: the roles, ask and project config tools are daemon halves of Infantry_Roles / _Attention / _Settings, wired nowhere in core. */
    @Test fun `roles ask and project config tools are not wired in core and their modules declare them`() {
        val moved = mapOf(
            "Infantry_Roles" to listOf("infantry_role_list", "infantry_role_get", "infantry_role_update", "infantry_role_create", "infantry_role_delete", "infantry_skill_list", "infantry_skill_save", "infantry_skill_delete"),
            "Infantry_Attention" to listOf("infantry_ask"),
            // RUNTIME-LOAD-4: the session's own progress report moved with the Agents family.
            // RUNTIME-LOAD-5: the session reads, over the host's sessions read model.
            "Infantry_Agents" to listOf("infantry_progress", "infantry_list_sessions", "infantry_get_session", "infantry_pod_state",
                // RUNTIME-LOAD-7 (F5 last): the spawn family, over the host spawn service.
                "infantry_spawn", "infantry_prompt", "infantry_kill", "infantry_session_restart", "infantry_session_remove"),
            "Infantry_Settings" to listOf("infantry_project_config_get", "infantry_project_config_set", "infantry_settings_get", "infantry_settings_set"),
            // RUNTIME-LOAD-8: the COMMS mail tools and the project pipeline tools.
            "Infantry_Chat" to listOf("infantry_inbox", "infantry_read", "infantry_message", "infantry_projects"),
            // BOARD-API-2: the ticket read tools (the files pair moved earlier, with TICKET-FILES).
            // RUNTIME-LOAD-4 (F5): the board's write tools.
            "Infantry_Board" to listOf("infantry_ticket_get", "infantry_ticket_files", "infantry_ticket_related_files", "infantry_epic_base", "infantry_ticket_move", "infantry_ticket_delete",
                "infantry_ticket_create", "infantry_ticket_update", "infantry_board", "infantry_ticket_start",
                // DP-6: the pipeline tools moved here from Infantry_Git with the pipeline frames.
                "infantry_pipeline", "infantry_pipeline_record",
                // ARCHIVE-1
                "infantry_ticket_archive", "infantry_ticket_restore", "infantry_archive"),
        )
        val sources = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        moved.forEach { (module, tools) ->
            val manifest = File(TestDaemonPlugins.repo, "plugins/official/$module/module.json").readText()
            tools.forEach { tool ->
                assertTrue(sources.none { "\"$tool\"" in it }, "$tool must come from plugins/official/$module/daemon, not cli/mcp")
                assertTrue("\"$tool\"" in manifest, "$module/module.json must declare $tool")
            }
        }
    }

    @Test fun `every built-in tool in core is on the list with a reason`() {
        val found = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("\"(infantry_[a-z_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(found.isNotEmpty(), "the tool sources were not found under $mcp")
        assertEquals(emptySet(), found - inCore.keys, "new built-in tools in core: write them as daemon plugins, or list them with a reason")
        assertEquals(emptySet(), inCore.keys - found, "listed tools that left core: delete them from the list (it only shrinks)")
        assertTrue(inCore.size <= CEILING, "the list grew past $CEILING")
        assertTrue(inCore.values.all { it.length > 15 })
    }

    private companion object { const val CEILING = 1 }
}
