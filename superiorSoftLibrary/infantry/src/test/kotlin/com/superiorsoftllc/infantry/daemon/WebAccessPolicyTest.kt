// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import io.ktor.http.HttpStatusCode
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WEB-1 (contract 1.122.0): the browser's admission rules, with no sockets. Same-origin admission never replaces the
 * token; the Host is checked against an allowlist (never trusted as it arrives); the bundle files are the only thing
 * served without a token. The same rules over a real socket are pinned in [WebBundleDaemonTest].
 */
class WebAccessPolicyTest {

    private val goodToken = "correct-horse-battery-staple-0123456789"
    private val host = "127.0.0.1:8787"
    private val origin = "http://127.0.0.1:8787"

    private fun protocols(token: String) = "infantry, infantry.bearer." + Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray())

    private fun policy(bind: String = "127.0.0.1", token: String? = goodToken, webHosts: List<String> = emptyList(), local: Set<String> = emptySet()) =
        AccessPolicy.of(bind, token, webHosts) { local }

    private fun browser(p: AccessPolicy, path: String = "/ws", host: String? = this.host, origin: String = this.origin, protocols: String? = protocols(goodToken), authorization: String? = null) =
        p.check(path, host, origin, authorization, protocols)

    @Test
    fun aSameOriginBrowserWithTheTokenAsASubprotocolIsAdmittedOnTheWebSocket() {
        assertNull(browser(policy()))
        assertNull(browser(policy(), host = "localhost:8787", origin = "http://localhost:8787"))
        assertNull(browser(policy(), protocols = null, authorization = "Bearer $goodToken"), "the Authorization header still works")
    }

    @Test
    fun aCrossSiteOriginIsRefusedEvenWithTheRightToken() {
        for (o in listOf("http://evil.example", "https://evil.example:8787", "http://127.0.0.1:9999", "null", "ws://127.0.0.1:8787",
            "http://127.0.0.1:8787/", "http://user@127.0.0.1:8787", "file://", "http://127.0.0.1:8787.evil.example")) {
            assertEquals(HttpStatusCode.Forbidden, browser(policy(), origin = o)?.status, o)
        }
    }

    @Test
    fun aDnsRebindingHostAndMatchingOriginIsRefusedEvenWithTheRightToken() {
        // The attacker's page resolves evil.example to 127.0.0.1: Host and Origin agree, but the Host is not allowlisted.
        for (h in listOf("evil.example:8787", "evil.example", "127.0.0.1.evil.example:8787", "localhost.evil.example")) {
            assertEquals(HttpStatusCode.Forbidden, browser(policy(), host = h, origin = "http://$h")?.status, h)
        }
        assertEquals(HttpStatusCode.Forbidden, browser(policy(), host = null)?.status, "no Host at all")
    }

    @Test
    fun aMissingWrongOrMalformedTokenIsRefusedWithABearerChallenge() {
        val cases = listOf(
            null, "infantry", "infantry, infantry.bearer.", protocols("wrong-token-wrong-token-wrong-token-0"),
            protocols(goodToken + "x"), protocols(goodToken.dropLast(1)), "infantry, infantry.bearer.%%%not-base64%%%",
            "infantry, infantry.bearer.$goodToken!", "infantry.bearer", "infantry, Bearer $goodToken",
        )
        for (c in cases) {
            val refusal = browser(policy(), protocols = c)
            assertEquals(HttpStatusCode.Unauthorized, refusal?.status, "protocols=$c")
            assertTrue(refusal!!.challengeBearer)
            assertTrue(goodToken !in refusal.reason, "a refusal never echoes the token")
        }
    }

    @Test
    fun aTokenlessLoopbackDaemonRefusesEveryBrowserThereIsNoTokenlessPath() {
        val loopback = policy(token = null)
        assertEquals(HttpStatusCode.Forbidden, browser(loopback)?.status)
        assertEquals(HttpStatusCode.Forbidden, browser(loopback, protocols = null)?.status)
        // A CLI on loopback (no Origin) is unchanged.
        assertNull(loopback.check("/ws", host, null, null))
    }

    @Test
    fun aBrowserReachesOnlyTheWebSocketNotTheOtherRoutes() {
        for (path in listOf("/attention", "/mail/send", "/health", "/update/status", "/hooks/trg-1", "/ws/", "/WS")) {
            assertEquals(HttpStatusCode.Forbidden, browser(policy(), path = path, authorization = "Bearer $goodToken")?.status, path)
        }
    }

    @Test
    fun theHostAllowlistIsLoopbackTheBindHostOrTheLocalAddressesAndTheConfiguredExtras() {
        val lan = policy(bind = "0.0.0.0", local = setOf("192.168.1.5", "fe80::1"))
        assertNull(browser(lan, host = "192.168.1.5:8787", origin = "http://192.168.1.5:8787"))
        assertNull(browser(lan, host = "[fe80::1]:8787", origin = "http://[fe80::1]:8787"))
        assertEquals(HttpStatusCode.Forbidden, browser(lan, host = "192.168.1.6:8787", origin = "http://192.168.1.6:8787")?.status)

        val bound = policy(bind = "10.0.0.2", local = setOf("192.168.1.5"))
        assertNull(browser(bound, host = "10.0.0.2:8787", origin = "http://10.0.0.2:8787"))
        assertEquals(HttpStatusCode.Forbidden, browser(bound, host = "192.168.1.5:8787", origin = "http://192.168.1.5:8787")?.status,
            "a specific bind does not admit the machine's other addresses")

        val droplet = policy(bind = "0.0.0.0", webHosts = listOf("agents.example.com", " Other.Example "))
        assertNull(browser(droplet, host = "agents.example.com", origin = "https://agents.example.com"))
        assertNull(browser(droplet, host = "other.example:443", origin = "https://OTHER.example:443"))
        assertEquals(listOf("127.0.0.1", "::1", "agents.example.com", "localhost", "other.example"), droplet.browserHosts)
    }

    @Test
    fun theWebHostsSettingIsReadCommaSeparated() {
        assertEquals(listOf("a.example", "b.example"), AccessPolicy.webHostsFromEnvironment { if (it == AccessPolicy.WEB_HOSTS_ENV) " a.example, ,b.example " else null })
        assertEquals(emptyList(), AccessPolicy.webHostsFromEnvironment { null })
    }

    @Test
    fun bundleFilesNeedNoTokenButKeepTheHostAndOriginChecks() {
        val token = policy()
        assertNull(token.check("/", host, null, null, webAsset = true))
        assertNull(token.check("/infantry.mjs", host, origin, null, webAsset = true), "module scripts carry a same-origin Origin")
        assertEquals(HttpStatusCode.Forbidden, token.check("/", "evil.example:8787", null, null, webAsset = true)?.status)
        assertEquals(HttpStatusCode.Forbidden, token.check("/x.mjs", host, "http://evil.example", null, webAsset = true)?.status)

        val loopback = policy(token = null)
        assertNull(loopback.check("/", host, null, null, webAsset = true))
        assertEquals(HttpStatusCode.Forbidden, loopback.check("/", "evil.example", null, null, webAsset = true)?.status, "DNS rebinding")
        assertEquals(HttpStatusCode.Forbidden, loopback.check("/x.mjs", host, "http://evil.example", null, webAsset = true)?.status)
    }

    @Test
    fun theSubprotocolTokenDecodesFromBase64UrlAndOnlyFromItsOwnPrefix() {
        assertEquals(goodToken, AccessPolicy.bearerFromProtocols(protocols(goodToken)))
        assertEquals("a/b+c=", AccessPolicy.bearerFromProtocols("infantry.bearer." + Base64.getUrlEncoder().withoutPadding().encodeToString("a/b+c=".toByteArray())))
        assertNull(AccessPolicy.bearerFromProtocols("infantry"))
        assertNull(AccessPolicy.bearerFromProtocols(null))
        assertTrue(AccessPolicy.offersWebProtocol("x, infantry"))
        assertTrue(!AccessPolicy.offersWebProtocol("infantry.bearer.abc"))
    }

    /**
     * The comparison is constant-time: both credentials are compared as fixed-length SHA-256 digests with
     * MessageDigest.isEqual, never as strings. Timing cannot be measured reliably in a unit test, so this pins the code
     * path: the subprotocol bearer goes through the same digest compare as the Authorization header.
     */
    @Test
    fun theSubprotocolTokenIsComparedInConstantTimeLikeTheHeader() {
        val source = File("src/main/kotlin/com/superiorsoftllc/infantry/daemon/AccessPolicy.kt")
        if (!source.isFile) return // run from another directory: the behavioural tests above still hold
        val text = source.readText()
        val matches = text.substringAfter("private fun matchesToken(").substringBefore("\n    }")
        assertTrue("MessageDigest.isEqual(digest(presented), expected)" in matches, matches)
        val browser = text.substringAfter("private fun checkBrowser(").substringBefore("\n    }")
        assertTrue("matchesToken(it)" in browser && "bearerMatches(authorization)" in browser, browser)
        assertTrue(Regex("""presented\s*==|==\s*presented|\.equals\(presented""").find(text) == null, "no string compare of a presented token")
    }
}
