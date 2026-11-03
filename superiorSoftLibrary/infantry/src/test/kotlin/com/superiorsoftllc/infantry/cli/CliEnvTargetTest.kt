package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.InfantryBin
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CLI-ENV-1: INFANTRY_PORT / INFANTRY_STATE_DIR pick the daemon, and a named target that is unusable fails closed. */
class CliEnvTargetTest {
    private fun env(vararg p: Pair<String, String>): (String) -> String? = p.toMap()::get

    @Test
    fun envPortBeatsTheDefaultAndTheInstalledDaemon() {
        assertEquals("ws://127.0.0.1:8812/ws", Cli.targetUrl(arrayOf("pods"), env("INFANTRY_PORT" to "8812")))
        assertEquals(
            "ws://127.0.0.1:8812/ws",
            Cli.targetUrl(arrayOf("pods"), env("INFANTRY_PORT" to "8812", "INFANTRY_INSTALL_PORT" to "8799")),
        )
    }

    @Test
    fun precedenceIsFlagThenUrlThenEnvPortThenStateDir() {
        val dir = Files.createTempDirectory("cli-env-1").toFile().also { it.resolve("daemon.port").writeText("8813") }
        val e = env("INFANTRY_PORT" to "8812", "INFANTRY_STATE_DIR" to dir.path)
        assertEquals("ws://127.0.0.1:5/ws", Cli.targetUrl(arrayOf("pods", "--port", "5"), e))
        assertEquals("ws://127.0.0.1:8812/ws", Cli.targetUrl(arrayOf("pods"), e), "INFANTRY_PORT beats the state dir")
        assertEquals(
            "ws://127.0.0.1:8813/ws",
            Cli.targetUrl(arrayOf("pods"), env("INFANTRY_STATE_DIR" to dir.path)),
        )
        assertEquals(
            "ws://127.0.0.1:9/ws",
            Cli.targetUrl(arrayOf("pods"), env(InfantryBin.URL_ENV to "ws://127.0.0.1:9/ws", "INFANTRY_PORT" to "8812")),
        )
    }

    @Test
    fun envPortReachesTheFakeDaemonNotTheDefault() {
        val accepted = AtomicInteger()
        ServerSocket(0).use { fake ->
            val t = thread(isDaemon = true) {
                runCatching { while (true) fake.accept().use { accepted.incrementAndGet() } }
            }
            Cli.run(arrayOf("pods"), StringBuilder(), StringBuilder(), env("INFANTRY_PORT" to fake.localPort.toString()))
            fake.close()
            t.join(2_000)
        }
        assertTrue(accepted.get() >= 1, "the CLI connected to the INFANTRY_PORT daemon")
    }

    @Test
    fun unreachableEnvTargetFailsAndNamesIt() {
        val closed = ServerSocket(0).use { it.localPort }
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("pods"), StringBuilder(), err, env("INFANTRY_PORT" to closed.toString()))
        assertTrue(exit != 0, "no fallback to a default daemon: $err")
        assertTrue(closed.toString() in err, "the message names the env target: $err")
    }

    @Test
    fun stateDirWithoutADaemonFailsClosed() {
        val dir = Files.createTempDirectory("cli-env-1-empty").toFile()
        val e = env("INFANTRY_STATE_DIR" to dir.path)
        assertFailsWith<Exception> { Cli.targetUrl(arrayOf("pods"), e) }
        val err = StringBuilder()
        assertTrue(Cli.run(arrayOf("pods"), StringBuilder(), err, e) != 0)
        assertTrue("Not falling back" in err, err.toString())
    }

    @Test
    fun badEnvPortFailsClosed() {
        val err = StringBuilder()
        assertTrue(Cli.run(arrayOf("pods"), StringBuilder(), err, env("INFANTRY_PORT" to "abc")) != 0)
        assertTrue("not falling back" in err, err.toString())
    }
}
