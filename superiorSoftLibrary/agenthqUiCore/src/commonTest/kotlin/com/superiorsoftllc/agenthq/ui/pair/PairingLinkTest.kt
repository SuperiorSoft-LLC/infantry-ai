package com.superiorsoftllc.agenthq.ui.pair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {
    private fun roundTrip(link: PairingLink): PairingLink {
        val parsed = assertIs<PairingLink.Parsed.Ok>(PairingLink.parse(link.toUri()))
        return parsed.link
    }

    @Test
    fun roundTripPlain() {
        val link = PairingLink("ws://100.67.51.8:8797/ws", "tok-123", "Gaming PC")
        assertEquals(link, roundTrip(link))
        assertTrue(link.toUri().startsWith("agenthq://connect?url=ws%3A%2F%2F100.67.51.8%3A8797%2Fws"))
    }

    @Test
    fun roundTripSpecialCharacters() {
        val link = PairingLink("wss://example.com:443/ws?x=1&y=2", "a+b/c=d&e%f #g?hé中😀", "Anthony's \"box\" & co")
        assertEquals(link, roundTrip(link))
    }

    @Test
    fun roundTripIpv6AndWss() {
        val v6 = PairingLink("ws://[fd7a:115c:a1e0::1]:8797/ws", "t", null)
        assertEquals(v6, roundTrip(v6))
        assertEquals("fd7a:115c:a1e0::1", PairingLink.hostOf(v6.url))
        val wss = PairingLink("wss://agent.example.net/ws", "t", "x")
        assertEquals(wss, roundTrip(wss))
    }

    @Test
    fun tokenWithoutNameAndTokenlessLinks() {
        assertNull(roundTrip(PairingLink("ws://10.0.0.5:1/ws", "t", null)).name)
        assertNull(roundTrip(PairingLink("ws://10.0.0.5:1/ws", null, null)).token)
    }

    @Test
    fun parseRejectsBadInput() {
        for (bad in listOf("", "https://x", "agenthq://connect?", "agenthq://connect?url=http%3A%2F%2Fx", "agenthq://connect?url=ws%3A%2F%2F",
            "agenthq://connect?url=ws%3A%2F%2Fa%20b%2Fws", "agenthq://connect?url=ws%3A%2F%2Fh%2Fws&token=%ZZ", "agenthq://other?url=ws%3A%2F%2Fh")) {
            assertIs<PairingLink.Parsed.Bad>(PairingLink.parse(bad), bad)
        }
    }

    @Test
    fun parseToleratesWhitespaceAndFragment() {
        val ok = assertIs<PairingLink.Parsed.Ok>(PairingLink.parse("  AGENTHQ://connect?url=ws%3A%2F%2Fh%3A1%2Fws&token=t#frag\n"))
        assertEquals("ws://h:1/ws", ok.link.url)
    }

    @Test
    fun errorsAndToStringNeverEchoTheToken() {
        val secret = "SuperSecretToken987"
        assertFalse(PairingLink("ws://h:1/ws", secret, null).toString().contains(secret))
        val bad = assertIs<PairingLink.Parsed.Bad>(PairingLink.parse("agenthq://connect?url=http%3A%2F%2Fx&token=$secret"))
        assertFalse(bad.reason.contains(secret))
        val refused = assertIs<PairingLink.Result.Refused>(PairingLink.forShow("ws://127.0.0.1:8797/ws", secret, null))
        assertFalse(refused.reason.contains(secret))
        assertFalse(SavedServer("n", "ws://h:1/ws", secret).toString().contains(secret))
    }

    @Test
    fun showRefusesLoopbackAndMissingToken() {
        for (host in listOf("127.0.0.1", "localhost", "[::1]", "127.1.2.3", "0.0.0.0", "LOCALHOST")) {
            assertIs<PairingLink.Result.Refused>(PairingLink.forShow("ws://$host:8797/ws", "t", null), host)
        }
        assertIs<PairingLink.Result.Refused>(PairingLink.forShow("ws://100.67.51.8:8797/ws", "", null))
        assertIs<PairingLink.Result.Refused>(PairingLink.forShow("http://100.67.51.8/ws", "t", null))
        assertIs<PairingLink.Result.Ok>(PairingLink.forShow("ws://100.67.51.8:8797/ws", "t", " Box "))
    }

    @Test
    fun noTokenOnLoopbackExplainsHowToTurnRemoteOn() {
        val refused = assertIs<PairingLink.Result.Refused>(showableLink("ws://127.0.0.1:8787/ws", null, null))
        assertTrue(refused.reason.contains("remote on"))
    }

    @Test
    fun reachableAddressesRankTailscaleThenLanAndKeepThePort() {
        val urls = PairingHosts.reachableUrls("ws://127.0.0.1:8797/ws", listOf("192.168.1.20", "fe80::1", "100.67.51.8", "127.0.0.1", "fd00::5"))
        assertEquals("ws://100.67.51.8:8797/ws", urls.first())
        assertEquals("ws://192.168.1.20:8797/ws", urls[1])
        assertTrue("ws://[fd00::5]:8797/ws" in urls)
        assertTrue(urls.none { PairingLink.isLoopbackUrl(it) })
        assertEquals(listOf("ws://100.67.51.8:8797/ws"), PairingHosts.reachableUrls("ws://100.67.51.8:8797/ws", listOf("1.2.3.4")))
        assertEquals(emptyList(), PairingHosts.reachableUrls("ws://localhost:8797/ws", listOf("127.0.0.1")))
    }

    @Test
    fun scanPathSavesServerAndTokenThenConnectsWithTheBearer() {
        val store = InMemorySavedServers()
        var connected: Pair<String, String?>? = null
        val link = PairingLink("ws://100.67.51.8:8797/ws", "bearer-xyz", "Gaming PC")
        applyPairing(link, store) { u, t -> connected = u to t }
        assertEquals("ws://100.67.51.8:8797/ws" to "bearer-xyz", connected)
        val saved = store.list().single()
        assertEquals("Gaming PC", saved.name)
        assertEquals("bearer-xyz", saved.token)
    }
}
