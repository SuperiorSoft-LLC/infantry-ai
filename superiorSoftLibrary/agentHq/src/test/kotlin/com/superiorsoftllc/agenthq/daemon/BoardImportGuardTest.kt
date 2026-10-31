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
    private val importRe = Regex("""^\s*import\s+com\.superiorsoftllc\.agenthq\.(backlog|context|brief)\b""")

    /** package/file -> importers still to move in DP-5. */
    private val remaining = setOf(
        "daemon/plugins/LocalBoardServices.kt", "daemon/DaemonMain.kt",
        "mvp/StoryHandler.kt",
        "stories/StoryMigration.kt", "ticketfiles/LiveRelatedWork.kt", "ticketfiles/RelatedDescriptionWriter.kt",
        "ticketfiles/RelatedTickets.kt",
    )

    private fun importers() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { f -> f.relativeTo(main).path.replace(File.separatorChar, '/').substringBefore('/') !in boardPackages }
        .filter { f -> f.readLines().any { importRe.containsMatchIn(it) } }
        .map { it.relativeTo(main).path.replace(File.separatorChar, '/') }.toSet()

    @Test fun `session and mailbox import no board package`() {
        assertTrue(main.isDirectory, "sources not found: $main")
        val bad = importers().filter { it.substringBefore('/') in strict }
        assertTrue(bad.isEmpty(), "route through OrchestratorLink/DaemonApi instead:\n${bad.joinToString("\n")}")
    }

    @Test fun `other board importers are a closed list`() {
        assertEquals(remaining, importers().filter { it.substringBefore('/') !in strict }.toSet(), "new or removed board importer: update the list (it only shrinks)")
    }
}
