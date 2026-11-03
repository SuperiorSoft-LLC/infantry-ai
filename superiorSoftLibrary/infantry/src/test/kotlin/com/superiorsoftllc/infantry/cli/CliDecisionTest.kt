package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P10-K5: `infantry decision list / show / add` over a REAL daemon and the REAL [Cli.run] (the [CliMemoryTest] pattern).
 * `--supersedes` takes `D1` or `1`, `--all` prints each superseded decision under the one that replaced it, and a
 * refusal prints the daemon's text, code first, exit 1; a malformed invocation is a usage error, exit 2.
 */
class CliDecisionTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-decision-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, stdin: String = "", project: String? = "P", session: String? = null): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (project != null) put(AccessPolicy.PROJECT_ID_ENV, project)
            if (session != null) put(AccessPolicy.SESSION_ID_ENV, session)
        }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader(stdin) })
        return Result(exit, out.toString(), err.toString())
    }

    private fun Result.lines() = out.lines().filter { it.isNotBlank() }

    @Test
    fun addListShowAndBothSupersedeForms() {
        val fixture = launch()
        fixture.cli("decision", "add", "use", "mongo", "--body", "it was there").also {
            assertEquals(0, it.exit, it.err)
            assertEquals("added D1: use mongo", it.out.trim())
        }
        fixture.cli("decision", "add", "tabs not spaces").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("decision", "add", "use mysql", "--supersedes", "D1").also {
            assertEquals(0, it.exit, it.err)
            assertEquals("added D3 (supersedes D1): use mysql", it.out.trim())
        }
        fixture.cli("decision", "add", "use postgres", "--supersedes", "3", "--body", "-", stdin = "jsonb\nand more").also {
            assertEquals(0, it.exit, it.err)
            assertEquals("added D4 (supersedes D3): use postgres", it.out.trim())
        }

        assertEquals(listOf("D2 | tabs not spaces", "D4 | use postgres"), fixture.cli("decision", "list").lines())

        val all = fixture.cli("decision", "list", "--all")
        assertEquals(0, all.exit, all.err)
        assertEquals(
            listOf(
                "D2 | tabs not spaces",
                "D4 | use postgres",
                "  ^ D3 | use mysql (superseded by D4)",
                "  ^ D1 | use mongo (superseded by D3)",
            ),
            all.lines(),
        )

        val show = fixture.cli("decision", "show", "D1")
        assertEquals(0, show.exit, show.err)
        assertTrue("D1: use mongo" in show.out && "STATUS: superseded by D3" in show.out && "it was there" in show.out, show.out)
        val head = fixture.cli("decision", "show", "4")
        assertTrue("STATUS: active" in head.out && "SUPERSEDES: D3" in head.out && "jsonb\nand more" in head.out.replace("\r\n", "\n"), head.out)
    }

    @Test
    fun refusalsPrintTheCodeAndExitOne() {
        val fixture = launch()
        fixture.cli("decision", "add", "first").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("decision", "add", "second", "--supersedes", "D1").also { assertEquals(0, it.exit, it.err) }

        val twice = fixture.cli("decision", "add", "third", "--supersedes", "1")
        assertEquals(1, twice.exit)
        assertTrue("decision_add refused: already_superseded: " in twice.err, twice.err)

        val missing = fixture.cli("decision", "add", "x", "--supersedes", "D9")
        assertEquals(1, missing.exit)
        assertTrue("decision_add refused: decision_not_found: " in missing.err, missing.err)

        val otherProject = fixture.cli("decision", "add", "x", "--supersedes", "D1", project = "Q")
        assertEquals(1, otherProject.exit)
        assertTrue("decision_add refused: decision_not_found: " in otherProject.err, otherProject.err)

        val ghost = fixture.cli("decision", "add", "x", session = "no-such-session")
        assertEquals(1, ghost.exit)
        assertTrue("decision_add refused: session_not_found: " in ghost.err, ghost.err)

        val show = fixture.cli("decision", "show", "D7")
        assertEquals(1, show.exit)
        assertTrue("decision_get refused: decision_not_found: " in show.err, show.err)

        assertEquals(listOf("D2 | second"), fixture.cli("decision", "list").lines(), "no refusal wrote a decision")
        assertEquals("No decisions in project Q.", fixture.cli("decision", "list", project = "Q").out.trim())
    }

    @Test
    fun malformedInvocationsAreUsageErrors() {
        val fixture = launch()
        for (args in listOf(
            arrayOf("decision"),
            arrayOf("decision", "add"),
            arrayOf("decision", "add", "x", "--supersedes", "X1"),
            arrayOf("decision", "show"),
            arrayOf("decision", "show", "D0"),
        )) {
            assertEquals(2, fixture.cli(*args).exit, args.joinToString(" "))
        }
        val noProject = fixture.cli("decision", "list", project = null)
        assertEquals(2, noProject.exit)
        assertTrue("needs a project id" in noProject.err, noProject.err)
    }
}
