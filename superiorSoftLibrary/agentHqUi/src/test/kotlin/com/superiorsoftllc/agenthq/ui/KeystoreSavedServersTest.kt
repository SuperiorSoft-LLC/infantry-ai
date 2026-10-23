package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.ui.pair.PairingLink
import com.superiorsoftllc.agenthq.ui.pair.SavedServer
import com.superiorsoftllc.agenthq.ui.pair.applyPairing
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KeystoreSavedServersTest {
    private val dir = Files.createTempDirectory("agenthq-saved-servers").toFile()
    private fun store() = KeystoreSavedServers(dir, Keystore(dir, env = { if (it == "AGENTHQ_KEYSTORE_KEY") "test-master-key" else null }, vault = null))

    @Test
    fun scannedLinkIsSavedWithItsTokenSealedAndConnectedWithTheBearer() {
        val secret = "bearer-SECRET-0123456789"
        var connected: Pair<String, String?>? = null
        applyPairing(PairingLink("ws://100.67.51.8:8797/ws", secret, "Gaming PC"), store()) { u, t -> connected = u to t }
        assertEquals("ws://100.67.51.8:8797/ws" to secret, connected)

        val reloaded = store().list().single()
        assertEquals("Gaming PC", reloaded.name)
        assertEquals(secret, reloaded.token)
        // At rest: neither the list file nor the keystore file holds the token in the clear.
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { assertFalse(it.readText().contains(secret), it.name) }
    }

    @Test
    fun savingTheSameUrlReplacesAndRemoveForgets() {
        val s = store()
        s.save(SavedServer("A", "ws://h:1/ws", "t1"))
        s.save(SavedServer("B", "ws://h:1/ws", "t2"))
        assertEquals("t2", s.list().single().token)
        assertEquals("B", s.list().single().name)
        s.remove("ws://h:1/ws")
        assertEquals(0, s.list().size)
        assertNull(Keystore(dir, env = { if (it == "AGENTHQ_KEYSTORE_KEY") "test-master-key" else null }, vault = null)
            .get(KeystoreSavedServers.secretName("ws://h:1/ws")))
    }
}
