// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents.registry

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.6): the layer direction. Orchestration depends on agents; agents never depend on
 * orchestration. The agent contract, the registry, its module sync, the official agents/roles daemon halves and the example agent
 * plugin may not import or name the orchestration packages (board, tickets, worktrees, pipeline, epics, project git) or the
 * orchestration API. And the registry may not name the roles package: roles contribute to it, not the reverse.
 */
class AgentRegistryLayerGuardTest {
    private val repo = TestDaemonPlugins.repo
    private val core = "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry"

    /** Orchestration (layer 3): its core packages and its API packages. */
    private val orchestration = Regex(
        """com\.superiorsoftllc\.infantry\.(backlog|worktree|orchestration|pipeline|epics|ticketfiles|projectgit|context|mvp|stories|daemon\.api\.board|daemon\.api\.orchestrator)\b""",
    )

    private val agentLayer: List<String> = listOf(
        "superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/agents",
        "superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/AgentDefinition.kt",
        "$core/agents/registry",
        "$core/daemon/plugins/AgentModules.kt",
        "$core/role/RoleAgents.kt",
        "$core/cli/AgentDefCli.kt",
        "plugins/official/Infantry_Agents/daemon/src",
        "plugins/official/Infantry_Roles/daemon/src",
        "plugins/examples/Acme_NewsAgent/daemon/src",
    )

    private fun sources(path: String): List<File> = File(repo, path).let { f ->
        if (f.isFile) listOf(f) else f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun hits(files: List<File>, pattern: Regex): List<String> = files.flatMap { f ->
        f.readLines().withIndex().filter { (_, l) -> pattern.containsMatchIn(l) }.map { (i, l) -> "${f.relativeTo(repo).invariantSeparatorsPath}:${i + 1}: ${l.trim()}" }
    }

    @Test fun `the agent layer names no orchestration code`() {
        val files = agentLayer.flatMap { p -> sources(p).also { assertTrue(it.isNotEmpty(), "no sources at $p") } }
        val found = hits(files, orchestration)
        assertEquals(emptyList(), found, "agents must not depend on orchestration:\n${found.joinToString("\n")}")
    }

    @Test fun `the registry does not name roles, roles name the registry`() {
        val found = hits(sources("$core/agents/registry"), Regex("""com\.superiorsoftllc\.infantry\.role\b"""))
        assertEquals(emptyList(), found, "the registry is below roles:\n${found.joinToString("\n")}")
    }

    @Test fun `the agent contract has no work-item fields`() {
        val contract = File(repo, "superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/AgentDefinition.kt").readText() +
            sources("superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/agents").joinToString("") { it.readText() }
        val fields = Regex("""\bval\s+(\w+)""").findAll(contract).map { it.groupValues[1].lowercase() }.toList()
        val workItems = fields.filter { f -> listOf("ticket", "board", "worktree", "branch", "epic", "pipeline").any { it in f } }
        assertEquals(emptyList(), workItems, "binding a session to work is orchestration's capability, never an agent field")
    }
}
