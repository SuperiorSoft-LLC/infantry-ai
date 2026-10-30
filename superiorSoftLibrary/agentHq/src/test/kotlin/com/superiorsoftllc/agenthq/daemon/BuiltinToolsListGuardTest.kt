package com.superiorsoftllc.agenthq.daemon

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
    private val mcp = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq/cli/mcp")

    /** tool -> why it is still in core. All are "migration pending" (family + blocker, docs/DAEMON-PLUGINS.md section 6). */
    private val inCore: Map<String, String> = buildMap {
        fun family(reason: String, vararg tools: String) = tools.forEach { put(it, reason) }
        family("pending: Infantry_Board daemon half; the Tickets API + host ProjectScope exist (DP-4a), the CLI tests that call these tools on plugin-less daemons are what is left", "agenthq_ticket_create", "agenthq_ticket_update", "agenthq_ticket_start", "agenthq_board")
        family("pending: a Mail/Chat daemon half", "agenthq_inbox", "agenthq_read", "agenthq_message")
        family("pending: a Pipeline official plugin", "agenthq_pipeline", "agenthq_pipeline_record")
        family("pending: Infantry_Agents daemon half; McpGuardrails moves with it", "agenthq_list_sessions", "agenthq_get_session", "agenthq_pod_state", "agenthq_spawn", "agenthq_prompt", "agenthq_kill", "agenthq_session_restart", "agenthq_session_remove")
        family("pending: file watch is a daemon behaviour (watch hooks), needs a DaemonApi surface", "agenthq_watch_file")
    }

    /** RUNTIME-LOAD-1: `agenthq_harness_status` is Infantry_Agents' daemon half; a second copy in core would shadow the plugin's tool. */
    @Test fun `harness status is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { "agenthq_harness_status" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "agenthq_harness_status must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"agenthq_harness_status\"" in manifest, "Infantry_Agents/module.json must declare the tool")
    }

    /** RUNTIME-LOAD-2: install joined status in Infantry_Agents; its tool timeout is the tool's own data, not a module-wide limit. */
    @Test fun `harness install is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { "agenthq_harness_install" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "agenthq_harness_install must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"agenthq_harness_install\"" in manifest, "Infantry_Agents/module.json must declare the tool")
        assertTrue("toolTimeoutMs" !in manifest, "a long tool declares DaemonTool.timeoutMs instead of raising the whole module's limit")
    }

    @Test fun `list harnesses is not wired in core and the Infantry_Agents module declares it`() {
        val inCoreSource = mcp.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { Regex("\"agenthq_list_harnesses\"").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), inCoreSource, "agenthq_list_harnesses must come from plugins/official/Infantry_Agents/daemon, not cli/mcp")
        val manifest = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents/module.json").readText()
        assertTrue("\"agenthq_list_harnesses\"" in manifest, "Infantry_Agents/module.json must declare the tool")
    }

    /** RUNTIME-LOAD-3: the roles, ask and project config tools are daemon halves of Infantry_Roles / _Attention / _Settings, wired nowhere in core. */
    @Test fun `roles ask and project config tools are not wired in core and their modules declare them`() {
        val moved = mapOf(
            "Infantry_Roles" to listOf("agenthq_role_list", "agenthq_role_get", "agenthq_role_update", "agenthq_role_create", "agenthq_role_delete"),
            "Infantry_Attention" to listOf("agenthq_ask"),
            // RUNTIME-LOAD-4: the session's own progress report moved with the Agents family.
            "Infantry_Agents" to listOf("agenthq_progress"),
            "Infantry_Settings" to listOf("agenthq_project_config_get", "agenthq_project_config_set"),
            // BOARD-API-2: the ticket read tools (the files pair moved earlier, with TICKET-FILES).
            // RUNTIME-LOAD-4 (F5): the board's write tools.
            "Infantry_Board" to listOf("agenthq_ticket_get", "agenthq_ticket_files", "agenthq_ticket_related_files", "agenthq_epic_base", "agenthq_ticket_move", "agenthq_ticket_delete"),
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
            .flatMap { Regex("\"(agenthq_[a-z_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(found.isNotEmpty(), "the tool sources were not found under $mcp")
        assertEquals(emptySet(), found - inCore.keys, "new built-in tools in core: write them as daemon plugins, or list them with a reason")
        assertEquals(emptySet(), inCore.keys - found, "listed tools that left core: delete them from the list (it only shrinks)")
        assertTrue(inCore.size <= CEILING, "the list grew past $CEILING")
        assertTrue(inCore.values.all { it.length > 15 })
    }

    private companion object { const val CEILING = 18 }
}
