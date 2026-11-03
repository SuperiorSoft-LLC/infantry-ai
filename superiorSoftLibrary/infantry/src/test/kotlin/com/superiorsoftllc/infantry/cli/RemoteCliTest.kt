package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client side of Phase 7, driven through the REAL [Cli.run] against REAL daemons over REAL sockets:
 * the watch that survives a dropped connection, the refused-credential messages, the endpoints file that
 * reaches several daemons, and the warning about a token sent over plain ws://.
 */
class RemoteCliTest {

    private val fixtures = mutableListOf<DaemonFixture>()
    private val relays = mutableListOf<TcpRelay>()
    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        relays.forEach { runCatching { it.close() } }
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        files.forEach { runCatching { it.delete() } }
        relays.clear()
        fixtures.clear()
        files.clear()
    }

    private fun launch(token: String?): DaemonFixture =
        DaemonFixture.launch(runner = GatedRunner(), token = token).also { fixtures += it }

    private fun DaemonFixture.url(): String = "ws://$connectHost:$port/ws"

    private fun tokenFile(token: String, bom: Boolean = false): File =
        writeTokenFile(token, bom).also { files += it }

    /**
     * A TCP pass-through in front of a daemon. [dropAll] severs every live connection while the listener
     * keeps accepting: that is what a flaky network does to a client, and the next dial gets through.
     */
    private class TcpRelay(private val targetPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val live = CopyOnWriteArrayList<Socket>()

        @Volatile
        private var listening = true

        val port: Int = server.localPort

        init {
            thread(isDaemon = true, name = "tcp-relay-accept") {
                while (listening) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    val upstream = try {
                        Socket("127.0.0.1", targetPort)
                    } catch (e: IOException) {
                        client.close()
                        continue
                    }
                    live += client
                    live += upstream
                    pipe(client, upstream)
                    pipe(upstream, client)
                }
            }
        }

        private fun pipe(from: Socket, to: Socket) {
            thread(isDaemon = true, name = "tcp-relay-pipe") {
                try {
                    from.getInputStream().copyTo(to.getOutputStream())
                } catch (e: IOException) {
                    // The socket was severed; closing both ends below is the whole response.
                } finally {
                    runCatching { from.close() }
                    runCatching { to.close() }
                }
            }
        }

        fun dropAll() {
            live.forEach { runCatching { it.close() } }
            live.clear()
        }

        override fun close() {
            listening = false
            runCatching { server.close() }
            dropAll()
        }
    }

    // ----- reconnecting ---------------------------------------------------------------------------

    @Test
    fun watchSurvivesADroppedConnectionAndCanStillBeCancelled() {
        val token = freshToken()
        val fixture = launch(token)
        val credential = tokenFile(token)
        val relay = TcpRelay(fixture.port).also { relays += it }
        val viaRelay = "ws://127.0.0.1:${relay.port}/ws"

        val submit = runCli("submit", "HANG relay pod", "--url", fixture.url(), "--token-file", credential.path)
        assertEquals(0, submit.exit, submit.err)
        val podId = submit.out.trim()

        val watch = BackgroundCli("watch", podId, "--url", viaRelay, "--token-file", credential.path)
        watch.awaitText("[pod] ")

        relay.dropAll()
        watch.awaitText("reconnected")

        val cancel = runCli("cancel", podId, "--force", "--url", fixture.url(), "--token-file", credential.path)
        assertEquals(0, cancel.exit, cancel.err)

        assertEquals(0, watch.finish(), "the watch should survive the drop and end on the cancel: ${watch.err.value()}")
        assertTrue(watch.err.value().contains("lost; reconnecting"), "the drop should be reported: ${watch.err.value()}")
        assertTrue(watch.out.value().contains("CANCELLED"), "the watch should show the cancel: ${watch.out.value()}")
    }

    // ----- credentials ----------------------------------------------------------------------------

    @Test
    fun aWrongTokenIsRefusedAndNeitherTokenIsPrinted() {
        val daemonToken = freshToken()
        val wrongToken = freshToken()
        val fixture = launch(daemonToken)
        val wrong = tokenFile(wrongToken)

        val result = runCli("pods", "--url", fixture.url(), "--token-file", wrong.path)

        assertEquals(1, result.exit, "a refused token is a runtime failure: ${result.err}")
        assertTrue(result.err.contains("HTTP 401"), result.err)
        assertTrue(result.err.contains("was not accepted"), result.err)
        val everything = result.out + result.err
        assertFalse(everything.contains(wrongToken), "the rejected token must never be printed")
        assertFalse(everything.contains(daemonToken), "the daemon's token must never be printed")
    }

    @Test
    fun aTokenFileWrittenWithAByteOrderMarkAuthenticates() {
        val token = freshToken()
        val fixture = launch(token)
        val result = runCli("pods", "--url", fixture.url(), "--token-file", tokenFile(token, bom = true).path)
        assertEquals(0, result.exit, result.err)
        assertTrue(result.out.contains("SESSIONS:"), result.out)
    }

    @Test
    fun aClientWithNoCredentialIsToldWhichFlagToUse() {
        val environmentCarriesToken = listOf(AccessPolicy.TOKEN_ENV, AccessPolicy.TOKEN_FILE_ENV)
            .any { System.getenv(it)?.isNotBlank() == true }
        if (environmentCarriesToken) {
            println("[skip] INFANTRY_TOKEN or INFANTRY_TOKEN_FILE is set here; the no-credential case is not exercised")
            return
        }
        val fixture = launch(freshToken())
        val result = runCli("pods", "--url", fixture.url())
        assertEquals(1, result.exit, result.err)
        assertTrue(result.err.contains("no token was sent"), result.err)
        assertTrue(result.err.contains("--token-file"), result.err)
    }

    // ----- several daemons ------------------------------------------------------------------------

    @Test
    fun oneClientReachesTwoDaemonsByEndpointNameWithEachDaemonsOwnToken() {
        val alphaToken = freshToken()
        val betaToken = freshToken()
        val alpha = launch(alphaToken)
        val beta = launch(betaToken)
        val alphaFile = tokenFile(alphaToken)
        val betaFile = tokenFile(betaToken)

        val endpoints = File.createTempFile("infantry-endpoints", ".json").also { files += it }
        endpoints.writeText(
            buildJsonObject {
                put(
                    "endpoints",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "alpha")
                                put("url", alpha.url())
                                put("tokenFile", alphaFile.path)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("name", "beta")
                                put("url", beta.url())
                                put("tokenFile", betaFile.path)
                            },
                        )
                    },
                )
            }.toString(),
        )
        // The endpoints file names token FILES. A token value inside it would be a leak.
        val endpointsText = endpoints.readText()
        assertFalse(
            endpointsText.contains(alphaToken) || endpointsText.contains(betaToken),
            "the endpoints file must not hold a token",
        )

        val submit = runCli("submit", "HANG alpha pod", "--endpoint", "alpha", "--endpoints", endpoints.path)
        assertEquals(0, submit.exit, submit.err)
        val podId = submit.out.trim()

        val onAlpha = runCli("runs", podId, "--endpoint", "alpha", "--endpoints", endpoints.path)
        assertEquals(0, onAlpha.exit, onAlpha.err)
        assertTrue(onAlpha.out.contains("RUNS for pod $podId"), onAlpha.out)

        // Beta is a different daemon: it has no such pod, and it was offered beta's own token.
        val onBeta = runCli("runs", podId, "--endpoint", "beta", "--endpoints", endpoints.path)
        assertEquals(1, onBeta.exit, onBeta.out)
        assertTrue(onBeta.err.contains("unknown pod"), onBeta.err)
    }

    @Test
    fun anUnknownEndpointNameIsAUsageErrorThatContactsNothing() {
        val endpoints = File.createTempFile("infantry-endpoints", ".json").also { files += it }
        endpoints.writeText("""{"endpoints":[]}""")

        val result = runCli("pods", "--endpoint", "nowhere", "--endpoints", endpoints.path)

        assertEquals(2, result.exit, result.err)
        assertTrue(result.err.contains("no endpoint named 'nowhere'"), result.err)
    }

    // ----- the cleartext warning ------------------------------------------------------------------

    @Test
    fun aTokenSentToAnotherMachineOverPlainWsIsFlaggedBeforeTheConnectionIsTried() {
        val lan = lanIpv4()
        if (lan == null) {
            println("[skip] no non-loopback IPv4 address on this machine; the cleartext warning was not exercised")
            return
        }
        if (!lanPathAnswers(lan)) {
            println("[skip] LAN address $lan refuses its own connections here; the cleartext warning was not exercised")
            return
        }
        // Port 1 has nothing listening, so the attempt itself fails. The warning must still come first.
        val result = runCli("pods", "--url", "ws://$lan:1/ws", "--token-file", tokenFile(freshToken()).path)
        assertTrue(result.err.contains("sent in cleartext"), "cleartext warning missing: ${result.err}")
        // Per spec: exit 2 with exact message "Infantry AI daemon not running on <host>:<port> \u2014 start it with \u0060infantry daemon\u0060"
        assertEquals(2, result.exit, "expected exit 2 for unreachable daemon, got ${result.exit}: ${result.err}")
        val expectedMsg = "error: Infantry AI daemon not running on $lan:1 \u2014 start it with \u0060infantry daemon\u0060"
        assertTrue(result.err.contains(expectedMsg), "exact message missing from err: ${result.err}")
    }

    @Test
    fun aTokenOnLoopbackPlainWsIsNotFlagged() {
        val token = freshToken()
        val fixture = launch(token)
        val result = runCli("pods", "--url", fixture.url(), "--token-file", tokenFile(token).path)
        assertEquals(0, result.exit, result.err)
        assertFalse(result.err.contains("cleartext"), "loopback is not a network path: ${result.err}")
    }
}
