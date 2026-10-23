package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P10-K3: `agenthq memory index / show / search / save` over a REAL daemon and the REAL [Cli.run]
 * (the [CliEpicStoryListTest] pattern). The save output is metadata, `chars` and the tail / excerpt,
 * never the body; a refusal prints the frame's text, code first, and exits non-zero.
 */
class CliMemoryTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("agenthq-memory-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, stdin: String = "", project: String? = "P"): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap { if (project != null) put(AccessPolicy.PROJECT_ID_ENV, project) }
        val exit = Cli.run(
            arrayOf(*args, "--port", port.toString()),
            out,
            err,
            getenv = { env[it] },
            input = { StringReader(stdin) },
        )
        return Result(exit, out.toString(), err.toString())
    }

    private fun DaemonFixture.body(name: String): String? = daemon.memoryNotes.get("P", name)?.body

    @Test
    fun saveShowIndexSearchRoundTrip() {
        val fixture = launch()
        val saved = fixture.cli("memory", "save", "runbook", "--description", "how to deploy", "--folder", "ops",
            "--content", "SECRET-BODY step one")
        assertEquals(0, saved.exit, saved.err)
        assertTrue("ops/runbook" in saved.out, saved.out)
        assertTrue("chars: 20" in saved.out, saved.out)
        assertFalse("SECRET-BODY" in saved.out, "a replace prints metadata, never the body: ${saved.out}")

        fixture.cli("memory", "save", "aaa-root", "--description", "root note", "--content", "x").also { assertEquals(0, it.exit, it.err) }

        val show = fixture.cli("memory", "show", "runbook")
        assertEquals(0, show.exit, show.err)
        assertTrue("SECRET-BODY step one" in show.out, show.out)
        assertTrue("ops" in show.out && "how to deploy" in show.out, show.out)

        val index = fixture.cli("memory", "index")
        assertEquals(0, index.exit, index.err)
        assertEquals(listOf("aaa-root: root note", "ops/runbook: how to deploy"), index.out.lines().filter { it.isNotBlank() })

        val search = fixture.cli("memory", "search", "step")
        assertEquals(0, search.exit, search.err)
        assertTrue("runbook" in search.out && "step one" in search.out, search.out)
    }

    @Test
    fun appendPrintsTheTailNotTheBody() {
        val fixture = launch()
        fixture.daemon.memoryNotes.save("P", "log", "a log", "HEAD-OF-BODY " + "x".repeat(500))
        val r = fixture.cli("memory", "save", "log", "--append", "--content", "the newest line")
        assertEquals(0, r.exit, r.err)
        assertTrue("the newest line" in r.out, r.out)
        assertFalse("HEAD-OF-BODY" in r.out, r.out)
        assertTrue(fixture.body("log")!!.endsWith("x\n\nthe newest line"))
    }

    @Test
    fun editPrintsAnExcerptAroundTheChange() {
        val fixture = launch()
        fixture.daemon.memoryNotes.save("P", "doc", "d", "FAR-AWAY-HEAD " + "y".repeat(400) + " port 8080 " + "z".repeat(400))
        val r = fixture.cli("memory", "save", "doc", "--edit", "--find", "port 8080", "--content", "port 9090")
        assertEquals(0, r.exit, r.err)
        assertTrue("port 9090" in r.out, r.out)
        assertFalse("FAR-AWAY-HEAD" in r.out, r.out)
        assertTrue(" port 9090 " in fixture.body("doc")!!)
    }

    @Test
    fun aRefusalIsPrintedWithItsCodeAndExitsNonZero() {
        val fixture = launch()
        fixture.daemon.memoryNotes.save("P", "doc", "d", "aaa")
        val ambiguous = fixture.cli("memory", "save", "doc", "--edit", "--find", "aa", "--content", "b")
        assertEquals(1, ambiguous.exit)
        assertTrue("memory_save refused: anchor_ambiguous: " in ambiguous.err, ambiguous.err)

        val missing = fixture.cli("memory", "save", "ghost", "--append", "--content", "b")
        assertEquals(1, missing.exit)
        assertTrue("note_not_found" in missing.err, missing.err)

        val show = fixture.cli("memory", "show", "ghost")
        assertEquals(1, show.exit)
        assertTrue("memory_get refused: note_not_found: " in show.err, show.err)
        assertEquals("aaa", fixture.body("doc"))
    }

    @Test
    fun bodiesComeFromAFileOrStdin() {
        val fixture = launch()
        val file = File.createTempFile("agenthq-memory-body", ".md").apply {
            writeText("- a markdown list\n- \"quoted\" `ticks` \$dollar\n")
            deleteOnExit()
        }
        val fromFile = fixture.cli("memory", "save", "f", "--description", "d", "--file", file.absolutePath)
        assertEquals(0, fromFile.exit, fromFile.err)
        assertEquals("- a markdown list\n- \"quoted\" `ticks` \$dollar\n", fixture.body("f"))

        val fromStdin = fixture.cli("memory", "save", "f", "--append", "-", stdin = "- from stdin")
        assertEquals(0, fromStdin.exit, fromStdin.err)
        assertTrue(fixture.body("f")!!.endsWith("\$dollar\n\n- from stdin"), fixture.body("f"))
    }

    @Test
    fun usageErrorsExitTwoBeforeConnecting() {
        val fixture = launch()
        assertEquals(2, fixture.cli("memory").exit)
        assertEquals(2, fixture.cli("memory", "show").exit)
        assertEquals(2, fixture.cli("memory", "index", project = null).exit)
        assertEquals(2, fixture.cli("memory", "save", "n", "--content", "x").exit, "replace needs a description")
        assertEquals(2, fixture.cli("memory", "save", "n", "--edit", "--content", "x").exit, "edit needs --find")
        assertEquals(2, fixture.cli("memory", "save", "n", "--append", "--edit", "--find", "a", "--content", "x").exit)
        assertEquals(2, fixture.cli("memory", "save", "n", "--append").exit, "no content source")
    }

    @Test
    fun aLargeIndexWalksDownFolders() {
        val fixture = launch()
        repeat(30) { fixture.daemon.memoryNotes.save("P", "a-note-%02d".format(it), "cue", "x", folder = "alpha") }
        repeat(30) { fixture.daemon.memoryNotes.save("P", "b-note-%02d".format(it), "cue", "x", folder = "beta/deep") }
        fixture.daemon.memoryNotes.save("P", "root-note", "the root cue", "x")

        val top = fixture.cli("memory", "index")
        assertEquals(0, top.exit, top.err)
        val lines = top.out.lines().filter { it.isNotBlank() }
        assertTrue("alpha/ (30 notes)" in lines, top.out)
        assertTrue("beta/ (30 notes)" in lines, top.out)
        assertTrue("root-note: the root cue" in lines, top.out)
        assertFalse(lines.any { it.startsWith("alpha/a-note") }, "above 60 notes the top level lists folders, not notes")

        val beta = fixture.cli("memory", "index", "--folder", "beta")
        assertEquals(0, beta.exit, beta.err)
        val betaLines = beta.out.lines().filter { it.isNotBlank() }
        assertEquals(30, betaLines.size, beta.out)
        assertTrue(betaLines.all { it.startsWith("beta/deep/b-note-") }, beta.out)
    }
}
