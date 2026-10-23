package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.runner.StubScript
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-CLI-4: `agenthq --version` / `agenthq version` prints `agenthq <cli contract> (daemon: <contract|not running>)`
 * and exits 0, so anyone can tell which CLI and daemon build they have. Same harness as [CliAcceptanceTest]: a REAL
 * stub daemon on an ephemeral port and the REAL [Cli.run]. Never the default port 8787.
 */
class CliVersionTest {

    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, CliTestEnv.getenv)
        return Result(exit, out.toString(), err.toString())
    }

    @Test
    fun versionFlagPrintsTheCliAndTheRunningDaemonsContract() {
        val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-cli-version").toFile())
            .also { fixtures += it }
        // The contract the daemon itself announces, read from its own ready frame, not assumed equal to the CLI's.
        val daemonContract = fixture.client.await(Envelope.READY).data.split(' ')
            .firstOrNull { it.startsWith("contract=") }?.removePrefix("contract=")
        assertNotNull(daemonContract, "the stub daemon's ready frame names its contract")

        for (form in listOf(arrayOf("--version"), arrayOf("version"))) {
            val result = cli(fixture.port, *form)
            assertEquals(0, result.exit, "${form.toList()} must exit 0, err: ${result.err}")
            assertEquals(
                "agenthq ${EventVocabulary.VERSION} (daemon: $daemonContract)",
                result.out.trim(),
                "${form.toList()} prints one line naming both builds; err: ${result.err}",
            )
        }
    }

    @Test
    fun versionWithNoDaemonSaysNotRunningAndStillExitsZero() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val result = cli(deadPort, "--version")
        assertEquals(0, result.exit, "--version must not fail without a daemon, err: ${result.err}")
        assertEquals("agenthq ${EventVocabulary.VERSION} (daemon: not running)", result.out.trim())
    }

    @Test
    fun helpDocumentsTheVersionFlag() {
        val out = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("--help"), out, StringBuilder(), CliTestEnv.getenv))
        assertTrue(out.contains("--version"), "--help lists --version: $out")
    }
}
