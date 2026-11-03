package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WIRE-1: the daemon loads HarnessRegistry (ServiceLoader) at startup and the `providers` frame the UI
 * already uses lists what the plugins registered. Boots the REAL daemon on a real WebSocket.
 */
class HarnessWiringDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun providersListContainsClaudeAndPiFromTheLoadedHarnessPlugins() {
        val fixture = DaemonFixture.launch(harnesses = loadHarnesses(descriptorDir = null)) // never the operator's own descriptors
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.PROVIDERS))
            val answer = fixture.client.await(Envelope.PROVIDERS)
            val ids = json.decodeFromString(ListSerializer(ProviderInfo.serializer()), answer.data).map { it.id }

            assertTrue("claude" in ids, "providers must list claude, got $ids")
            assertTrue("pi" in ids, "providers must list pi, got $ids")
            assertEquals("stub", ids.first(), "the default provider stays first")
            assertEquals(ids.size, ids.toSet().size, "no provider listed twice: $ids")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
