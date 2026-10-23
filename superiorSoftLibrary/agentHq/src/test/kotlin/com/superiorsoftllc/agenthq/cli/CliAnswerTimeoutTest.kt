package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import java.io.StringReader
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI-TIMEOUT-1: AGENTHQ_CLI_ANSWER_TIMEOUT_MS, read through Cli.run's getenv, governs how long a command waits
 * for its answer. The stub greets with `ready` and then never answers, so only the timeout can end the run.
 */
class CliAnswerTimeoutTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
            webSocket("/ws") {
                // A daemon's greeting, then silence: every request after `ready` goes unanswered.
                val ready = Envelope(Envelope.READY, "provider=stub contract=${EventVocabulary.VERSION} instance=A since=0")
                send(Frame.Text(Json.encodeToString(Envelope.serializer(), ready)))
                for (frame in incoming) { /* never answer */ }
            }
        }
    }.start(wait = false)

    @AfterTest
    fun stop() = server.stop(0, 0)

    private fun run(env: Map<String, String>): Pair<Int, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("tickets", "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return exit to err.toString()
    }

    @Test
    fun `a short override governs the answer timeout`() {
        val started = System.currentTimeMillis()
        val (exit, err) = run(mapOf(ANSWER_TIMEOUT_ENV to "200", "AGENTHQ_PROJECT_ID" to "P"))
        val took = System.currentTimeMillis() - started
        assertEquals(1, exit, err)
        assertTrue(err.contains("within 200ms"), err)
        assertTrue(took < 10_000, "took ${took}ms")
    }

    @Test
    fun `an invalid override keeps the default`() {
        assertEquals(ANSWER_TIMEOUT_MS, answerTimeoutMs { "0" })
        assertEquals(ANSWER_TIMEOUT_MS, answerTimeoutMs { "-5" })
        assertEquals(ANSWER_TIMEOUT_MS, answerTimeoutMs { "abc" })
        assertEquals(ANSWER_TIMEOUT_MS, answerTimeoutMs { null })
        assertEquals(750L, answerTimeoutMs { " 750 " })
    }
}
