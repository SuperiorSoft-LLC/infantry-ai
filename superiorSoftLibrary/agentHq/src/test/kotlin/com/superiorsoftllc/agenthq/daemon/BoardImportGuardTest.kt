package com.superiorsoftllc.agenthq.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DAEMON-PLUGIN-1 (DP-4c). Core names the board only through OrchestratorLink / DaemonApi: session/ and mailbox/ import no
 * `backlog`/`context`/`brief`. The other importers are a closed ratchet (DP-5 shrinks it; a new one fails the test).
 */
class BoardImportGuardTest {
    private val main = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq")
    private val boardPackages = setOf("backlog", "context", "brief")
    private val strict = setOf("session", "mailbox")
    private val importRe = Regex("""^\s*import\s+(com\.superiorsoftllc\.agenthq\.[\w.]+)""")
    private val boardMain = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHqBoard/src/main/kotlin/com/superiorsoftllc/agenthq")

    /**
     * DP-5f-5: keyed by MODULE, not package name. A board symbol is a top-level declaration in :agentHqBoard (`pkg.Name`); an
     * importer is a file of :agentHq (any package) that imports one. Moving the importer to the board, or its use behind a port,
     * removes it.
     */
    private val boardSymbols: Set<String> by lazy {
        val decl = Regex("""^(?:(?:public|internal|data|sealed|enum|abstract|open|annotation|value|inline|const|fun)\s+)*(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)""")
        boardMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val lines = f.readLines()
            val pkg = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() ?: return@flatMap emptySequence()
            lines.mapNotNull { decl.find(it)?.groupValues?.get(1) }.map { "$pkg.$it" }.filter { pkg.substringAfterLast(".") in boardPackages }.asSequence()
        }.toSet()
    }

    /** Core files that import a board class, still to move (DP-5h empties it); it only ever shrinks. */
    private val remaining = setOf(
        "backlog/StoryAdapters.kt", "backlog/TicketBoardStores.kt", "daemon/plugins/LocalBoardServices.kt", "daemon/DaemonMain.kt",
    )

    private fun importers() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { f -> f.readLines().any { l -> importRe.find(l)?.groupValues?.get(1) in boardSymbols } }
        .map { it.relativeTo(main).path.replace(File.separatorChar, '/') }.toSet()

    @Test fun `session and mailbox import no board package`() {
        assertTrue(main.isDirectory, "sources not found: $main")
        assertTrue(boardSymbols.size > 10, "agentHqBoard declarations not found")
        val bad = importers().filter { it.substringBefore('/') in strict }
        assertTrue(bad.isEmpty(), "route through OrchestratorLink/DaemonApi instead:\n${bad.joinToString("\n")}")
    }

    @Test fun `other board importers are a closed list`() {
        assertEquals(remaining, importers().filter { it.substringBefore('/') !in strict }.toSet(), "new or removed board importer: update the list (it only shrinks)")
    }

    /** DP-5e: :agentHqBoard names only the protocol, the daemon API and its own packages (the build also refuses an agentHq dependency). */
    @Test fun `agentHqBoard imports nothing of agentHq`() {
        val board = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHqBoard/src/main/kotlin/com/superiorsoftllc/agenthq")
        assertTrue(board.isDirectory, "sources not found: $board")
        val own = setOf("protocol", "daemon.api", "epics", "brief", "ledger", "context", "ticketfiles", "session", "agents", "worktree", "settings", "usage", "role", "persistence", "backlog", "mvp", "stories")
        val ref = Regex("""com\.superiorsoftllc\.agenthq\.([a-z]+(?:\.api)?)""")
        val bad = board.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
                .flatMap { ref.findAll(it).map { m -> m.groupValues[1] } }.filter { it !in own }.map { "${f.name}: $it" }
        }.toList()
        assertTrue(bad.isEmpty(), "agentHqBoard must not import core:\n${bad.joinToString("\n")}")
    }
}
