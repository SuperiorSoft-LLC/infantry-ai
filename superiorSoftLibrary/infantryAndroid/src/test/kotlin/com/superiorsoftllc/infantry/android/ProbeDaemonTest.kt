// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.android

import com.superiorsoftllc.infantry.ui.shell.ConnectOutcome
import com.superiorsoftllc.infantry.ui.shell.DaemonUrlValidator
import com.superiorsoftllc.infantry.ui.shell.probeDaemon
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MOBILE-PROBE-TEST-1: probeDaemon (infantryUiCore androidMain ConnectScreen.kt) against a real in-process daemon on an
 * ephemeral port. ktor's MockEngine has no WebSocket capability and probeDaemon only opens a WebSocket, so a real
 * server is the smallest seam: no production change.
 */
class ProbeDaemonTest {
    private val requests = AtomicInteger(0)
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    @BeforeTest fun start() {
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            // The daemon's bearer gate: anything but "good" is refused before the upgrade, like the real one.
            intercept(ApplicationCallPipeline.Plugins) {
                requests.incrementAndGet()
                if (call.request.header(HttpHeaders.Authorization) != "Bearer $GOOD") {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                }
            }
            routing {
                webSocket("/ws") {
                    send(Frame.Text("""{"type":"ready"}"""))
                    for (frame in incoming) Unit
                }
                // Accepts the socket and never says `ready`: a daemon that hangs.
                webSocket("/silent") { for (frame in incoming) Unit }
            }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    private fun url(path: String) = "ws://127.0.0.1:$port$path"

    @Test fun readyFromTheDaemonIsConnected() = runBlocking {
        assertEquals(ConnectOutcome.Connected, probeDaemon(url("/ws"), GOOD, timeoutMs = 5_000))
    }

    @Test fun http401IsBadToken() = runBlocking {
        val outcome = probeDaemon(url("/ws"), "wrong-token", timeoutMs = 5_000)
        assertIs<ConnectOutcome.BadToken>(outcome)
        assertEquals("Token rejected (HTTP 401): check the bearer token", outcome.message)
    }

    @Test fun aDaemonThatNeverAnswersTimesOutAsUnreachable() = runBlocking {
        val outcome = probeDaemon(url("/silent"), GOOD, timeoutMs = 300)
        assertIs<ConnectOutcome.Unreachable>(outcome)
        assertTrue(outcome.message.startsWith("Daemon unreachable: no answer from ${url("/silent")}"), outcome.message)
        // BUG-PROBE-MSG-1: a sub-second timeout must not be truncated to "within 0s".
        assertFalse(outcome.message.contains("within 0s"), outcome.message)
        assertTrue(outcome.message.endsWith("within 300ms"), outcome.message)
    }

    /**
     * ConnectForm.submit (ConnectScreen.kt:160-163) only calls onConnect, which is what starts the probe, when the
     * validator returns null. The real gate is a composable, so this pins the validator's verdict and that the daemon
     * saw no request.
     */
    @Test fun aMalformedUrlIsRejectedBeforeAnyRequest() {
        listOf("http://127.0.0.1:$port/ws", "127.0.0.1:$port/ws", "ws://", "ws://ho st/ws").forEach {
            assertNotNull(DaemonUrlValidator.validate(it), "should reject '$it'")
        }
        assertEquals(0, requests.get(), "no request may reach the daemon for a rejected URL")
    }

    private companion object { const val GOOD = "good-token" }
}
