package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-PORT-0b: since BUG-PORT-0, `build()` holds a port-0 daemon's OS-assigned port until something lets go of it.
 * [AgentHqDaemon.start] did; a caller that starts `daemon.server` directly (34 agentHqUi test sites) did not, so CIO
 * failed its own bind with `BindException: Address already in use`. Every start path must give the port back first.
 */
class DaemonPortDirectStartTest {

    private fun causeChain(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }

    @Test
    fun aPortZeroDaemonStartedThroughItsServerDirectlyBindsAndAnswersHealth() {
        var built: AgentHqDaemon? = null
        val fixture = try {
            DaemonFixture.launch(
                port = 0,
                beforeStart = { built = it },
                // What the agentHqUi tests do: start the Ktor server itself, not AgentHqDaemon.start().
                startServer = { daemon ->
                    daemon.server.start(wait = false)
                    daemon.boundPort()
                },
            )
        } catch (failure: Throwable) {
            built?.let { runCatching { it.server.stop(0, 200) }; it.close() }
            fail("a direct daemon.server.start(wait = false) on a port-0 daemon must bind: ${causeChain(failure)}")
        }
        try {
            assertTrue(fixture.port > 0, "the daemon bound a real port, not 0: ${fixture.port}")
            assertEquals(fixture.daemon.port, fixture.port, "the server bound the port build() reserved and advertised")
            val health = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${fixture.port}/health")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, health.statusCode(), "/health answers on the bound port: ${health.body()}")
            fixture.client.await(Envelope.READY)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
