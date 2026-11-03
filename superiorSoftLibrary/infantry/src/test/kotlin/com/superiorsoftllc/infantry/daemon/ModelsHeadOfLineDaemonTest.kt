package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.provider.HarnessModelCatalog
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-ModelsHeadOfLine: `models` (sent in every client's connect burst) used to run the harness catalogs -- Pi's
 * host `pi --list-models`, up to its 20 s bound -- inline on the connection's request loop, so every request the
 * client sent after it queued behind the child process. The answer now comes off the loop: a slow catalog delays
 * only the `models` reply, never the requests that follow it on the same connection.
 */
class ModelsHeadOfLineDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aSlowPiListingDoesNotBlockTheRequestsSentAfterModels() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val slowPi = HarnessModelCatalog {
            entered.countDown()
            // Stands in for `pi --list-models` under load; released by the test, bounded so a bug cannot hang CI.
            release.await(30, TimeUnit.SECONDS)
            listOf(ProviderModels("pi", "acme", "acme", listOf("acme/m1")))
        }
        val registry = HarnessRegistry().also { it.register(StubPiPlugin) }
        val fixture = DaemonFixture.launch(harnesses = registry, modelCatalogs = HarnessModelCatalogs(mapOf("pi" to slowPi)))
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the daemon asked the Pi catalog")

            // The connection keeps answering while the Pi listing is still running.
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"after-models"}""")
            assertNotNull(fixture.client.awaitOrNull(Envelope.PROJECT, timeoutMs = 4_000), "project_save waited behind models")
            assertNull(fixture.client.awaitOrNull(Envelope.MODELS, timeoutMs = 200), "models answered before its catalog finished")

            release.countDown()
            val rows = json.decodeFromString(ListSerializer(ProviderModels.serializer()), fixture.client.await(Envelope.MODELS).data)
            assertEquals(listOf("acme/m1"), rows.single { it.provider == "pi" }.models)
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun twoModelsRequestsAreAnsweredInTheOrderTheyWereSent() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val release = CountDownLatch(1)
        // ttl 0: every request re-lists, so the two answers differ and their order is observable.
        val pi = HarnessModelCatalog {
            val n = calls.incrementAndGet()
            if (n == 1) release.await(30, TimeUnit.SECONDS)
            listOf(ProviderModels("pi", "acme", "acme", listOf("acme/m$n")))
        }
        val registry = HarnessRegistry().also { it.register(StubPiPlugin) }
        val fixture = DaemonFixture.launch(harnesses = registry, modelCatalogs = HarnessModelCatalogs(mapOf("pi" to pi)))
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            fixture.send(Envelope(Envelope.MODELS))
            Thread.sleep(200)
            release.countDown()
            fun piModels(occurrence: Int) = json.decodeFromString(
                ListSerializer(ProviderModels.serializer()),
                fixture.client.await(Envelope.MODELS, occurrence).data,
            ).single { it.provider == "pi" }.models
            assertEquals(listOf("acme/m1"), piModels(0))
            assertEquals(listOf("acme/m2"), piModels(1))
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }
}

/** A "pi" harness for the registry only (pi is a module now): the models frame asks the catalog of every registered harness. */
private object StubPiPlugin : com.superiorsoftllc.infantry.harness.HarnessPlugin {
    override val pluginId = "stub-pi"
    override val contractVersion = com.superiorsoftllc.infantry.harness.HARNESS_CONTRACT_VERSION
    override fun register(registrar: com.superiorsoftllc.infantry.harness.HarnessRegistrar) = registrar.register(
        object : com.superiorsoftllc.infantry.harness.CliHarness(
            com.superiorsoftllc.infantry.runner.PipedProcessRunner(), { com.superiorsoftllc.infantry.runner.ProcessSpec(listOf("x")) },
            {
                object : com.superiorsoftllc.infantry.harness.HarnessTranslator {
                    override fun translate(line: String) = emptyList<com.superiorsoftllc.infantry.harness.HarnessEvent>()
                    override fun terminal(exitCode: Int, stderrTail: String): com.superiorsoftllc.infantry.harness.HarnessEvent = com.superiorsoftllc.infantry.harness.HarnessEvent.Completion()
                }
            },
            "pi",
        ) {
            override val id = "pi"
            override val contractVersion = com.superiorsoftllc.infantry.harness.HARNESS_CONTRACT_VERSION
            override val capabilities = emptySet<com.superiorsoftllc.infantry.harness.HarnessCapability>()
        },
    )
}
