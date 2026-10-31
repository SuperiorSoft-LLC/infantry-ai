package com.superiorsoftllc.agenthq.orchestration

import com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ORCH-1 step 3 (docs/DAEMON-CORE.md 6.6): the runner side (sessions, harnesses, worktrees, commands, the CLI and its client)
 * and the orchestrator side (board, tickets, mail rules, roles, prompts, memory, triggers, the pod scheduler) may only meet
 * through `daemon.api.orchestrator` (OrchestratorLink / RunnerLink). Imports that still cross the line are a RATCHET: this test
 * fails on a new crossing and on a baseline entry that is now too high, so the list only shrinks. Each entry is a follow-up
 * (docs/DAEMON-CORE.md 6.7).
 */
class OrchestratorBoundaryGuardTest {
    private val main = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq")

    @Test fun `runner and orchestrator packages only cross by the known baseline`() {
        val seen = HashMap<String, Int>()
        // DP-7a: a name declared in agentHqProtocol is a wire type, not a crossing into the orchestrator's implementation.
        val wire = wireNames()
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val pkg = f.relativeTo(main).path.replace('\\', '/').substringBefore('/')
            f.forEachLine { l ->
                val m = IMPORT.matchEntire(l.trim()) ?: return@forEachLine
                val to = m.groupValues[1]
                if (to in KNOWLEDGE && l.trim().substringAfterLast('.') in wire) return@forEachLine
                if ((pkg in RUNNER && to in ORCHESTRATOR) || (pkg in ORCHESTRATOR && to in RUNNER)) seen.merge("$pkg>$to", 1, Int::plus)
            }
        }
        val worse = seen.filter { (k, n) -> n > (BASELINE[k] ?: 0) }
        val stale = BASELINE.filter { (k, n) -> (seen[k] ?: 0) < n }
        assertTrue(worse.isEmpty(), "new runner<->orchestrator imports (go through daemon.api.orchestrator instead): $worse")
        assertTrue(stale.isEmpty(), "BASELINE is higher than the code now; lower it to lock the gain: ${stale.map { (k, n) -> "$k baseline $n now ${seen[k] ?: 0}" }}")
    }

    @Test fun `the interface module names no daemon implementation package`() {
        val api = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHqDaemonApi/src/main/kotlin/com/superiorsoftllc/agenthq/daemon/api/orchestrator")
        val bad = api.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { it.trim().startsWith("import com.superiorsoftllc.agenthq.") && !it.contains(".daemon.api.") }.map { "${f.name}: ${it.trim()}" }
        }.toList()
        assertTrue(bad.isEmpty(), "the orchestrator interface depends only on itself: $bad")
    }

    private fun wireNames(): Set<String> {
        val dir = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHqProtocol/src/main/kotlin/com/superiorsoftllc/agenthq")
        val decl = Regex("""^(?:data |enum |sealed )?(?:class|object|interface|fun|val|const val)\s+(\w+)""")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapNotNull { decl.find(it)?.groupValues?.get(1) } }.toSet()
    }

    companion object {
        private val KNOWLEDGE = setOf("memory", "issues", "decisions")
        private val IMPORT = Regex("""import com\.superiorsoftllc\.agenthq\.(\w+)\..*""")
        val RUNNER = setOf("session", "harness", "provider", "claude", "opencode", "executables", "runner", "worktree", "projectgit", "commands", "images", "update", "keystore", "cli", "client")
        val ORCHESTRATOR = setOf("backlog", "epics", "stories", "mvp", "pipeline", "ticketfiles", "context", "brief", "ledger", "orchestration", "role", "agents", "prompts", "triggers", "attention", "questions", "decisions", "issues", "memory")

        /** Today's crossings, "from>to" -> import count (51 in 30 pairs). Lower an entry when you remove an import; never raise one. */
        val BASELINE = mapOf(
            "agents>session" to 4, "backlog>images" to 0, "backlog>session" to 3, "backlog>worktree" to 0, "brief>session" to 0,
            "claude>prompts" to 2, "cli>agents" to 1, "cli>decisions" to 0, "cli>epics" to 2, "cli>issues" to 0, "cli>memory" to 0,
            "cli>orchestration" to 3, "cli>pipeline" to 5, "cli>prompts" to 1, "cli>role" to 1, "cli>ticketfiles" to 3,
            "context>session" to 0, "images>mvp" to 0, "memory>session" to 0, "mvp>worktree" to 1, "opencode>prompts" to 1,
            "orchestration>session" to 7, "projectgit>mvp" to 1, "prompts>session" to 2, "role>session" to 3, "session>agents" to 1,
            "session>prompts" to 1, "stories>session" to 0, "ticketfiles>worktree" to 2, "triggers>session" to 5,
        )
    }
}
