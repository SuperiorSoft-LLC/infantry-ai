package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.update.BuildInfo
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-CLI-4: `infantry --version` / `infantry version` prints `infantry <cli contract> build <cli build> (daemon: <contract> build <build>|not running)`
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
        val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("infantry-cli-version").toFile())
            .also { fixtures += it }
        // The contract the daemon itself announces, read from its own ready frame, not assumed equal to the CLI's.
        val readyFields = fixture.client.await(Envelope.READY).data.split(' ')
        val daemonContract = readyFields.firstOrNull { it.startsWith("contract=") }?.removePrefix("contract=")
        assertNotNull(daemonContract, "the stub daemon's ready frame names its contract")
        // BUILD-STAMP-1: and its build, the same string this JVM resolves (the fixture daemon runs in-process).
        val daemonBuild = readyFields.firstOrNull { it.startsWith("build=") }?.removePrefix("build=")
        assertEquals(BuildInfo.current, daemonBuild, "the ready frame carries build=<the daemon's build string>")

        for (form in listOf(arrayOf("--version"), arrayOf("version"))) {
            val result = cli(fixture.port, *form)
            assertEquals(0, result.exit, "${form.toList()} must exit 0, err: ${result.err}")
            assertEquals(
                "infantry ${EventVocabulary.VERSION} build ${BuildInfo.current} (daemon: $daemonContract build $daemonBuild)",
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
        assertEquals("infantry ${EventVocabulary.VERSION} build ${BuildInfo.current} (daemon: not running)", result.out.trim())
    }

    @Test
    fun versionLineShowsBothBuildsAndCopesWithAnOlderDaemon() {
        val build = "1.0.1+20261023.1432.e85b223"
        assertEquals(
            "infantry ${EventVocabulary.VERSION} build $build (daemon: 1.115.0 build 1.0.1+20261022.0900.abc1234.dirty)",
            Cli.versionLine(build, ReadyInfo("1.115.0", "1.0.1+20261022.0900.abc1234.dirty")),
        )
        // A daemon from before BUILD-STAMP-1 sends no build=: its contract alone, as before.
        assertEquals("infantry ${EventVocabulary.VERSION} build $build (daemon: 1.110.0)", Cli.versionLine(build, ReadyInfo("1.110.0", null)))
        assertEquals("infantry ${EventVocabulary.VERSION} build $build (daemon: not running)", Cli.versionLine(build, null))
    }

    @Test
    fun helpDocumentsTheVersionFlag() {
        val out = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("--help"), out, StringBuilder(), CliTestEnv.getenv))
        assertTrue(out.contains("--version"), "--help lists --version: $out")
    }
}
