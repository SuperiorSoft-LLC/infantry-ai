package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.assertNotEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-REAL: the production desktop entry must run against the REAL daemon, provider OpenCode by default.
 * These tests need no model and no CLI: they pin the provider resolution and the connection URL that the
 * entry hands to the shell.
 */
class RealDaemonLaunchTest {
    private val entry = File("src/main/kotlin/com/superiorsoftllc/agenthq/ui/RealDaemonMain.kt")

    @Test fun unsetProviderDefaultsToNone() {
        assertEquals("none", RealDaemonLaunch.providerId { null })
    }

    @Test fun blankProviderDefaultsToNone() {
        assertEquals("none", RealDaemonLaunch.providerId { "   " })
    }

    @Test fun explicitProviderIsHonoured() {
        assertEquals("stub", RealDaemonLaunch.providerId { if (it == ProviderFactory.ENV) "stub" else null })
    }

    @Test fun shellConnectsToTheDaemonPortOnLoopback() {
        assertEquals("ws://127.0.0.1:8787/ws", RealDaemonLaunch.wsUrl(8787))
        assertEquals("ws://127.0.0.1:8787/ws", RealDaemonLaunch.wsUrl(8787, "0.0.0.0"))
    }

    /**
     * PORT-1: the configured port is taken (a leftover daemon on 8788, in the owner's case). The launcher still
     * starts, and the URL it hands the shell is the port the daemon ACTUALLY bound, which also sits in daemon.port.
     */
    @Test fun launcherConnectsToTheFallbackPortWhenTheConfiguredOneIsTaken() {
        val dir = Files.createTempDirectory("agenthq-ui-port").toFile()
        val portFile = File(dir, "daemon.port")
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { held ->
            val taken = held.localPort
            val provider = StubProvider(StubScript.default(), delayMs = 0)
            val daemon = AgentHqDaemon.build(
                port = taken, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
                sessionStore = JsonSessionStore(File(dir, "sessions.json")),
                mailStore = JsonMailStore(File(dir, "mailbox.json")),
                store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
                mailBaseUrl = "http://127.0.0.1:1", token = null,
                modelCatalogs = HarnessModelCatalogs.EMPTY,
                strictPort = false,
                portFile = portFile,
            )
            try {
                val wsUrl = RealDaemonLaunch.start(daemon)
                val bound = URI(wsUrl).port
                assertNotEquals(taken, bound, "the shell must not be pointed at the taken port")
                assertEquals("ws://127.0.0.1:$bound/ws", wsUrl)
                assertEquals("$bound", portFile.readText().trim(), "daemon.port names the same port the shell uses")
                val health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$bound/health")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, health.statusCode(), "the URL reaches the daemon this launcher started")
            } finally {
                daemon.server.stop(0, 200)
                daemon.close()
            }
        }
        assertTrue(!portFile.exists(), "a clean shutdown removes daemon.port")
        dir.deleteRecursively()
    }

    @Test fun realEntryBootsTheRealDaemonNotTheStub() {
        assertTrue(entry.exists(), "RealDaemonMain.kt must exist in src/main")
        val text = entry.readText()
        assertTrue("AgentHqDaemon.fromEnv()" in text, "the entry must boot the real daemon from the environment")
        assertTrue("StubProvider" !in text && "AgentHqDaemon.build(" !in text, "the entry must not inject the stub")
    }

    @Test fun stubSmokeEntryStaysUntouched() {
        val smoke = File("src/test/kotlin/com/superiorsoftllc/agenthq/ui/SmokeMain.kt").readText()
        assertTrue("StubProvider" in smoke, "SmokeMain keeps the stub path")
    }

    /** E2E-OWNER-1: the owner's preview opens the window; only an explicit AGENTHQ_HEADLESS runs without one. */
    @Test fun theWindowOpensUnlessHeadlessIsAskedFor() {
        listOf(null, "", "   ", "0", "false", "no", "headless").forEach { value ->
            assertFalse(RealDaemonLaunch.headless { if (it == RealDaemonLaunch.HEADLESS) value else null }, "AGENTHQ_HEADLESS=$value")
        }
    }

    @Test fun headlessAcceptsTheUsualYesValues() {
        listOf("1", "true", "TRUE", " yes ").forEach { value ->
            assertTrue(RealDaemonLaunch.headless { if (it == RealDaemonLaunch.HEADLESS) value else null }, "AGENTHQ_HEADLESS=$value")
        }
    }

    /** The headless run holds the daemon while its stdin is open, and returns (so the daemon stops) once it closes. */
    @Test fun aHeadlessRunStopsWhenStdinCloses() {
        val stdin = PipedOutputStream()
        val input = PipedInputStream(stdin)
        val run = thread(isDaemon = true, name = "headless-run") { RealDaemonLaunch.awaitStdinEof(input) }
        stdin.write("not a command\n".toByteArray())
        stdin.flush()
        run.join(300)
        assertTrue(run.isAlive, "input that is not EOF keeps the daemon running")
        stdin.close()
        run.join(5_000)
        assertFalse(run.isAlive, "closing stdin ends the headless run")
    }
}
