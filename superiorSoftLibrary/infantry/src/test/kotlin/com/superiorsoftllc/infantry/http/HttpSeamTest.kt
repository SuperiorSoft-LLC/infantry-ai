// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.http

import com.superiorsoftllc.infantry.daemon.api.http.Backoff
import com.superiorsoftllc.infantry.daemon.api.http.HttpException
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import com.superiorsoftllc.infantry.daemon.api.http.RetryPolicy
import com.superiorsoftllc.infantry.daemon.api.http.Sleeper
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP-SEAM-1: the pure backoff (fake clock, no I/O) and the core client against a local server on port 0. */
class HttpSeamTest {
    private var server: HttpServer? = null

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> handler(ex); ex.close() }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    @Test fun `delays double up to the cap and jitter stays inside its band`() {
        val p = RetryPolicy(maxAttempts = 5, baseDelayMs = 100, maxDelayMs = 350, factor = 2.0, jitter = 0.0)
        assertEquals(listOf(100L, 200L, 350L, 350L), (1..4).map { p.delayMs(it) })
        val j = RetryPolicy(baseDelayMs = 100, maxDelayMs = 10_000, jitter = 0.5)
        assertEquals(50L, j.delayMs(1, random = 0.0))
        assertEquals(150L, j.delayMs(1, random = 1.0))
    }

    @Test fun `shouldRetry stops at maxAttempts and only retries listed statuses`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertTrue(p.shouldRetry(1, 503)); assertTrue(p.shouldRetry(2, null))
        assertFalse(p.shouldRetry(3, 503)); assertFalse(p.shouldRetry(1, 404)); assertFalse(p.shouldRetry(1, 200))
        assertFalse(RetryPolicy.NONE.shouldRetry(1, 503))
    }

    @Test fun `backoff sleeps the policy delays on a fake clock and returns the attempts`() {
        val slept = ArrayList<Long>()
        val p = RetryPolicy(maxAttempts = 4, baseDelayMs = 100, maxDelayMs = 10_000, jitter = 0.0)
        val statuses = ArrayDeque(listOf(503, 429, 200))
        val (status, attempts) = Backoff.run(p, Sleeper { slept += it }, statusOf = { s: Int -> s }) { statuses.removeFirst() }
        assertEquals(200, status); assertEquals(3, attempts); assertEquals(listOf(100L, 200L), slept)
    }

    @Test fun `backoff rethrows after the last attempt of a thrown failure`() {
        val slept = ArrayList<Long>()
        val calls = AtomicInteger()
        assertFailsWith<IllegalStateException> {
            Backoff.run(RetryPolicy(maxAttempts = 2, jitter = 0.0), Sleeper { slept += it }, statusOf = { s: Int -> s }) { calls.incrementAndGet(); error("down") }
        }
        assertEquals(2, calls.get()); assertEquals(1, slept.size)
    }

    @Test fun `a GET is retried on 503 and the third answer wins`() {
        val hits = AtomicInteger()
        val base = serve { ex -> if (hits.incrementAndGet() < 3) reply(ex, 503, "busy") else reply(ex, 200, """{"ok":true}""") }
        val slept = ArrayList<Long>()
        val http = CoreHttp(sleeper = Sleeper { slept += it }, random = { 0.5 })
        val r = http.request(HttpSpec("$base/x"))
        assertEquals(200, r.status); assertEquals(3, r.attempts); assertEquals(2, slept.size)
        assertEquals("true", http.json(HttpSpec("$base/x")).jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test fun `a POST is not retried unless the caller asks`() {
        val hits = AtomicInteger()
        val base = serve { ex -> hits.incrementAndGet(); reply(ex, 503, "busy") }
        val r = CoreHttp(sleeper = Sleeper { }).request(HttpSpec("$base/x", method = "POST", body = "{}"))
        assertEquals(503, r.status); assertEquals(1, hits.get())
    }

    @Test fun `a secret header is sent, scrubbed from the body, and absent from toString and errors`() {
        val base = serve { ex -> reply(ex, 200, "you sent ${ex.requestHeaders.getFirst("Authorization")}") }
        val spec = HttpSpec("$base/x", secretHeaders = mapOf("Authorization" to "Bearer s3cr3t-token"))
        val r = CoreHttp().request(spec)
        assertEquals("you sent ***", r.body)
        assertFalse("s3cr3t-token" in spec.toString())
        val err = assertFailsWith<HttpException> { CoreHttp().request(HttpSpec("http://127.0.0.1:1/x?k=s3cr3t-token", secretHeaders = mapOf("Authorization" to "s3cr3t-token"), retry = RetryPolicy.NONE)) }
        assertFalse("s3cr3t-token" in err.message.orEmpty())
    }

    @Test fun `a body over the cap is cut and flagged and json refuses it`() {
        val base = serve { ex -> reply(ex, 200, "x".repeat(5_000)) }
        val http = CoreHttp()
        val r = http.request(HttpSpec("$base/x", maxBytes = 1_000))
        assertTrue(r.truncated); assertEquals(1_000, r.body.length)
        assertFailsWith<HttpException> { http.json(HttpSpec("$base/x", maxBytes = 1_000)) }
    }

    @Test fun `redirects are followed up to the setting and refused beyond it`() {
        val base = serve { ex -> if (ex.requestURI.path == "/end") reply(ex, 200, "arrived") else if (ex.requestURI.path == "/loop") { ex.responseHeaders.add("Location", "/loop"); ex.sendResponseHeaders(302, -1) } else { ex.responseHeaders.add("Location", "/end"); ex.sendResponseHeaders(302, -1) } }
        assertEquals("arrived", CoreHttp().request(HttpSpec("$base/start")).body)
        assertEquals(302, CoreHttp().request(HttpSpec("$base/start", maxRedirects = 0)).status, "0 follows none: the 3xx comes back as is")
        assertFailsWith<HttpException> { CoreHttp().request(HttpSpec("$base/loop", maxRedirects = 2)) }
    }
}
