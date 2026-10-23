package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.TestWait
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P10-K8: `agenthq knowledge` over a REAL daemon and the REAL [Cli.run] (the [CliDecisionTest] pattern), on a project
 * seeded through the CLI itself. Three lists, each capped: the note index (folded into folders above 60 notes, at most
 * 60 lines), the ACTIVE decisions (at most 50) and the OPEN issues (at most 30); a capped list ends with `N more`.
 * Superseded decisions and fixed issues never appear.
 */
class CliKnowledgeTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("agenthq-knowledge-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, project: String? = "P"): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (project != null) put(AccessPolicy.PROJECT_ID_ENV, project)
            put(ANSWER_TIMEOUT_ENV, TestWait.defaultMs.toString())
        }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    private fun DaemonFixture.seed(vararg args: String) = cli(*args).also { assertEquals(0, it.exit, args.joinToString(" ") + ": " + it.err) }

    private fun Result.lines() = out.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    /** The lines between [heading] and the next blank line. */
    private fun Result.section(heading: String): List<String> {
        val all = out.replace("\r\n", "\n").lines()
        val start = all.indexOf(heading)
        assertTrue(start >= 0, "no $heading section in:\n$out")
        return all.drop(start + 1).takeWhile { it.isNotBlank() }
    }

    @Test
    fun aSeededProjectPrintsThreeCappedListsWithoutDeadKnowledge() {
        val fixture = launch()
        // Notes: 3 filed under ops/ and 62 at the root = 65 notes, above the fold, so 1 folder line + 62 root lines.
        for (i in 1..3) fixture.seed("memory", "save", "runbook$i", "--folder", "ops/deploy", "--description", "ops $i", "--content", "x")
        for (i in 1..62) fixture.seed("memory", "save", "note%02d".format(i), "--description", "root $i", "--content", "x")
        // Decisions: 52 added, then D1 and D2 superseded (D53, D54): 52 active, 2 dead.
        fixture.seed("decision", "add", "dead choice one")
        fixture.seed("decision", "add", "dead choice two")
        for (i in 3..52) fixture.seed("decision", "add", "decision $i")
        fixture.seed("decision", "add", "replaces one", "--supersedes", "D1")
        fixture.seed("decision", "add", "replaces two", "--supersedes", "D2")
        // Issues: 33 opened, I1 and I2 fixed: 31 open.
        fixture.seed("issue", "open", "--summary", "fixed bug one", "--location", "src/a/One.kt")
        fixture.seed("issue", "open", "--summary", "fixed bug two", "--location", "src/a/Two.kt")
        for (i in 3..33) fixture.seed("issue", "open", "--summary", "bug $i", "--location", "src/b/B$i.kt")
        fixture.seed("issue", "fix", "I1", "--summary", "done")
        fixture.seed("issue", "fix", "I2", "--summary", "done")

        val result = fixture.cli("knowledge")
        assertEquals(0, result.exit, result.err)
        assertEquals("Knowledge of project P: 65 notes, 52 active decisions, 31 open issues", result.lines().first())

        val notes = result.section("NOTES")
        assertEquals(61, notes.size, notes.joinToString("\n"))
        assertEquals("ops/ (3 notes)", notes.first())
        assertEquals("note01: root 1", notes[1])
        assertEquals("note59: root 59", notes[59])
        assertEquals("3 more: agenthq memory index", notes.last())

        val decisions = result.section("DECISIONS")
        assertEquals(51, decisions.size, decisions.joinToString("\n"))
        assertEquals("D3 decision 3", decisions.first())
        assertEquals("D52 decision 52", decisions[49])
        assertEquals("2 more: agenthq decision list", decisions.last())

        val issues = result.section("ISSUES")
        assertEquals(31, issues.size, issues.joinToString("\n"))
        assertEquals("I3 bug 3 (src/b/B3.kt)", issues.first())
        assertEquals("I32 bug 32 (src/b/B32.kt)", issues[29])
        assertEquals("1 more: agenthq issue list", issues.last())

        for (dead in listOf("D1 ", "D2 ", "dead choice", "I1 ", "I2 ", "fixed bug")) {
            assertFalse(result.lines().any { it.startsWith(dead) || dead in it }, "'$dead' must not appear in:\n${result.out}")
        }
    }

    @Test
    fun underTheCapsNothingIsFoldedAndNoMoreLineIsPrinted() {
        val fixture = launch()
        fixture.seed("memory", "save", "runbook", "--folder", "ops", "--description", "how to deploy", "--content", "x")
        fixture.seed("decision", "add", "use mongo")
        fixture.seed("decision", "add", "use postgres", "--supersedes", "D1")
        fixture.seed("issue", "open", "--summary", "login 500", "--location", "src/auth/Login.kt")

        val result = fixture.cli("knowledge")
        assertEquals(0, result.exit, result.err)
        assertEquals(
            listOf(
                "Knowledge of project P: 1 note, 1 active decision, 1 open issue",
                "NOTES",
                "ops/runbook: how to deploy",
                "DECISIONS",
                "D2 use postgres",
                "ISSUES",
                "I1 login 500 (src/auth/Login.kt)",
            ),
            result.lines(),
        )

        val empty = fixture.cli("knowledge", project = "Q")
        assertEquals(0, empty.exit, empty.err)
        assertEquals(
            listOf(
                "Knowledge of project Q: 0 notes, 0 active decisions, 0 open issues",
                "NOTES", "(none)", "DECISIONS", "(none)", "ISSUES", "(none)",
            ),
            empty.lines(),
        )
    }

    @Test
    fun malformedInvocationsAreUsageErrors() {
        val fixture = launch()
        val noProject = fixture.cli("knowledge", project = null)
        assertEquals(2, noProject.exit)
        assertTrue("knowledge needs a project id" in noProject.err, noProject.err)
        val extra = fixture.cli("knowledge", "now")
        assertEquals(2, extra.exit)
        assertTrue("knowledge takes no argument" in extra.err, extra.err)
    }
}
