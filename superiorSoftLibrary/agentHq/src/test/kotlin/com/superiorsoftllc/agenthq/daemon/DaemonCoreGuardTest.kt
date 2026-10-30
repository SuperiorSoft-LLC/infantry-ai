package com.superiorsoftllc.agenthq.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DAEMON-CORE-1 (docs/DAEMON-CORE.md). Guards that make the daemon core line physical:
 *  (a) core sources name no plugin-owned package and the module depends on no plugin module;
 *  (b) every top-level package of the daemon is classified below, with a reason; DAEMON-PLUGINS-2 shrinks [STAYS] and
 *      [PLUGIN_LATER] and the test fails when a package is unclassified, classified twice, listed but gone, or missing from the doc;
 *  (c) code that talks to the orchestrator names no transport (a binding does, and only a binding).
 */
class DaemonCoreGuardTest {
    private val root = TestDaemonPlugins.repo
    private val main = File(root, "superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq")

    /** CORE-SPLIT-1/2: the packages that now live in their own leaf Gradle module (module -> its source root). */
    private val leaves = LEAF_MODULES.mapValues { (m, _) -> File(root, "superiorSoftLibrary/$m/src/main/kotlin/com/superiorsoftllc/agenthq") }

    private val daemonApi = File(root, "superiorSoftLibrary/agentHqDaemonApi/src/main/kotlin/com/superiorsoftllc/agenthq")
    private fun sources() = (listOf(main, daemonApi) + leaves.values).flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    private fun code(f: File) = f.readLines().withIndex().filter { (_, l) ->
        val t = l.trim(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
    }

    @Test fun `core sources name no plugin-owned package`() {
        assertTrue(sources().size > 100, "daemon sources not found under $main")
        val owned = listOf("agenthq.plugins.", "com.acme.", "com.example.starter")
        val hits = sources().flatMap { f ->
            code(f).filter { (_, l) -> owned.any { it in l.substringBefore(" // ") } }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim().take(110)}" }
        }
        assertTrue(hits.isEmpty(), "core names a plugin-owned package (a plugin may depend on core's API, never the reverse):\n${hits.joinToString("\n")}")
    }

    @Test fun `the daemon module depends on no plugin module`() {
        val deps = File(root, "superiorSoftLibrary/agentHq/build.gradle.kts").readLines().filter { l ->
            val t = l.trim()
            "project(" in t && listOf("implementation", "api", "compileOnly", "runtimeOnly").any { t.startsWith(it) }
        }
        assertTrue(deps.isNotEmpty(), "no project dependencies found: the guard is blind")
        val bad = deps.filter { ":plugins:" in it || "daemon-plugin" in it }
        assertTrue(bad.isEmpty(), "agentHq depends on a plugin module (plugins load at run time, from jars):\n${bad.joinToString("\n")}")
    }

    @Test fun `every top-level package is classified with a reason, none stale`() {
        val actual = (listOf(main) + leaves.values).flatMap { r -> r.listFiles { f -> f.isDirectory }!!.map { it.name } }.toSet()
        val lists = listOf(CORE, STAYS, PLUGIN_NOW, PLUGIN_LATER)
        val listed = lists.flatMap { it.keys }
        assertEquals(emptySet<String>(), actual - listed.toSet(), "unclassified package(s): add each to exactly one list with a reason")
        assertEquals(emptySet<String>(), listed.toSet() - actual, "classified but gone: remove from the list")
        assertEquals(listed.toSet().size, listed.size, "a package is classified twice")
        assertTrue(lists.flatMap { it.values }.all { it.length >= 12 }, "every classification needs a real reason")
        assertTrue(PLUGIN_LATER.values.all { "blocker:" in it }, "plugin-later entries name their blocker as 'blocker: ...'")
    }

