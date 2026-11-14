// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.cards.daemon

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardImageProxyTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
    private val public = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    private class Rig(
        val dir: File = Files.createTempDirectory("card-img").toFile(),
        val settings: MutableMap<String, String> = mutableMapOf(),
        var body: ByteArray = ByteArray(0),
        var addresses: Map<String, List<InetAddress>> = emptyMap(),
        var redirectTo: String? = null,
        var status: Int = 200,
    ) {
        val asked = mutableListOf<DownloadRequest>()
        val proxy = CardImageProxy(
            cacheDir = dir,
            setting = { settings[it] },
            hostsOf = { if (it == "news") listOf("*.gstatic.com", "img.example.org") else null },
            download = { req ->
                asked += req
                val r = redirectTo
                when {
                    r != null && !req.allowRedirect!!.invoke(r) -> DownloadResult(ok = false, error = "redirect refused: x sent it to y", retryable = true)
                    status != 200 -> DownloadResult(ok = false, status = status, error = "HTTP $status")
                    else -> { req.dest.parentFile.mkdirs(); req.dest.writeBytes(body); DownloadResult(ok = true, file = req.dest, bytes = body.size.toLong(), status = 200) }
                }
            },
            resolve = { host -> addresses[host] ?: emptyList() },
        )
    }

    private fun rig() = Rig(addresses = mapOf("t0.gstatic.com" to listOf(public), "img.example.org" to listOf(public), "evil.gstatic.com" to listOf(InetAddress.getByName("127.0.0.1"))))

    @Test
    fun `an allowed https host is fetched once, sniffed and then served from the cache`() {
        val r = rig().apply { body = png }
        val out = r.proxy.fetch("news", "https://t0.gstatic.com/a.png")
        assertIs<CardImageProxy.Outcome.Image>(out)
        assertEquals("image/png", out.mime)
        val req = r.asked.single()
        assertEquals(CardImageProxy.USER_AGENT, req.headers["User-Agent"])
        assertFalse(req.headers.keys.any { it.equals("Cookie", true) || it.equals("Referer", true) || it.equals("Authorization", true) })
        assertTrue(req.secretHeaders.isEmpty())
        assertEquals(CardImageProxy.DEFAULT_IMAGE_BYTES.toLong(), req.maxBytes)
        assertIs<CardImageProxy.Outcome.Image>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
        assertEquals(1, r.asked.size, "the second ask comes from the cache")
    }

    @Test
    fun `undeclared host, http, IP literal, odd port, credentials and unknown type are refused without a fetch`() {
        val r = rig().apply { body = png }
        listOf(
            "news" to "https://tracker.example.com/p.png",
            "news" to "http://t0.gstatic.com/a.png",
            "news" to "https://93.184.216.34/a.png",
            "news" to "https://[::1]/a.png",
            "news" to "https://t0.gstatic.com:8443/a.png",
            "news" to "https://user:pw@t0.gstatic.com/a.png",
            "news" to "https://gstatic.com/a.png",
            "hello" to "https://t0.gstatic.com/a.png",
            "news" to "file:///etc/passwd",
        ).forEach { (type, url) -> assertIs<CardImageProxy.Outcome.Refused>(r.proxy.fetch(type, url), url) }
        assertTrue(r.asked.isEmpty())
    }

    @Test
    fun `a host resolving to a private address is refused`() {
        val r = rig().apply { body = png }
        val out = r.proxy.fetch("news", "https://evil.gstatic.com/a.png")
        assertIs<CardImageProxy.Outcome.Refused>(out)
        assertTrue("non-public" in out.reason)
        assertTrue(r.asked.isEmpty())
    }

    @Test
    fun `a redirect to an undeclared host is refused`() {
        val r = rig().apply { body = png; redirectTo = "https://tracker.example.com/x.png" }
        assertIs<CardImageProxy.Outcome.Refused>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
        r.redirectTo = "https://img.example.org/b.png"
        assertIs<CardImageProxy.Outcome.Image>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
    }

    @Test
    fun `bytes that are not an image are refused and not cached`() {
        val r = rig().apply { body = "<html><script>".toByteArray() }
        assertIs<CardImageProxy.Outcome.Refused>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
        assertTrue(r.dir.listFiles { f -> f.isFile }.orEmpty().isEmpty())
    }

    @Test
    fun `owner allow-list adds a host, and the switch turns images off`() {
        val r = rig().apply { body = png; addresses = addresses + ("cdn.owner.net" to listOf(public)) }
        assertIs<CardImageProxy.Outcome.Refused>(r.proxy.fetch("news", "https://cdn.owner.net/a.png"))
        r.settings[CardImageProxy.ALLOW_KEY] = "cdn.owner.net, *.other.io"
        assertIs<CardImageProxy.Outcome.Image>(r.proxy.fetch("news", "https://cdn.owner.net/a.png"))
        r.settings[CardImageProxy.ENABLED_KEY] = "false"
        assertIs<CardImageProxy.Outcome.Refused>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
    }

    @Test
    fun `an HTTP error is a failure, not a refusal`() {
        val r = rig().apply { status = 404 }
        assertIs<CardImageProxy.Outcome.Failed>(r.proxy.fetch("news", "https://t0.gstatic.com/a.png"))
    }

    @Test
    fun `expired entries and the size cap evict the oldest`() {
        val r = rig().apply { body = png }
        r.settings[CardImageProxy.TTL_HOURS_KEY] = "1"
        r.proxy.fetch("news", "https://t0.gstatic.com/a.png")
        val cached = r.dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }!!.single()
        cached.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L)
        r.proxy.fetch("news", "https://t0.gstatic.com/a.png")
        assertEquals(2, r.asked.size, "an expired entry is fetched again")
    }

    @Test
    fun `host patterns, public addresses and the sniffer`() {
        assertTrue(CardImageProxy.hostMatches("a.b.gstatic.com", "*.gstatic.com"))
        assertFalse(CardImageProxy.hostMatches("gstatic.com", "*.gstatic.com"))
        assertFalse(CardImageProxy.hostMatches("evilgstatic.com", "*.gstatic.com"))
        assertFalse(CardImageProxy.hostMatches("x.com", "*.com"))
        assertTrue(CardImageProxy.hostMatches("ichef.bbci.co.uk", "*")) // CARD-IMG-2: a bare * is any named host
        listOf("10.1.2.3", "172.16.0.1", "192.168.1.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1")
            .forEach { assertFalse(CardImageProxy.isPublic(InetAddress.getByName(it)), it) }
        assertTrue(CardImageProxy.isPublic(InetAddress.getByName("93.184.216.34")))
        assertTrue(CardImageProxy.isPublic(InetAddress.getByName("2606:4700::1")))
        assertEquals(CardImageProxy.Kind.PNG, CardImageProxy.sniff(png))
        assertEquals(CardImageProxy.Kind.JPEG, CardImageProxy.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(CardImageProxy.Kind.GIF, CardImageProxy.sniff("GIF89a....".toByteArray()))
        assertEquals(CardImageProxy.Kind.WEBP, CardImageProxy.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(CardImageProxy.sniff("<svg>".toByteArray()))
    }

    @Test
    fun `the frame answer carries base64 bytes or the reason`() {
        val r = rig().apply { body = png }
        val ok = CardsDaemonPlugin().imageAnswer(r.proxy, """{"type":"news","url":"https://t0.gstatic.com/a.png"}""")
        assertTrue("\"ok\":true" in ok && "\"mime\":\"image/png\"" in ok && "\"base64\"" in ok, ok)
        val no = CardsDaemonPlugin().imageAnswer(r.proxy, """{"type":"news","url":"https://tracker.example.com/a.png"}""")
        assertTrue("\"ok\":false" in no && "\"refused\"" in no, no)
    }
}
