package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PORT-1: a taken port no longer kills the daemon. It listens on a free one, records it in `daemon.port`, and
 * removes that file on clean shutdown; `INFANTRY_PORT_STRICT=true` refuses with one readable line instead.
 *
 * Every port here is OS-assigned (an occupied ephemeral one), never 8787/8788 ([TestPortGuardTest]).
 */
class DaemonPortFallbackTest {

    /** A port something else is listening on, for the duration of [block]. */
    private fun withOccupiedPort(block: (Int) -> Unit) =
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { held -> block(held.localPort) }

    @Test fun aTakenPortFallsBackToAFreeOneAndRecordsIt() = withOccupiedPort { taken ->
        val stateDir = Files.createTempDirectory("infantry-port-test").toFile()
        val portFile = File(stateDir, DaemonPort.FILE_NAME)
        // Before PORT-1 this launch threw java.net.BindException: Address already in use.
        val fixture = DaemonFixture.launch(stateDir = stateDir, port = taken, portFile = portFile)
        try {
            assertNotEquals(taken, fixture.port, "the daemon must not claim the taken port")
            assertEquals(fixture.port, fixture.daemon.port, "the daemon reports the port it chose, not the one asked for")
            assertEquals(fixture.port, DaemonPort.read(portFile), "daemon.port holds the port actually bound")
            fixture.client.await(Envelope.READY)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** Bind [port] on loopback the way any other socket on the host would, and let go again. */
    private fun bindExplicitly(port: Int) = ServerSocket().use { it.bind(InetSocketAddress(loopback, port)) }

    /**
     * BUG-FLAKE-1: the fallback stays this daemon's from build() to start(). The probe used to release it at once,
     * and under a full gate another socket took it in that gap: on Windows bind(0) hands ports out in sequence, so
     * the released fallback was the next port anyone got, and start() died on a BindException.
     */
    @Test fun theFallbackPortStaysTheDaemonsFromBuildToStart() = withOccupiedPort { taken ->
        val stateDir = Files.createTempDirectory("infantry-port-test").toFile()
        var explicit: Result<Unit>? = null
        var ephemeral = 0
        val fixture = DaemonFixture.launch(stateDir = stateDir, port = taken, beforeStart = { daemon ->
            explicit = runCatching { bindExplicitly(daemon.port) }
            ephemeral = ServerSocket(0, 50, loopback).use { it.localPort }
        })
        try {
            assertNotEquals(taken, fixture.port, "the daemon must not claim the taken port")
            assertTrue(explicit!!.exceptionOrNull() is BindException, "another socket bound port ${fixture.port} between build() and start(): $explicit")
            assertNotEquals(fixture.port, ephemeral, "the OS handed port ${fixture.port} to another socket between build() and start()")
            assertEquals(fixture.daemon.port, fixture.port, "start() binds the port build() chose")
            fixture.client.await(Envelope.READY)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test fun cleanShutdownRemovesThePortFile() {
        val stateDir = Files.createTempDirectory("infantry-port-test").toFile()
        val portFile = File(stateDir, DaemonPort.FILE_NAME)
        val fixture = DaemonFixture.launch(stateDir = stateDir, portFile = portFile)
        assertEquals(fixture.port, DaemonPort.read(portFile))
        fixture.stop()
        assertFalse(portFile.exists(), "a clean shutdown deletes daemon.port")
        fixture.deleteState()
    }

    @Test fun releaseLeavesAnotherDaemonsPortFileAlone() {
        val file = Files.createTempFile("daemon", ".port").toFile().apply { deleteOnExit() }
        DaemonPort.write(file, 40001)
        DaemonPort.release(file, 40002)
        assertEquals(40001, DaemonPort.read(file), "a second daemon on the same state dir keeps its record")
        DaemonPort.release(file, 40001)
        assertNull(DaemonPort.read(file))
    }

    @Test fun aFreePortIsKeptWithNoNote() {
        val free = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val choice = DaemonPort.choose(free, "127.0.0.1", strict = false, pidOf = { null })
        assertEquals(DaemonPort.Choice(free, free, null), choice)
        // BUG-PORT-0: 0 resolves to a real OS-assigned port up front, with no note (it is not a fallback).
        val ephemeral = DaemonPort.choose(0, "127.0.0.1", strict = false)
        assertEquals(0, ephemeral.requested)
        assertTrue(ephemeral.port > 0, "0 resolves to a real port: $ephemeral")
        assertNull(ephemeral.note)
    }

    /**
     * BUG-FLAKE-1: a free requested port is held too, not only a fallback, and closing gives it back. choose()
     * holds nothing: fromEnv's strict pre-check runs it before build() reserves the same port.
     */
    @Test fun aFreeRequestedPortIsHeldUntilTheReservationClosesAndChooseHoldsNothing() {
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        DaemonPort.reserve(free, "127.0.0.1", strict = false, pidOf = { null }).use { reservation ->
            assertEquals(DaemonPort.Choice(free, free, null), reservation.choice)
            assertFailsWith<BindException>("reserve() must keep $free bound") { bindExplicitly(free) }
        }
        bindExplicitly(free)
        assertEquals(DaemonPort.Choice(free, free, null), DaemonPort.choose(free, "127.0.0.1", strict = false, pidOf = { null }))
        bindExplicitly(free)
    }

    @Test fun theFallbackIsLoggedAsOneLineNamingBothPortsAndThePid() = withOccupiedPort { taken ->
        val choice = DaemonPort.choose(taken, "127.0.0.1", strict = false, pidOf = { 4242L })
        assertEquals("port $taken in use (pid 4242); listening on ${choice.port} instead", choice.note)
    }

    @Test fun strictModeRefusesWithAReadableMessage() = withOccupiedPort { taken ->
        val refused = assertFailsWith<PortInUseException> {
            DaemonPort.choose(taken, "127.0.0.1", strict = true, pidOf = { null })
        }
        assertEquals(
            "port $taken in use (pid unknown) and INFANTRY_PORT_STRICT=true forbids a fallback. " +
                "Stop whatever holds it, or unset INFANTRY_PORT_STRICT to let the daemon pick a free port.",
            refused.message,
        )
    }

    /**
     * The real `main`, in its own JVM: strict + taken port exits 2 with one readable line, no stack trace, and
     * without having opened the database.
     */
    @Test fun strictDaemonProcessExitsWithTheReadableLine() = withOccupiedPort { taken ->
        val stateDir = Files.createTempDirectory("infantry-port-strict").toFile()
        try {
            // The test classpath can exceed the Windows command-line limit, so it travels in an @argfile.
            val argFile = File(stateDir, "java.args").apply {
                writeText("-cp\n\"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"\n")
            }
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val builder = ProcessBuilder(java, "@${argFile.absolutePath}", "com.superiorsoftllc.infantry.daemon.DaemonMainKt")
            builder.environment().apply {
                listOf("INFANTRY_HOST", "INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE", "INFANTRY_DB", "INFANTRY_MAIL_BASE").forEach(::remove)
                put("INFANTRY_PORT", taken.toString())
                put("INFANTRY_PORT_STRICT", "true")
                put("INFANTRY_STATE_DIR", stateDir.absolutePath)
                put("INFANTRY_PROVIDER", "stub")
            }
            val process = builder.start()
            val stderr = process.errorStream.bufferedReader().readText()
            process.inputStream.close()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the strict daemon must exit, not hang")
            assertEquals(2, process.exitValue(), stderr)
            assertTrue("[infantry] refusing to start: port $taken in use" in stderr, stderr)
            assertTrue("INFANTRY_PORT_STRICT=true" in stderr, stderr)
            assertFalse("Exception" in stderr || "\tat " in stderr, "no stack trace: $stderr")
            assertFalse(File(stateDir, "infantry.db").exists(), "refused before the database was opened")
        } finally {
            stateDir.deleteRecursively()
        }
    }
}
