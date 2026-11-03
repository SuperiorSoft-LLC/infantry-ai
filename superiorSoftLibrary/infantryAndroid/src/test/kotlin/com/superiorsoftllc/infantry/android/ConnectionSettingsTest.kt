package com.superiorsoftllc.infantry.android

import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.shell.ConnectOutcome
import com.superiorsoftllc.infantry.ui.shell.ConnectionStore
import com.superiorsoftllc.infantry.ui.shell.DaemonUrlValidator
import com.superiorsoftllc.infantry.ui.shell.KeyValueStore
import com.superiorsoftllc.infantry.ui.shell.LaunchDecision
import com.superiorsoftllc.infantry.ui.shell.ShellKeys
import com.superiorsoftllc.infantry.ui.shell.TokenCipher
import com.superiorsoftllc.infantry.ui.shell.launchDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOBILE-CONNECT-1 (gaps G6, G9). Today InfantryActivity only READS the "infantry" prefs and nothing writes them, so a
 * physical phone has no way to keep a daemon URL + token: it always dials the emulator alias ws://10.0.2.2:8787/ws.
 * These tests pin the store that writes them (URL plain, token sealed by the Keystore cipher), the URL validator, and
 * the launch decision that shows the connect screen when nothing is saved.
 */
class ConnectionSettingsTest {
    /** In-memory SharedPreferences stand-in. */
    private class MapStore : KeyValueStore {
        val map = linkedMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    /** Reversible, visibly-not-plaintext stand-in for the AndroidKeyStore AES-GCM cipher (Keystore is not on a JVM). */
    private object FakeCipher : TokenCipher {
        override fun encrypt(plain: String): String = "sealed:" + plain.reversed().map { (it.code + 1).toChar() }.joinToString("")
        override fun decrypt(sealed: String): String? =
            sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.map { (it.code - 1).toChar() }?.joinToString("")?.reversed()
    }

    private val token = "s3cret-bearer-TOKEN"
    private val url = "ws://100.101.102.103:9123/ws"

    @Test fun savedConnectionIsUsedAtNextLaunch() {
        val plain = MapStore(); val secure = MapStore()
        ConnectionStore(plain, secure, FakeCipher).save(url, token)
        // A fresh store over the same files, as on the next launch.
        val reopened = ConnectionStore(plain, secure, FakeCipher)
        assertEquals(url, reopened.setting(ShellKeys.WS_URL))
        assertEquals(token, reopened.setting(ShellKeys.TOKEN))
        assertIs<LaunchDecision.Connect>(launchDecision(extras = { null }, store = reopened))
    }

    @Test fun tokenRoundTripsThroughTheCipherAndNeverLandsInPlainPrefs() {
        val plain = MapStore(); val secure = MapStore()
        ConnectionStore(plain, secure, FakeCipher).save(url, token)
        assertEquals(url, plain.map[ShellKeys.WS_URL])
        assertNull(plain.map[ShellKeys.TOKEN], "token key must not exist in plain prefs")
        assertFalse(plain.map.values.any { token in it }, "token text in plain prefs: ${plain.map}")
        assertFalse(secure.map.values.any { token in it }, "secure prefs must hold ciphertext only: ${secure.map}")
        assertEquals(token, ConnectionStore(plain, secure, FakeCipher).load()?.token)
    }

    @Test fun legacyPlainTokenIsMigratedIntoSealedStorage() {
        val plain = MapStore().apply { map[ShellKeys.WS_URL] = url; map[ShellKeys.TOKEN] = token }
        val secure = MapStore()
        val store = ConnectionStore(plain, secure, FakeCipher)
        assertEquals(token, store.load()?.token)
        assertNull(plain.map[ShellKeys.TOKEN], "the legacy plain token must be removed once sealed")
    }

    @Test fun unreadableCiphertextMeansNoTokenNotACrash() {
        val plain = MapStore(); val secure = MapStore()
        ConnectionStore(plain, secure, FakeCipher).save(url, token)
        secure.map.keys.toList().forEach { secure.map[it] = "garbage" }
        val loaded = ConnectionStore(plain, secure, FakeCipher).load()
        assertEquals(url, loaded?.url)
        assertNull(loaded?.token)
    }

    @Test fun clearForgetsBoth() {
        val plain = MapStore(); val secure = MapStore()
        val store = ConnectionStore(plain, secure, FakeCipher)
        store.save(url, token)
        store.clear()
        assertNull(store.load())
        assertTrue(plain.map.isEmpty() && secure.map.isEmpty())
    }

    @Test fun nothingSavedAndNoExtrasShowsTheConnectScreen() {
        assertIs<LaunchDecision.Setup>(launchDecision(extras = { null }, store = ConnectionStore(MapStore(), MapStore(), FakeCipher)))
    }

    @Test fun intentExtrasStillWinForTestsAndAdb() {
        val store = ConnectionStore(MapStore(), MapStore(), FakeCipher).apply { save(url, token) }
        val extras = mapOf(ShellKeys.WS_URL to "ws://10.0.2.2:9555/ws")
        val decision = launchDecision(extras = { extras[it] }, store = store)
        assertIs<LaunchDecision.Connect>(decision)
        assertEquals("ws://10.0.2.2:9555/ws", decision.settings[ShellKeys.WS_URL])
        assertEquals(token, decision.settings[ShellKeys.TOKEN], "an extra URL alone keeps the saved token")
    }

    @Test fun validatorAcceptsWsAndWssWithAHost() {
        listOf(
            "ws://10.0.2.2:8787/ws", "wss://box.tail1234.ts.net/ws", "ws://100.64.1.2:9000/ws",
            "ws://[fd7a:115c:a1e0::1]:8787/ws", "WS://Example.com/ws", "  ws://host/ws  ",
        ).forEach { assertNull(DaemonUrlValidator.validate(it), "should accept '$it'") }
    }

    @Test fun validatorRejectsEverythingElse() {
        listOf(
            "", "   ", "http://host/ws", "https://host/ws", "host:8787/ws", "ws://", "ws:///ws",
            "ws://host:99999/ws", "ws://ho st/ws", "ws://:8787/ws", "ftp://host",
        ).forEach { assertNotNull(DaemonUrlValidator.validate(it), "should reject '$it'") }
    }

    @Test fun probeOutcomeNamesBadTokenAndUnreachable() {
        assertIs<ConnectOutcome.Connected>(ConnectOutcome.of(ConnectionInfo(state = ConnState.CONNECTED)))
        val refused = ConnectOutcome.of(ConnectionInfo(state = ConnState.REFUSED, detail = "daemon refused the connection (HTTP 401): check the bearer token"))
        assertIs<ConnectOutcome.BadToken>(refused)
        assertTrue("401" in refused.message && "token" in refused.message.lowercase())
        val down = ConnectOutcome.of(ConnectionInfo(state = ConnState.RECONNECTING, attempt = 1, detail = "ConnectException: Connection refused"))
        assertIs<ConnectOutcome.Unreachable>(down)
        assertTrue("unreachable" in down.message.lowercase())
        assertNull(ConnectOutcome.of(ConnectionInfo(state = ConnState.CONNECTING)), "still deciding")
    }
}
