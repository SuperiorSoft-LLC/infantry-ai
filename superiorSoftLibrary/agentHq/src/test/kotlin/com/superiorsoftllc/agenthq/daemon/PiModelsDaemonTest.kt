package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.pi.PiHarnessPlugin
import com.superiorsoftllc.agenthq.harness.pi.PiModelCatalog
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROLE-FIRST S4: a REAL daemon with the Pi plugin registered (and no Pi provider configured) answers the existing
 * `models` frame with Pi's providers/models, from the recorded `--list-models` table -- no Pi spawned.
 */
class PiModelsDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun models(catalogs: HarnessModelCatalogs): List<ProviderModels> {
        val registry = HarnessRegistry().also { it.register(PiHarnessPlugin()) }
        val fixture = DaemonFixture.launch(harnesses = registry, modelCatalogs = catalogs)
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            return json.decodeFromString(ListSerializer(ProviderModels.serializer()), fixture.client.await(Envelope.MODELS).data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun theModelsFrameCarriesPiRowsBesideTheConfiguredProviders() {
        val recorded = javaClass.getResourceAsStream("/pi/list-models.txt")!!.bufferedReader().readText()
        val all = models(HarnessModelCatalogs(mapOf("pi" to PiModelCatalog(listModels = { recorded }, storeFile = { null }))))
        val pi = all.filter { it.provider == "pi" }
        assertEquals(6, pi.size, "$pi")
        assertEquals(179, pi.sumOf { it.models.size })
        assertTrue("google/" in pi.single { it.group == "google" }.models.first())
        assertTrue(all.any { it.provider == "stub" }, "the configured provider is still listed: $all")
    }

    @Test
    fun aFailingPiListingIsANotedRowNotAnError() {
        val all = models(HarnessModelCatalogs(mapOf("pi" to PiModelCatalog(listModels = { error("pi not found") }, storeFile = { null }))))
        val pi = all.single { it.provider == "pi" }
        assertTrue(pi.models.isEmpty())
        assertTrue(pi.note!!.contains("pi not found"), pi.note)
    }
}
