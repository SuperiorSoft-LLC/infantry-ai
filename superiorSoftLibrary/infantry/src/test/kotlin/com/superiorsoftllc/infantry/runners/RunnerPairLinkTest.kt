// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon.WssLinkTransport
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RUNNER-LINK-1: the `infantry://runner?` pairing link codec, the host order, and the link in the `pair` answer. */
class RunnerPairLinkTest {
    private val fp = "ab".repeat(32)
    private val code = "ABCD-EFGH-JKLM-NPQR"
    private val dir: File = Files.createTempDirectory("runner-pair-link").toFile()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closers.asReversed().forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun link(hosts: List<String> = listOf("100.64.0.7", "runner.example.com"), fingerprint: String? = fp, plain: Boolean = false) =
        RunnerPairLink("t1", hosts, 18901, code, fingerprint, plain)

    private fun ok(text: String) = (RunnerPairLink.parse(text) as RunnerPairLink.Parsed.Ok).link
    private fun bad(text: String) = (RunnerPairLink.parse(text) as RunnerPairLink.Parsed.Bad).reason

    @Test
    fun `round trip keeps every field and the host order`() {
        val l = link()
        assertEquals("infantry://runner?v=1&name=t1&host=100.64.0.7&host=runner.example.com&port=18901&code=$code&fp=$fp", l.toUri())
        assertEquals(l, ok(l.toUri()))
        assertEquals(l, ok("'${l.toUri()}'"))
    }

    @Test
    fun `an IPv6 host travels in brackets`() {
        val l = link(hosts = listOf("fd7a::1", "[::2]"))
        assertEquals(listOf("[fd7a::1]", "[::2]"), ok(l.toUri()).hosts)
        assertTrue(l.toUri().contains("host=%5Bfd7a::1%5D") || l.toUri().contains("host=[fd7a::1]"))
    }

    @Test
    fun `toString never prints the code`() {
        assertFalse(link().toString().contains(code))
        assertTrue(link().toString().contains("redacted"))
    }

    @Test
    fun `plain link needs no fingerprint`() {
        val l = ok(link(fingerprint = null, plain = true).toUri())
        assertTrue(l.plain)
        assertEquals(null, l.fingerprint)
    }

    @Test
    fun `parse refuses each malformed link with fixed text`() {
        val base = link().toUri()
        assertTrue(bad("http://x").contains("runner pairing link"))
        assertTrue(bad(base.replace("v=1", "v=2")).contains("newer runner") && bad(base.replace("v=1", "v=2")).contains("update this app"))
        assertTrue(bad(base.replace("&name=t1", "")).contains("no runner name"))
        assertTrue(bad(base.replace("&port=18901", "")).contains("no port"))
        assertTrue(bad(base.replace("&code=$code", "")).contains("no pairing code"))
        assertTrue(bad(base.replace("&host=100.64.0.7&host=runner.example.com", "")).contains("no host"))
        assertTrue(bad(base.replace("name=t1", "name=Bad_Name")).contains("runner name"))
        assertTrue(bad(base.replace("&fp=$fp", "")).contains("fingerprint"))
        val secret = "SECRETTEXT"
        assertFalse(bad("infantry://runner?v=1&name=$secret").contains(secret))
    }

    @Test
    fun `fingerprint compare ignores case and colons`() {
        val colons = fp.uppercase().chunked(2).joinToString(":")
        assertTrue(link().fingerprintMatches(colons))
        assertFalse(link().fingerprintMatches("00".repeat(32)))
        assertEquals(link(), ok(link().toUri().replace("fp=$fp", "fp=$colons")))
    }

    @Test
    fun `host candidates are flags then advertised then detected, capped`() {
        val detected = { listOf("10.0.0.5") }
        assertEquals(listOf("a", "b", "c"), RunnerPairLink.candidates(listOf("a,b"), listOf("c", "a"), 4, detected))
        assertEquals(listOf("c"), RunnerPairLink.candidates(emptyList(), listOf("c"), 4, detected))
        assertEquals(listOf("10.0.0.5"), RunnerPairLink.candidates(emptyList(), emptyList(), 4, detected))
        assertEquals(listOf("a", "b"), RunnerPairLink.candidates(listOf("a", "b", "c"), emptyList(), 2, detected))
    }

    @Test
    fun `detected addresses put tailnet first then private then the rest and skip loopback and link-local`() {
        fun ip(s: String) = InetAddress.getByName(s)
        val out = RunnerPairLink.ordered(listOf(ip("8.8.4.4"), ip("192.168.1.9"), ip("127.0.0.1"), ip("169.254.1.1"), ip("100.100.1.2")))
        assertEquals(listOf("100.100.1.2", "192.168.1.9", "8.8.4.4"), out)
    }

    @Test
    fun `the pair answer carries a link that parses back to its code, port and fingerprint`() {
        val limits: (com.superiorsoftllc.infantry.settings.LimitDef) -> Long = { if (it == LimitRegistry.RUNNERS_LINK_PORT) 0L else it.default }
        val service = RunnerLinkService(
            File(dir, "r"), limits, settings = { RunnerLinkSettings.Values(advertiseHosts = listOf("runner.example.com")) },
            runner = null, transport = { WssLinkTransport }, host = "127.0.0.1", log = {},
        ).also { closers += it }
        val answer = service.admin(JsonObject(mapOf("op" to JsonPrimitive("pair"), "name" to JsonPrimitive("t1"), "hosts" to JsonPrimitive("127.0.0.1"))))
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        val parsed = ok(assertNotNull(RunnerLinkService.field(answer, "link")))
        assertEquals(RunnerLinkService.field(answer, "code"), parsed.code)
        assertEquals(RunnerLinkService.field(answer, "port")?.toInt(), parsed.port)
        assertTrue(parsed.fingerprintMatches(RunnerLinkService.field(answer, "fingerprint")))
        assertEquals(listOf("127.0.0.1", "runner.example.com"), parsed.hosts)
        assertEquals("t1", parsed.name)
    }
}
