package com.superiorsoftllc.infantry.daemon

import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DAEMON-DROP-1: a slow operation can never block other clients, and a stall names itself in the log. */
class StallProbeTest {

    /** The starvation hypothesis, kept as a guard: handlers blocked up to the engine's default call group must not stop /health. */
    @Test
    fun `blocked handlers up to the default call group do not stop other requests`() {
        val cpus = Runtime.getRuntime().availableProcessors()
        val release = java.util.concurrent.CountDownLatch(1)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/slow") { release.await(20, TimeUnit.SECONDS); call.respondText("late") }
                get("/health") { call.respondText("ok") }
            }
        }.start(wait = false)
        val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
        try {
            val client = HttpClient.newHttpClient()
            repeat(cpus * 2) { client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/slow")).build(), HttpResponse.BodyHandlers.discarding()) }
            Thread.sleep(800)
            val status = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
            assertEquals(200, status)
        } finally {
            release.countDown()
            server.stop(0, 0)
        }
    }

    @Test
    fun `a missed probe logs the busy threads once, an answer logs the recovery`() {
        val lines = mutableListOf<String>()
        var up = false
        var now = 1_000L
        val probe = StallProbe(URI("http://x/health"), ask = { _, _ -> up }, dump = { "THREADS" }, log = { lines += it }, clock = { now })
        probe.tick(); now += 500; probe.tick()
        assertEquals(1, lines.size)
        assertTrue(probe.stalled && "THREADS" in lines[0] && "did not answer" in lines[0])
        up = true; now += 4_000; probe.tick()
        assertEquals(2, lines.size)
        assertTrue("answers again after 4500 ms" in lines[1] && !probe.stalled)
    }
}
