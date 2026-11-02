package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Model + provider picker: the `models` frame lists what every CONFIGURED provider can run, read from
 * the providers themselves (never a hardcoded list), and a provider with nothing to offer is shown
 * with the reason instead of being hidden.
 */
class ModelsDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Catalogued : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "catalogued"
        override fun models() = listOf(
            ProviderModels("catalogued", "alpha", "Alpha", listOf("alpha/one", "alpha/two")),
            ProviderModels("catalogued", "beta", "Beta", listOf("beta/three")),
        )
    }

    private class Empty : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "empty"
        override fun models() = listOf(ProviderModels("empty", "", "empty", emptyList(), "no models configured"))
    }

    private class Broken : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "broken"
        override fun models(): List<ProviderModels> = error("cli exploded")
    }

    private fun models(vararg extra: AgentProvider): List<ProviderModels> {
        val fixture = DaemonFixture.launch(additionalProviders = extra.toList())
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            val answer = fixture.client.await(Envelope.MODELS)
            return json.decodeFromString(ListSerializer(ProviderModels.serializer()), answer.data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun everyConfiguredProviderIsListedWithItsOwnModels() {
        val all = models(Catalogued())
        assertEquals(listOf("alpha/one", "alpha/two"), all.first { it.group == "alpha" }.models)
        assertEquals(listOf("beta/three"), all.first { it.group == "beta" }.models)
        assertTrue(all.any { it.provider == "stub" }, "the default provider is listed too: $all")
    }

    @Test
    fun aProviderWithNoModelsIsRepresentedHonestlyNotHidden() {
        val entry = models(Empty()).first { it.provider == "empty" }
        assertTrue(entry.models.isEmpty())
        assertEquals("no models configured", entry.note)
    }

    @Test
    fun aProviderWhoseListingFailsCarriesTheReasonAndDoesNotBreakTheOthers() {
        val all = models(Broken(), Catalogued())
        val broken = assertNotNull(all.firstOrNull { it.provider == "broken" })
        assertTrue(broken.models.isEmpty())
        assertTrue(broken.note!!.contains("cli exploded"), broken.note)
        assertTrue(all.any { it.provider == "catalogued" })
    }
}
