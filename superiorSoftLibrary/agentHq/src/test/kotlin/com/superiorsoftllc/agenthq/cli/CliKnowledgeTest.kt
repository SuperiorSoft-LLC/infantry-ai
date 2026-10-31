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
