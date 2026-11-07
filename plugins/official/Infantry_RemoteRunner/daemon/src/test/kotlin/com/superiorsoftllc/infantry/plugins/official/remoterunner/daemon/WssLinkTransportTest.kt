// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-6a: the transport half of RUNNER-POOL-5's pairing tests, moved with the transport (the handler, store and
 * pairing tests stay in core's RunnerLinkPairingTest). Port 0 only.
 */
class WssLinkTransportTest {
    private val dir: File = Files.createTempDirectory("runner-link-transport").toFile()

    private data class Seen(val type: String, val data: String, val orchestratorId: String?, val credential: String?, val from: String?)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val handler = object : LinkHandler {
        override fun handle(type: String, data: String, orchestratorId: String?, credential: String?, from: String?): LinkAnswer {
            seen += Seen(type, data, orchestratorId, credential, from)
            return if (type == "runner_link_pair") LinkAnswer("runner_link_reply", "{\"ok\":true}") else LinkAnswer("runner_link_error", "$type refused")
        }

        override fun malformed() = LinkAnswer("runner_link_error", "malformed frame")
    }

    @Test
    fun `end to end over wss the pinned fingerprint is checked before anything is sent and a wrong one is refused`() {
        val tls = LinkTlsMaterial(File(dir, "tls"), "pw")
        val fp = WssLinkTransport.fingerprint(tls)
        assertEquals(fp, WssLinkTransport.fingerprint(tls), "the certificate is made once")
        val serving = WssLinkTransport.serve(handler, "127.0.0.1", 0, tls, 10_000)
        try {
            val url = "wss://127.0.0.1:${serving.port}"
            val wrong = assertFailsWith<LinkFailure> { WssLinkTransport.call(url, "00:11:22", false, null, null, "runner_link_pair", "{}", 10) }
            assertTrue(wrong.message!!.contains("mismatch"), wrong.message)
            assertEquals(LinkTls.normalized(fp), LinkTls.normalized(wrong.presentedFingerprint!!))
            val missing = assertFailsWith<LinkFailure> { WssLinkTransport.call(url, null, false, null, null, "runner_link_pair", "{}", 10) }
            assertNotNull(missing.presentedFingerprint)
            assertTrue(seen.isEmpty(), "no frame reached the runner before the pin matched: $seen")

            val ok = WssLinkTransport.call(url, fp.lowercase().replace(":", ""), false, null, null, "runner_link_pair", "{\"code\":\"c\"}", 10)
            assertEquals(LinkAnswer("runner_link_reply", "{\"ok\":true}"), ok)
            val refused = WssLinkTransport.call(url, fp, false, "inf-1", "cred", "settings_set", "{}", 10)
            assertEquals("runner_link_error", refused.type)
            assertEquals(Seen("runner_link_pair", "{\"code\":\"c\"}", null, null, "127.0.0.1"), seen[0])
            assertEquals(Seen("settings_set", "{}", "inf-1", "cred", "127.0.0.1"), seen[1], "id and credential travel in the headers; from is the real peer")
        } finally {
            serving.close()
        }
    }

    @Test
    fun `plain ws is refused unless runners allowPlainTailnet`() {
        assertFailsWith<LinkFailure> { LinkClient.endpoint("ws://h:1", allowPlain = false) }
        assertEquals("ws://h:1${LinkWire.PATH}", LinkClient.endpoint("ws://h:1", allowPlain = true).toString())
        assertEquals("wss://h:1${LinkWire.PATH}", LinkClient.endpoint("wss://h:1", allowPlain = false).toString())
        val serving = WssLinkTransport.serve(handler, "127.0.0.1", 0, null, 10_000)
        try {
            val answer = WssLinkTransport.call("ws://127.0.0.1:${serving.port}", null, true, null, null, "runner_link_pair", "{}", 10)
            assertEquals("runner_link_reply", answer.type)
        } finally {
            serving.close()
        }
    }
}
