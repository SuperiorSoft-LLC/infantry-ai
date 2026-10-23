package com.superiorsoftllc.agenthq.cli

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
 * TRAITS-T7: `agenthq trait save / list / show / delete` and `agenthq role traits` over a REAL daemon and the
 * REAL [Cli.run] (the [CliMemoryTest] pattern). The frames are the T1-T2 ones (contract 1.48.0 / 1.49.0); a
 * refusal prints the daemon's text and exits 1, a usage slip exits 2 before any socket opens.
 */
class CliTraitTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("agenthq-trait-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, stdin: String = ""): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(
            arrayOf(*args, "--port", port.toString()),
            out,
            err,
            getenv = { null },
            input = { StringReader(stdin) },
        )
        return Result(exit, out.toString(), err.toString())
    }

    private fun DaemonFixture.boundTo(roleId: String): List<String> = daemon.store.roleTraits.get(roleId)?.traitIds ?: emptyList()

    private fun usage(vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        // Port 1: nothing listens there, so a usage error that slipped past validation would exit 2 with
        // "not running", which the assertions below tell apart from the usage text.
        val exit = Cli.run(arrayOf(*args, "--port", "1"), out, err, getenv = { null }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    /** Section 8 of docs/AGENTHQ-ROLE-TRAITS.md, typed through the CLI: the worked example. */
    @Test
    fun saveTheReportBackTraitThenListAndShowIt() {
        val fixture = launch()
        val body = "When your work is done, send ONE mail to the PM.\n- commit: <sha>"
        val saved = fixture.cli(
            "trait", "save", "report-back", "--name", "REPORT BACK", "--body", "-",
            "--report-to", "pm", "--report-fields", "commit,tests,uncommitted,model,wall time,blockers",
            stdin = body,
        )
        assertEquals(0, saved.exit, saved.err)
        assertTrue("saved report-back v1" in saved.out, saved.out)
        assertTrue("check: report to pm: commit, tests, uncommitted, model, wall time, blockers" in saved.out, saved.out)

        val stored = fixture.daemon.store.traits.get("report-back")!!
        assertEquals("REPORT BACK", stored.name)
        assertEquals(body, stored.body)
        assertEquals(listOf("commit", "tests", "uncommitted", "model", "wall time", "blockers"), stored.check!!.fields)

        val list = fixture.cli("trait", "list")
        assertEquals(0, list.exit, list.err)
        assertEquals(
            listOf("ID | VERSION | NAME | CHECK", "report-back | 1 | REPORT BACK | report to pm (6 fields)"),
            list.out.lines().filter { it.isNotBlank() },
        )

        val show = fixture.cli("trait", "show", "report-back")
        assertEquals(0, show.exit, show.err)
        assertTrue("NAME: REPORT BACK" in show.out && "VERSION: 1" in show.out, show.out)
        assertTrue(body in show.out, show.out)
    }

    @Test
    fun resavingWithoutNameKeepsTheStoredNameAndBumpsTheVersion() {
        val fixture = launch()
        assertEquals(0, fixture.cli("trait", "save", "no-force", "--name", "No force push", "--body", "never force").exit)
        val again = fixture.cli("trait", "save", "no-force", "--body", "never force-push a shared branch")
        assertEquals(0, again.exit, again.err)
        assertTrue("saved no-force v2" in again.out, again.out)
        assertEquals("No force push", fixture.daemon.store.traits.get("no-force")!!.name)
        assertEquals(null, fixture.daemon.store.traits.get("no-force")!!.check)
    }

    @Test
    fun anEmptyLibraryAndAnUnknownTraitAreReportedPlainly() {
        val fixture = launch()
        val list = fixture.cli("trait", "list")
        assertEquals(0, list.exit, list.err)
        assertEquals("No traits.", list.out.trim())

        val show = fixture.cli("trait", "show", "nope")
        assertEquals(1, show.exit)
        assertTrue("trait_get refused: unknown trait 'nope'" in show.err, show.err)

        val bad = fixture.cli("trait", "save", "x", "--body", "b", "--report-fields", "commit,commit")
        assertEquals(1, bad.exit)
        assertTrue("trait_save refused" in bad.err, bad.err)
        assertEquals(null, fixture.daemon.store.traits.get("x"))
    }

    @Test
    fun roleTraitsSetShowAndClear() {
        val fixture = launch()
        fixture.cli("trait", "save", "a-one", "--body", "one").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("trait", "save", "b-two", "--body", "two").also { assertEquals(0, it.exit, it.err) }

        val none = fixture.cli("role", "traits", "backend")
        assertEquals(0, none.exit, none.err)
        assertEquals("backend: no traits", none.out.trim())

        val set = fixture.cli("role", "traits", "backend", "--set", "b-two,a-one")
        assertEquals(0, set.exit, set.err)
        assertEquals("backend: b-two, a-one", set.out.trim())
        assertEquals(listOf("b-two", "a-one"), fixture.boundTo("backend"))

        assertEquals("backend: b-two, a-one", fixture.cli("role", "traits", "backend").out.trim())

        val unknown = fixture.cli("role", "traits", "backend", "--set", "a-one,ghost")
        assertEquals(1, unknown.exit)
        assertTrue("role_traits_set refused" in unknown.err, unknown.err)
        assertEquals(listOf("b-two", "a-one"), fixture.boundTo("backend"), "a refusal writes nothing")

        val cleared = fixture.cli("role", "traits", "backend", "--clear")
        assertEquals(0, cleared.exit, cleared.err)
        assertEquals("backend: no traits", cleared.out.trim())
        assertEquals(emptyList(), fixture.boundTo("backend"))
    }

    @Test
    fun deleteIsRefusedWhileARoleStillBindsTheTrait() {
        val fixture = launch()
        fixture.cli("trait", "save", "report-back", "--body", "report").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("role", "traits", "backend", "--set", "report-back").also { assertEquals(0, it.exit, it.err) }

        val refused = fixture.cli("trait", "delete", "report-back")
        assertEquals(1, refused.exit, refused.out)
        assertTrue("trait_delete refused" in refused.err && "still bound" in refused.err && "backend" in refused.err, refused.err)
        assertFalse("deleted" in refused.out, refused.out)
        assertTrue(fixture.daemon.store.traits.get("report-back") != null, "a refused delete keeps the trait")

        fixture.cli("role", "traits", "backend", "--clear").also { assertEquals(0, it.exit, it.err) }
        val deleted = fixture.cli("trait", "delete", "report-back")
        assertEquals(0, deleted.exit, deleted.err)
        assertEquals("deleted report-back", deleted.out.trim())
        assertEquals(null, fixture.daemon.store.traits.get("report-back"))
    }

    @Test
    fun usageSlipsAreRefusedBeforeConnecting() {
        listOf(
            arrayOf("trait") to "trait needs a subcommand",
            arrayOf("trait", "save", "x") to "trait save requires --body",
            arrayOf("trait", "save", "--body", "b") to "trait save requires a trait id",
            arrayOf("trait", "save", "x", "--body", "b", "--report-to", "pm") to "--report-to needs --report-fields",
            arrayOf("trait", "show") to "trait show requires a trait id",
            arrayOf("trait", "delete") to "trait delete requires a trait id",
            arrayOf("role") to "role needs a subcommand",
            arrayOf("role", "traits") to "role traits requires a role id",
            arrayOf("role", "traits", "backend", "--set", "a", "--clear") to "--set or --clear, not both",
        ).forEach { (args, expected) ->
            val r = usage(*args)
            assertEquals(2, r.exit, "${args.joinToString(" ")}: ${r.err}")
            assertTrue(expected in r.err, "${args.joinToString(" ")}: ${r.err}")
            assertFalse("not running" in r.err, "${args.joinToString(" ")} connected: ${r.err}")
        }
    }
}
