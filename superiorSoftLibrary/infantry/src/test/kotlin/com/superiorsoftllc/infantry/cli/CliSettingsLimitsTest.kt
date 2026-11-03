package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.TestWait
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No-hard-coded-limits (contract 1.133.0): `infantry settings` / `settings set` / `settings limits` over a REAL daemon and
 * the REAL [Cli.run]: a limit is listed with its default and env name, a change applies at once, a bad value is refused
 * with its range.
 */
class CliSettingsLimitsTest {
    /** No test here may hang a fork. */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-settings-cli-test").toFile())
            .also { fixtures += it; it.client.await(Envelope.READY) }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = mapOf(ANSWER_TIMEOUT_ENV to TestWait.defaultMs.toString())
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    @Test
    fun limitsAreListedSetAndRefusedWithTheirRange() {
        val daemon = launch()
        val limits = daemon.cli("settings", "limits")
        assertEquals(0, limits.exit, limits.err)
        assertTrue("limits.concurrency.maxPerPod = 4 sessions (default 4, range 0..no ceiling; 0 = unlimited)" in limits.out, limits.out)
        assertTrue("env INFANTRY_LIMIT_CONCURRENCY_MAX_PER_POD" in limits.out, limits.out)
        assertTrue("limits.watchdog.ticketDeadlineMinutes = 180 minutes" in limits.out, limits.out)

        val set = daemon.cli("settings", "set", "limits.concurrency.maxPerPod", "12")
        assertEquals(0, set.exit, set.err)
        assertTrue("limits.concurrency.maxPerPod = 12" in set.out, set.out)
        val get = daemon.cli("settings", "get", "limits.concurrency.maxPerPod")
        assertEquals("limits.concurrency.maxPerPod = 12", get.out.trim())

        val bad = daemon.cli("settings", "set", "limits.cpu.alertPercent", "500")
        assertEquals(1, bad.exit, bad.out)
        assertTrue("limits.cpu.alertPercent" in bad.err && "1..100" in bad.err, bad.err)

        val usage = daemon.cli("settings", "frobnicate")
        assertEquals(2, usage.exit)
        assertTrue(SettingsCli.USAGE in usage.err, usage.err)
    }
}
