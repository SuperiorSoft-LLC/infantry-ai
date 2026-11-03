// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.TestWait
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-CLI-1: a ticket description reaches the board exactly as given: a multi-line `--desc`, `--desc-file <path>` and
 * `--desc -` (stdin), markdown list items starting with '-' included. Over a REAL daemon and the REAL [Cli.run]; the
 * ticket is read back from the daemon's board, never from what the CLI printed.
 *
 * Also the contract rule: only a different MAJOR contract version warns.
 */
class CliTicketDescriptionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    /** No leading/trailing whitespace: the board trims those, everything in between must survive. */
    private val brief = "## Goal\nKeep every line.\n- first item\n- second item\n\n-- not a flag\nlast line"

    private fun launch(): DaemonFixture = DaemonFixture.launch(
        StubScript.default(),
        null,
        Files.createTempDirectory("infantry-ticket-desc-cli-test").toFile(),
    ).also { fixtures += it }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String, stdin: String? = null): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val input: () -> Reader = { StringReader(stdin ?: error("this call must not read stdin")) }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { null }, input = input)
        return Result(exit, out.toString(), err.toString())
    }

    private var listings = 0

    private fun stored(fixture: DaemonFixture, title: String): Ticket? {
        fixture.send(Envelope(Envelope.TICKET_LIST, ""))
        val frame = fixture.client.await(Envelope.TICKETS, occurrence = listings++, timeoutMs = TestWait.defaultMs)
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data).singleOrNull { it.title == title }
    }

    private fun tempFile(text: String) = Files.createTempFile("infantry-desc", ".md").toFile().apply {
        writeText(text)
        deleteOnExit()
    }

    @Test
    fun aThreeLineDescSavesAllThreeLines() {
        val fixture = launch()
        val r = cli(fixture.port, "ticket", "create", "--title", "three", "--desc", "line one\nline two\nline three")
        assertEquals(0, r.exit, r.err)
        assertEquals("line one\nline two\nline three", stored(fixture, "three")?.description)
    }

    @Test
    fun aDescStartingWithADashIsAValueNotAFlag() {
        val fixture = launch()
        val r = cli(fixture.port, "ticket", "create", "--title", "dash", "--desc", "- item one\n- item two")
        assertEquals(0, r.exit, r.err)
        assertEquals("- item one\n- item two", stored(fixture, "dash")?.description)
    }

    @Test
    fun descFileSavesTheExactContent() {
        val fixture = launch()
        val r = cli(fixture.port, "ticket", "create", "--title", "from file", "--desc-file", tempFile(brief).path)
        assertEquals(0, r.exit, r.err)
        assertEquals(brief, stored(fixture, "from file")?.description)
    }

    @Test
    fun descDashReadsStdinVerbatim() {
        val fixture = launch()
        val r = cli(fixture.port, "ticket", "create", "--title", "from stdin", "--desc", "-", stdin = brief)
        assertEquals(0, r.exit, r.err)
        assertEquals(brief, stored(fixture, "from stdin")?.description)
    }

    @Test
    fun updateTakesDescFileAndStdin() {
        val fixture = launch()
        assertEquals(0, cli(fixture.port, "ticket", "create", "--title", "edit me", "--desc", "old").exit)
        val id = assertNotNull(stored(fixture, "edit me")).id

        val viaFile = cli(fixture.port, "ticket", "update", id, "--desc-file", tempFile(brief).path)
        assertEquals(0, viaFile.exit, viaFile.err)
        assertEquals(brief, stored(fixture, "edit me")?.description)

        val viaStdin = cli(fixture.port, "ticket", "update", id, "--desc", "-", stdin = "- only\n- stdin")
        assertEquals(0, viaStdin.exit, viaStdin.err)
        assertEquals("- only\n- stdin", stored(fixture, "edit me")?.description)
    }

    @Test
    fun aMissingDescFileIsAUsageErrorBeforeConnecting() {
        val r = cli(1, "ticket", "create", "--title", "t", "--desc-file", "no/such/file.md")
        assertEquals(2, r.exit, r.err)
        assertTrue("--desc-file" in r.err, r.err)
    }

    @Test
    fun twoDescriptionSourcesAreRefused() {
        val r = cli(1, "ticket", "create", "--title", "t", "--desc", "a", "--desc-file", "x.md")
        assertEquals(2, r.exit, r.err)
        assertTrue("give the description once" in r.err, r.err)
    }

    @Test
    fun helpRecommendsDescFileForMultiLineText() {
        val out = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("ticket", "create", "--help"), out, StringBuilder(), getenv = { null }))
        assertTrue("Multi-line description: use --desc-file <path>" in out, out.toString())
    }

    @Test
    fun anOlderCliBuildIsToldWhichLauncherToUse() {
        val ready = "contract=1.78.0 build=1.0.1+20261024.1000.abcdef1"
        val line = assertNotNull(Cli.olderCliNotice("1.0.1+20261023.1432.e85b223", ready, "C:/app/bin"))
        assertTrue("CLI is older than the daemon" in line && "C:/app/bin" in line, line)
        assertNull(Cli.olderCliNotice("1.0.1+20261024.1000.abcdef1", ready, null))
    }

    @Test
    fun theSameContractVersionSaysNothing() {
        assertNull(Cli.contractNotice("1.78.0", "provider=stub contract=1.78.0 instance=i since=0"))
    }

    @Test
    fun anEqualMajorIsAHintNotAWarning() {
        val notice = assertNotNull(Cli.contractNotice("1.78.0", "provider=stub contract=1.76.0 instance=i since=0"))
        assertFalse(notice.major, notice.line)
        assertFalse(notice.line.startsWith("warning"), notice.line)
        assertEquals("hint: CLI 1.78, daemon 1.76: rebuild/reinstall both for new features", notice.line)
    }

    @Test
    fun aDifferentMajorWarns() {
        val notice = assertNotNull(Cli.contractNotice("1.78.0", "provider=stub contract=2.0.0 instance=i since=0"))
        assertTrue(notice.major, notice.line)
        assertTrue(notice.line.startsWith("warning"), notice.line)
    }

    @Test
    fun aRealDaemonOfThisBuildGivesNoContractLine() {
        val fixture = launch()
        val r = cli(fixture.port, "tickets")
        assertEquals(0, r.exit, r.err)
        assertFalse("contract" in r.err, "same build, same contract ${EventVocabulary.VERSION}: ${r.err}")
    }
}
