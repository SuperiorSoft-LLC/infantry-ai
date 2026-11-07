// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** UPDATER-NET-MSG-1: a short network drop is retried quietly; a lasting one names its cause. */
class NetworkRetryTest {
    /** A server that drops the first [drops] connections without an answer, then serves a release. */
    private fun flaky(drops: Int, hits: AtomicInteger): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            if (hits.incrementAndGet() <= drops) {
                ex.close()
            } else {
                val body = """{"tag_name":"v1.0.4","assets":[]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        start()
    }

    private fun config(server: HttpServer, retries: Int) =
        UpdateConfig("o/r", SemVer(1, 0, 0), "http://127.0.0.1:${server.address.port}", networkRetries = retries, networkBackoffMs = 5)

    @Test
    fun `one dropped connection then success shows no failure`() {
        val hits = AtomicInteger()
        val server = flaky(drops = 1, hits = hits)
        try {
            val got = GitHubReleases().latest(config(server, retries = 3))
            assertIs<Fetched.Ok<GitHubRelease>>(got, "a single IOException must be retried, got $got")
            assertEquals(2, hits.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `retries stop at the named count and the failure carries the cause`() {
        val hits = AtomicInteger()
        val server = flaky(drops = 100, hits = hits)
        try {
            val got = GitHubReleases().latest(config(server, retries = 2))
            val failed = assertIs<Fetched.Failed>(got)
            assertIs<UpdateFailure.Offline>(failed.failure)
            assertTrue(hits.get() in 3..6, "1 try + 2 retries (the JDK client may repeat a dropped GET itself), got ${hits.get()}")
            assertTrue("EOF" in failed.failure.message, "the exception message must be shown: ${failed.failure.message}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a cause names its kind and message`() {
        assertTrue(describeNetworkError(UnknownHostException("api.github.com")).startsWith("DNS lookup failed"))
        assertTrue(describeNetworkError(java.net.http.HttpTimeoutException("request timed out")).startsWith("timeout"))
        assertTrue(describeNetworkError(java.net.SocketException("Connection reset")).startsWith("connection reset"))
    }
}
