// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.provider.HarnessModelCatalog
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.ModelCatalogCache
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-ModelsHeadOfLine: `models` (sent in every client's connect burst) used to run the harness catalogs -- Pi's
 * host `pi --list-models`, up to its 20 s bound -- inline on the connection's request loop, so every request the
 * client sent after it queued behind the child process. The answer now comes off the loop: a slow catalog delays
 * only the `models` reply, never the requests that follow it.
 *
 * BUG MODEL-CATALOG-1: and it no longer waits for it to finish either. A harness that has never listed its models is
 * awaited only `limits.models.catalogWaitMs`, then the answer says it is still listing; a harness that listed before
 * is answered at once from the cache and refreshed behind the answer. One slow harness never empties another's list.
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
        val registry = HarnessRegistry().also { it.register(StubCatalogHarness.plugin("pi")) }
        val fixture = DaemonFixture.launch(
            harnesses = registry,
            modelCatalogs = HarnessModelCatalogs(mapOf("pi" to slowPi)),
            modelCatalogCache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 400 }),
        )
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the daemon asked the Pi catalog")

            // The connection keeps answering while the Pi listing is still running.
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"after-models"}""")
            assertNotNull(fixture.client.awaitOrNull(Envelope.PROJECT, timeoutMs = 4_000), "project_save waited behind models")
            val waited = fixture.client.await(Envelope.MODELS)
            val rows = json.decodeFromString(ListSerializer(ProviderModels.serializer()), waited.data)
            val pi = rows.single { it.provider == "pi" }
            assertTrue(pi.models.isEmpty(), "a listing that is still running holds the answer back")
            assertTrue("pi has not listed its models yet" in pi.note.orEmpty(), "the answer names the slow harness: ${pi.note}")

            // The listing was not cancelled: the next request carries it, from the cache.
            release.countDown()
            val later = awaitPiModels(fixture, 1).first
            assertEquals(listOf("acme/m1"), later.models)
            assertNull(later.note)
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun oneSlowHarnessLeavesEveryOtherHarnesssModelsInTheAnswer() {
        val release = CountDownLatch(1)
        val registry = HarnessRegistry().also { it.register(StubCatalogHarness.plugin("pi", "claude")) }
        val fixture = DaemonFixture.launch(
            harnesses = registry,
            modelCatalogs = HarnessModelCatalogs(
                mapOf(
                    "pi" to HarnessModelCatalog {
                        release.await(30, TimeUnit.SECONDS)
                        listOf(ProviderModels("pi", "acme", "acme", listOf("acme/m1")))
                    },
                    // The static-list harness: instant, and it must not be lost because pi is slow.
                    "claude" to HarnessModelCatalog { listOf(ProviderModels("claude", "acme", "acme", listOf("opus", "sonnet"))) },
                ),
            ),
            modelCatalogCache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 400 }),
        )
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            val rows = json.decodeFromString(ListSerializer(ProviderModels.serializer()), fixture.client.await(Envelope.MODELS).data)
            assertEquals(listOf("opus", "sonnet"), rows.filter { it.provider == "claude" }.flatMap { it.models }, "claude's models survived pi's slow listing")
            assertTrue(rows.none { it.provider == "pi" && it.models.isNotEmpty() }, "pi had listed nothing yet")
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun twoModelsRequestsAreAnsweredInTheOrderTheyWereSent() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val registry = HarnessRegistry().also { it.register(StubCatalogHarness.plugin("pi")) }
        val fixture = DaemonFixture.launch(
            harnesses = registry,
            modelCatalogs = HarnessModelCatalogs(
                mapOf(
                    "pi" to HarnessModelCatalog {
                        val n = calls.incrementAndGet()
                        if (n == 1) release.await(30, TimeUnit.SECONDS)
                        listOf(ProviderModels("pi", "acme", "acme", listOf("acme/m$n")))
                    },
                ),
            ),
            modelCatalogCache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 300 }),
        )
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            fixture.send(Envelope(Envelope.MODELS))
            fun piModels(occurrence: Int) = json.decodeFromString(
                ListSerializer(ProviderModels.serializer()),
                fixture.client.await(Envelope.MODELS, occurrence).data,
            ).single { it.provider == "pi" }
            // Both answers arrive, in order, while the one listing is still running: neither waits for it.
            assertTrue(piModels(0).models.isEmpty())
            assertTrue(piModels(1).models.isEmpty())
            assertEquals(1, calls.get(), "a burst of requests shares one listing")
            release.countDown()
            assertEquals(listOf("acme/m1"), awaitPiModels(fixture, 2).first.models, "the finished listing is served from the cache")
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }

    /**
     * Asks again (bounded) until the cached pi listing has landed; returns it and the next occurrence index to read,
     * because the fixture's [DaemonFixture.client] answers are indexed, not consumed.
     */
    private fun awaitPiModels(fixture: DaemonFixture, occurrence: Int): Pair<ProviderModels, Int> {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var next = occurrence
        var last: ProviderModels? = null
        while (System.nanoTime() < until) {
            fixture.send(Envelope(Envelope.MODELS))
            val rows = json.decodeFromString(
                ListSerializer(ProviderModels.serializer()),
                fixture.client.await(Envelope.MODELS, next).data,
            )
            next++
            last = rows.single { it.provider == "pi" }
            if (last.models.isNotEmpty()) return last to next
            Thread.sleep(50)
        }
        error("the pi listing never landed: ${last?.models} / ${last?.note}")
    }
}