    @Test fun `the leaf modules depend on no other agentHq module and no plugin module`() {
        for ((module, packages) in LEAF_MODULES) {
            val gradle = File(root, "superiorSoftLibrary/$module/build.gradle.kts")
            assertTrue(gradle.isFile, "$module has no build.gradle.kts")
            val projectDeps = gradle.readLines().filter { it.trim().let { t -> !t.startsWith("//") && "project(" in t } }
            assertTrue(projectDeps.isEmpty(), "$module is a leaf: it must not depend on any project, found:\n${projectDeps.joinToString("\n")}")
            assertTrue("include(\":superiorSoftLibrary:$module\")" in File(root, "settings.gradle.kts").readText(), "$module is not included in settings.gradle.kts")
            val src = leaves.getValue(module)
            assertEquals(packages, src.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet(), "$module holds exactly these top-level packages")
            val bad = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                code(f).filter { (_, l) ->
                    l.trim().let { t -> t.startsWith("import com.superiorsoftllc.agenthq.") &&
                        !(t.removePrefix("import com.superiorsoftllc.agenthq.").let { r -> LEAF_PACKAGES.any { r == it || r.startsWith("$it.") } }) }
                }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
            }.toList()
            assertTrue(bad.isEmpty(), "$module imports daemon code that is not in a leaf module:\n${bad.joinToString("\n")}")
        }
        assertTrue(File(root, "superiorSoftLibrary/agentHq/build.gradle.kts").readText().let { b -> LEAF_MODULES.keys.all { "project(\":superiorSoftLibrary:$it\")" in b } }, "agentHq must depend on each leaf module")
    }

    @Test fun `the doc lists every package`() {
        val doc = File(root, "docs/DAEMON-CORE.md").readText()
        val missing = listOf(CORE, STAYS, PLUGIN_NOW, PLUGIN_LATER).flatMap { it.keys }.filter { "`$it`" !in doc }
        assertTrue(missing.isEmpty(), "docs/DAEMON-CORE.md does not mention package(s): $missing")
    }

    @Test fun `non-binding code names no transport when it talks to the orchestrator`() {
        val transport = Regex("""java\.net\.http|HttpClient|HttpURLConnection|WebSocket|okhttp|ktor\.client|https?://|wss?://""")
        fun binding(f: File) = f.path.replace('\\', '/').contains("/orchestration/binding/")
        val users = sources().filter { f -> "OrchestratorLink" in f.readText() && !binding(f) }
        assertTrue(users.any { it.name == "OrchestratorLink.kt" } && users.any { it.name == "DaemonMain.kt" }, "the interface and its first caller were not found")
        val hits = users.flatMap { f ->
            // DaemonMain names URLs for its own server; only the lines about the link count there.
            code(f).filter { (_, l) -> transport.containsMatchIn(l) && (f.name != "DaemonMain.kt" || "rchestratorLink" in l) }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim().take(110)}" }
        }
        assertTrue(hits.isEmpty(), "transport named next to the orchestrator link (it belongs in an orchestration/binding/ class):\n${hits.joinToString("\n")}")
    }

    companion object {
        /** CORE-SPLIT-1/2: leaf Gradle modules and the top-level packages each owns (package names unchanged). */
        val LEAF_MODULES = mapOf(
            "agentHqProtocol" to setOf("protocol", "session", "harness"),
            "agentHqRunner" to setOf("runner"),
        )

        /** What a leaf module may import from the agenthq tree: packages that live in a leaf module (and the shared types of session/harness it carries). */
        private val LEAF_PACKAGES = setOf("protocol", "runner", "session.SessionState", "session.SleepHint", "session.WaitHint", "session.RunPhaseOverlay", "harness.HarnessCommand", "harness.CommandAction", "harness.StandardCommands", "harness.CommandLine", "harness.CommandLines")

        /** CORE: the abstraction layer. Stays by definition (docs/DAEMON-CORE.md section 2). */
        val CORE = mapOf(
            "protocol" to "the wire contract and frames: the abstract data contract between UI and daemon",
            "daemon" to "bootstrap, FrameGate, AccessPolicy and the plugin host (daemon/plugins); concrete handlers inside it are listed in the doc",
            "policy" to "ActionCatalog, PolicyProvider, PolicyRuntime: the policy layer mechanism",
            "settings" to "SettingsService and LimitRegistry: configuration and the tools to modify it",
            "db" to "the one place that opens the SQLite database: the storage abstraction",
            "keystore" to "secret storage behind the secrets the plugin API grants",
            "harness" to "the Harness / HarnessPlugin / HarnessRegistry abstraction (its claude, pi, descriptor and install subfolders are not core)",
            "runner" to "process-runner and watch mechanism; ToolProfiles are data on top of it",
        )

        /** STAYS: not abstract, but cannot leave yet. Each reason names what holds it; DAEMON-PLUGINS-2 shrinks this list. */
        val STAYS = mapOf(
            "session" to "SessionManager, the session lifecycle and the process ledger are core; AutoProgress, ToolApprovals, SleepHint and friends inside it are plugin candidates",
            "mailbox" to "Mailbox, MailRouter and MailStore are core mail routing; Stall/Watchdog/ReportBack monitors in it are product rules that move out later",
            "persistence" to "AgentHqStore and records are shared by every feature table; shrinks as each feature takes its own table",
            "worktree" to "git working copies: runner side, used by SessionManager and the board alike",
            "projectgit" to "git status/diff/commit helpers over worktree for the project, runner side; moves with the Git official plugin once DaemonApi has a git surface",
            "usage" to "limits and usage counters are read by SettingsService and attention",
            "usagemeter" to "token metering fed by every harness stream",
            "stats" to "harness-neutral RequestUsage records (STATS-2 contract)",
            "tokens" to "usage ledger behind the meter",
            "executables" to "resolves harness and tool binaries for the runner",
            "update" to "self-update of the daemon install; DaemonMain wires it directly",
            "notify" to "the spoken-push frame helper of the protocol",
            "provider" to "AgentProvider abstraction used by SessionManager; ClaudeCodeProvider and OpenCodeProvider inside it are concrete (see claude, opencode)",
            "orchestration" to "the pod/node DAG scheduler; the scheduler is orchestrator side (the orchestrator seam, OrchestratorLink, lives in agentHqDaemonApi since ORCH-1)",
        )

        /** PLUGIN NOW: a mechanism and an official plugin exist (or can be written today); only the move is left. */
        val PLUGIN_NOW = mapOf(
            "pipeline" to "Pipeline official plugin (agenthq_pipeline tools)",
            "issues" to "project knowledge: issues, a Knowledge plugin",
            "decisions" to "project knowledge: decisions, a Knowledge plugin",
            "memory" to "project knowledge: notes, a Knowledge plugin",
            "prompts" to "prompt templates: Infantry_Prompts daemon half",
            "images" to "chat uploads: Infantry_Chat daemon half",
        )

        /** PLUGIN LATER: needs a missing mechanism first; the blocker is named. */
        val PLUGIN_LATER = mapOf(
            "backlog" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "epics" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "stories" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "mvp" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "ticketfiles" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "context" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "brief" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "ledger" to "blocker: DaemonApi has no ticket/board/epic surface and DaemonMain, TicketBoard, the story handlers and the mailbox name each other directly; see docs/DAEMON-BOARD-API.md",
            "claude" to "blocker: concrete Claude adapter is already a HarnessPlugin, but ProviderFactory, HarnessModelCatalogs, Sandbox and DaemonMain name it, so built-ins are not registered through the SPI",
            "opencode" to "blocker: same as claude (ProviderFactory and DaemonMain name OpenCodeServeAdapter)",
            "role" to "blocker: roles are bound into SessionManager spawn and mail addressing; needs a DaemonApi role surface",
            "agents" to "blocker: AgentProfiles is wired into MailRouter and SessionManager spawn binding; needs a spawn-binding hook",
            "questions" to "blocker: questions are delivered through the mailbox and attention store; needs attention in the API first",
            "attention" to "blocker: usage, mailbox and triggers raise attention items directly (reverse dependency)",
            "triggers" to "blocker: DaemonApi has schedule() but no spawn-with-binding or ticket-transition events",
            "commands" to "blocker: RunManager needs a pty/runner surface in DaemonApi",
            "cli" to "blocker: the agenthq CLI and MCP server import protocol, session and daemon types; needs the agentHqCore module split first",
            "experiments" to "blocker: the pi tool bridge hooks PiLaunchExtras, SessionStatsService taggers and reads AgentHqMcp/AccessPolicy internals; needs a harness launch-extras hook and an MCP tool-exposure surface in DaemonApi",
            "client" to "blocker: the console client shares protocol and mailbox types; moves with cli",
        )
    }
}
