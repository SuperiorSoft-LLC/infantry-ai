package com.superiorsoftllc.agenthq.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// HARNESS 2/4. Every plugin below is a THROWAWAY: not Claude, not Pi. The registry must treat all of
// them (and HARNESS 1/4's own FakeHarness) identically, because it knows no harness by name.

/** Counts how often it was actually used, so "never called again after unload" is measurable. */
private class ThrowawayHarness(
    override val id: String,
    override val capabilities: Set<HarnessCapability> = emptySet(),
    private val failWith: Throwable? = null,
) : Harness {
    val eventsCalls = AtomicInteger()
    override fun events(request: HarnessRequest): Flow<HarnessEvent> = flow {
        eventsCalls.incrementAndGet()
        emit(HarnessEvent.TextDelta("hello from $id"))
        failWith?.let { throw it }
        emit(HarnessEvent.Completion())
    }
}

private class ThrowawayPlugin(
    override val pluginId: String,
    val harnesses: List<Harness>,
    override val contractVersion: String = HARNESS_CONTRACT_VERSION,
    private val throwAfterRegistering: Throwable? = null,
    private val throwOnUnload: Throwable? = null,
) : HarnessPlugin {
    val registerCalls = AtomicInteger()
    val unloadCalls = AtomicInteger()
    var stashedRegistrar: HarnessRegistrar? = null

    override fun register(registrar: HarnessRegistrar) {
        registerCalls.incrementAndGet()
        stashedRegistrar = registrar
        harnesses.forEach(registrar::register)
        throwAfterRegistering?.let { throw it } // AFTER registering: a half-registered plugin is the hard case
    }

    override fun unload() {
        unloadCalls.incrementAndGet()
        throwOnUnload?.let { throw it }
    }
}

private fun sourceOf(vararg plugins: HarnessPlugin) = HarnessPluginSource {
    plugins.map { p -> PluginCandidate("candidate:${p.pluginId}") { p } }
}

private fun req(harnessId: String) = HarnessRequest(prompt = "p", harnessId = harnessId, project = "proj")

class HarnessRegistryTest {
    /** Everything the registry reported through its failure sink. A failure that never reaches here was swallowed. */
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val registry = HarnessRegistry(onFailure = { failures += it })

    // ---- discovery + version handshake -------------------------------------------------------

    @Test
    fun aPluginDeclaringTheImplementedVersionIsDiscoveredAcceptedAndUsable() = runBlocking {
        val plugin = ThrowawayPlugin("throwaway-a", listOf(ThrowawayHarness("zz-alpha", setOf(HarnessCapability.USAGE))))

        val report = registry.discover(sourceOf(plugin))

        assertEquals(listOf<PluginOutcome>(PluginOutcome.Accepted("throwaway-a", listOf("zz-alpha"))), report.outcomes)
        assertEquals(1, plugin.registerCalls.get())
        assertEquals(listOf("zz-alpha"), registry.harnessIds())
        assertEquals(listOf("throwaway-a"), registry.pluginIds())
        // and it is USABLE, not merely listed
        assertEquals(
            listOf(HarnessEvent.TextDelta("hello from zz-alpha"), HarnessEvent.Completion()),
            registry.run(req("zz-alpha")).toList(),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aPluginDeclaringAnUnimplementedVersionIsRefusedWithAClearReasonAndNeverRun() {
        val wrong = listOf("99.0.0", "0.0.1", "banana", "", "$HARNESS_CONTRACT_VERSION-snapshot")
        assertTrue(HARNESS_CONTRACT_VERSION !in wrong, "test setup: every version below must differ from the implemented one")
        val plugins = wrong.mapIndexed { i, v ->
            ThrowawayPlugin("throwaway-v$i", listOf(ThrowawayHarness("zz-v$i")), contractVersion = v)
        }

        val report = registry.discover(sourceOf(*plugins.toTypedArray()))

        assertEquals(wrong.size, report.refused.size, "every mismatched plugin must be refused, none accepted or lost")
        assertTrue(report.accepted.isEmpty() && report.failed.isEmpty())
        report.refused.forEachIndexed { i, r ->
            assertEquals(RefusalReason.VERSION_MISMATCH, r.reason)
            assertTrue("throwaway-v$i" in r.detail, "reason must name the plugin: ${r.detail}")
            assertTrue("'${wrong[i]}'" in r.detail, "reason must quote the version the plugin declared: ${r.detail}")
            assertTrue("'$HARNESS_CONTRACT_VERSION'" in r.detail, "reason must say what the daemon implements: ${r.detail}")
        }
        // refused BEFORE the plugin could run: it is not "registered and left broken"
        assertTrue(plugins.all { it.registerCalls.get() == 0 }, "a refused plugin's register() must never be called")
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty())
        assertFailsWith<UnknownHarnessException> { registry.run(req("zz-v0")) }
    }

    @Test
    fun theDaemonAcceptsExactlyTheVersionsItWasToldItImplements() {
        val reg = HarnessRegistry(supportedVersions = setOf("1.0.0", "1.1.0"), onFailure = { failures += it })
        val newer = ThrowawayPlugin("p-newer", listOf(ThrowawayHarness("zz-newer")), contractVersion = "1.1.0")
        val tooNew = ThrowawayPlugin("p-too-new", listOf(ThrowawayHarness("zz-too-new")), contractVersion = "1.2.0")

        val report = reg.discover(sourceOf(newer, tooNew))

        assertEquals(listOf("p-newer"), report.accepted.map { it.pluginId })
        assertEquals(listOf(RefusalReason.VERSION_MISMATCH), report.refused.map { it.reason })
        assertEquals(listOf("zz-newer"), reg.harnessIds())
    }

    @Test
    fun registrationDoesNotCareWhatThePluginIs() = runBlocking<Unit> {
        // HARNESS 1/4's own fake, registered as a plugin, next to a throwaway nobody ever heard of.
        val fakePlugin = ThrowawayPlugin("fake-plugin", listOf(FakeHarness()))
        val odd = ThrowawayHarness("zz-no-such-vendor", setOf(HarnessCapability.REASONING, HarnessCapability.SESSION_RESUME))
        val report = registry.discover(sourceOf(fakePlugin, ThrowawayPlugin("odd-plugin", listOf(odd))))

        assertEquals(2, report.accepted.size)
        assertEquals(
            listOf(
                HarnessEvent.TextDelta("he"), HarnessEvent.TextDelta("llo"),
                HarnessEvent.ToolCall("c1", "read", "{}"), HarnessEvent.Error("boom"),
            ),
            registry.run(req("fake")).toList(),
        )
        assertEquals(HarnessEvent.TextDelta("hello from zz-no-such-vendor"), registry.run(req("zz-no-such-vendor")).toList().first())
        // behaviour comes from what the PLUGIN declared, not from anything the daemon knows about it
        registry.run(req("zz-no-such-vendor").copy(sessionId = "s")).toList() // SESSION_RESUME declared: fine
        assertFailsWith<MissingCapabilityException> { registry.run(req("zz-no-such-vendor").copy(model = "m")) }
    }

    @Test
    fun discoveryThatFindsNoUsablePluginRegistersNothing_noBuiltInFallback() {
        assertTrue(registry.discover(sourceOf()).outcomes.isEmpty())
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty(), "empty discovery must register nothing")

        // a plugin that was found but refused must not trigger a fallback either
        val report = registry.discover(sourceOf(ThrowawayPlugin("throwaway-old", listOf(ThrowawayHarness("zz-old")), contractVersion = "0.0.1")))
        assertEquals(1, report.refused.size)
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty(), "a refusal must not fall back to a built-in list")
        for (name in listOf("claude", "pi", "omp", "fake", "default", "builtin")) {
            assertFailsWith<UnknownHarnessException> { registry.run(req(name)) }
        }
    }

    @Test
    fun theStandardServiceLoaderSourceDiscoversAListedPlugin() = runBlocking {
        val report = registry.discover(ServiceLoaderPluginSource())

        val accepted = report.accepted.single { it.pluginId == ServiceLoaderTestPlugin.PLUGIN_ID }
        assertEquals(listOf(ServiceLoaderTestPlugin.HARNESS_ID), accepted.harnessIds)
        assertTrue(report.failed.isEmpty())
        assertEquals(
            HarnessEvent.TextDelta("from-service-loader"),
            registry.run(req(ServiceLoaderTestPlugin.HARNESS_ID)).toList().first(),
        )
    }

    @Test
    fun theServiceLoaderSourceOnlyLooksWhereItWasTold() {
        val empty = object : ClassLoader(null) {} // no parent, no resources: nothing can be listed
        assertTrue(ServiceLoaderPluginSource(empty).candidates().isEmpty())
        assertTrue(ServiceLoaderPluginSource().candidates().any { it.label == ServiceLoaderTestPlugin::class.java.name })
    }

    // ---- lifecycle: register, use, unload ------------------------------------------------------

    @Test
    fun anUnloadedPluginLeavesNothingBehindForALaterPlugin() = runBlocking {
        val h1 = ThrowawayHarness("zz-one")
        val first = ThrowawayPlugin("throwaway-first", listOf(h1, ThrowawayHarness("zz-two")))
        assertEquals(1, registry.discover(sourceOf(first)).accepted.size) // loaded first: the unload below is not vacuous
        registry.run(req("zz-one")).toList()
        assertEquals(1, h1.eventsCalls.get()) // and really used

        assertEquals(
            UnloadOutcome.Unloaded("throwaway-first", listOf("zz-one", "zz-two")),
            registry.unload("throwaway-first"),
        )

        assertEquals(1, first.unloadCalls.get())
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty())
        assertNull(registry.resolve("zz-one"))
        assertFailsWith<UnknownHarnessException> { registry.run(req("zz-one")) }

        // A later plugin takes over the SAME harness id and the SAME plugin id without tripping over anything.
        val h1Later = ThrowawayHarness("zz-one")
        val later = ThrowawayPlugin("throwaway-first", listOf(h1Later))
        assertEquals(1, registry.discover(sourceOf(later)).accepted.size)
        registry.run(req("zz-one")).toList()
        assertEquals(1, h1Later.eventsCalls.get())
        assertEquals(1, h1.eventsCalls.get(), "the unloaded plugin's harness must never be reached again")
        assertEquals(listOf("zz-one"), registry.harnessIds(), "zz-two belonged to the unloaded plugin and must be gone")
        assertTrue(failures.isEmpty())

        // unloading what is not loaded is reported, not an exception, and does not run plugin code twice
        registry.unload("throwaway-first")
        assertEquals(UnloadOutcome.NotLoaded("throwaway-first"), registry.unload("throwaway-first"))
        assertEquals(1, later.unloadCalls.get())
    }

    @Test
    fun aPluginWhoseUnloadThrowsIsStillRemovedAndReportedAsAFailure() {
        val boom = IllegalStateException("unload boom")
        val plugin = ThrowawayPlugin("throwaway-bad-unload", listOf(ThrowawayHarness("zz-bu")), throwOnUnload = boom)
        registry.discover(sourceOf(plugin)).accepted.single()

        val outcome = assertIs<UnloadOutcome.UnloadFailed>(registry.unload("throwaway-bad-unload"))

        assertSame(boom, outcome.failure.cause)
        assertEquals(PluginFailurePhase.UNLOAD, outcome.failure.phase)
        assertEquals(listOf("zz-bu"), outcome.harnessIds)
        assertEquals(listOf(outcome.failure), failures.toList(), "the failure must reach the sink, not be swallowed")
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty(), "a throwing unload must still remove the plugin")
        // the id is free again
        val again = registry.discover(sourceOf(ThrowawayPlugin("throwaway-bad-unload", listOf(ThrowawayHarness("zz-bu")))))
        assertEquals(1, again.accepted.size)
    }

    @Test
    fun aRegistrarKeptPastRegisterFailsLoudlyInsteadOfSilentlyDroppingTheHarness() {
        val plugin = ThrowawayPlugin("throwaway-late", listOf(ThrowawayHarness("zz-on-time")))
        registry.discover(sourceOf(plugin)).accepted.single()

        assertFailsWith<IllegalStateException> { plugin.stashedRegistrar!!.register(ThrowawayHarness("zz-late")) }

        assertEquals(listOf("zz-on-time"), registry.harnessIds())
    }

    // ---- error surface ------------------------------------------------------------------------

    @Test
    fun aPluginThatThrowsWhileRegisteringSurfacesAsAPluginFailureAndLeavesNothingHalfRegistered() {
        val boom = IllegalStateException("kaboom")
        val bad = ThrowawayPlugin("throwaway-bad", listOf(ThrowawayHarness("zz-half")), throwAfterRegistering = boom)
        val good = ThrowawayPlugin("throwaway-good", listOf(ThrowawayHarness("zz-good")))

        val report = registry.discover(sourceOf(bad, good))

        val failure = report.failed.single().failure
        assertSame(boom, failure.cause)
        assertEquals(PluginFailurePhase.REGISTER, failure.phase)
        assertEquals("throwaway-bad", failure.plugin)
        assertTrue("throwaway-bad" in failure.message && "kaboom" in failure.message, failure.message)
        assertEquals(1, bad.registerCalls.get(), "the plugin really ran and really threw")
        assertEquals(listOf("zz-good"), registry.harnessIds(), "no zz-half, and the healthy plugin is not hidden by its neighbour")
        assertEquals(listOf(failure), failures.toList(), "the failure must reach the sink, not be swallowed")
    }

    @Test
    fun aPluginThatCannotBeConstructedSurfacesAsAPluginFailureNamingIt() {
        val ctorBoom = IllegalArgumentException("ctor boom")
        val good = ThrowawayPlugin("throwaway-good", listOf(ThrowawayHarness("zz-good")))
        val source = HarnessPluginSource {
            listOf(PluginCandidate("com.example.BrokenPlugin") { throw ctorBoom }, PluginCandidate("good") { good })
        }

        val report = registry.discover(source)

        val failure = report.failed.single().failure
        assertEquals(PluginFailurePhase.INSTANTIATE, failure.phase)
        assertEquals("com.example.BrokenPlugin", failure.plugin)
        assertSame(ctorBoom, failure.cause)
        assertEquals(listOf("zz-good"), registry.harnessIds())
        assertEquals(listOf(failure), failures.toList())
    }

    @Test
    fun aSourceThatThrowsSurfacesAsAFailureInsteadOfAnException() {
        val boom = IllegalStateException("source boom")

        val report = registry.discover(HarnessPluginSource { throw boom })

        val failure = report.failed.single().failure
        assertEquals(PluginFailurePhase.DISCOVER, failure.phase)
        assertSame(boom, failure.cause)
        assertEquals(listOf(failure), failures.toList())
        assertTrue(registry.harnessIds().isEmpty())
    }

    @Test
    fun aHarnessThatThrowsWhileRunningIsReportedAsItsPluginsFailure() = runBlocking {
        val boom = IllegalStateException("stream boom")
        registry.discover(sourceOf(ThrowawayPlugin("throwaway-flaky", listOf(ThrowawayHarness("zz-flaky", failWith = boom)))))
        val seen = mutableListOf<HarnessEvent>()

        val e = assertFailsWith<PluginFailureException> { registry.run(req("zz-flaky")).collect { seen += it } }

        assertEquals(listOf<HarnessEvent>(HarnessEvent.TextDelta("hello from zz-flaky")), seen, "it failed mid-stream, after real output")
        assertSame(boom, e.cause)
        assertEquals(PluginFailurePhase.RUN, e.failure.phase)
        assertEquals("throwaway-flaky", e.failure.plugin)
        assertEquals(listOf(e.failure), failures.toList())
    }

    @Test
    fun aMissingCapabilityIsTheCallersErrorNotAPluginFailure() {
        registry.discover(sourceOf(ThrowawayPlugin("throwaway-limited", listOf(ThrowawayHarness("zz-limited")))))

        val e = assertFailsWith<MissingCapabilityException> { registry.run(req("zz-limited").copy(model = "m")) }

        assertEquals(HarnessCapability.MODEL_SELECTION, e.capability)
        assertTrue(failures.isEmpty(), "the plugin did nothing wrong, so no plugin failure may be recorded")
    }

    // ---- identity rules -----------------------------------------------------------------------

    @Test
    fun aSecondPluginClaimingATakenHarnessIdIsRefusedNamingTheOwnerAndLeavesNoResidue() = runBlocking {
        val first = ThrowawayPlugin("throwaway-owner", listOf(ThrowawayHarness("zz-taken")))
        val second = ThrowawayPlugin("throwaway-squatter", listOf(ThrowawayHarness("zz-fresh"), ThrowawayHarness("zz-taken")))

        val report = registry.discover(sourceOf(first, second))

        val refused = report.refused.single()
        assertEquals(RefusalReason.DUPLICATE_HARNESS_ID, refused.reason)
        assertTrue("zz-taken" in refused.detail && "throwaway-owner" in refused.detail, refused.detail)
        assertEquals(1, second.registerCalls.get(), "it did run, so zz-fresh was staged and must not leak")
        assertEquals(listOf("zz-taken"), registry.harnessIds())
        assertEquals(listOf("throwaway-owner"), registry.pluginIds())
        assertEquals(HarnessEvent.TextDelta("hello from zz-taken"), registry.run(req("zz-taken")).toList().first())
    }

    @Test
    fun aPluginIdAlreadyLoadedIsRefusedWithoutRunningThePlugin() {
        val first = ThrowawayPlugin("throwaway-dup", listOf(ThrowawayHarness("zz-d1")))
        val again = ThrowawayPlugin("throwaway-dup", listOf(ThrowawayHarness("zz-d2")))

        val report = registry.discover(sourceOf(first, again))

        assertEquals(RefusalReason.DUPLICATE_PLUGIN, report.refused.single().reason)
        assertEquals(0, again.registerCalls.get())
        assertEquals(listOf("zz-d1"), registry.harnessIds())
    }

    @Test
    fun aPluginRegisteringTheSameHarnessIdTwiceIsRefused() {
        val plugin = ThrowawayPlugin("throwaway-twice", listOf(ThrowawayHarness("zz-twice"), ThrowawayHarness("zz-twice")))

        val report = registry.discover(sourceOf(plugin))

        assertEquals(RefusalReason.DUPLICATE_HARNESS_ID, report.refused.single().reason)
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty())
    }

    @Test
    fun blankIdsAreRefused() {
        val blankPlugin = ThrowawayPlugin(" ", listOf(ThrowawayHarness("zz-blank-plugin")))
        val blankHarness = ThrowawayPlugin("throwaway-blank-harness", listOf(ThrowawayHarness("zz-ok"), ThrowawayHarness("")))

        val report = registry.discover(sourceOf(blankPlugin, blankHarness))

        assertEquals(listOf(RefusalReason.INVALID_ID, RefusalReason.INVALID_ID), report.refused.map { it.reason })
        assertEquals(0, blankPlugin.registerCalls.get())
        assertTrue(registry.harnessIds().isEmpty() && registry.pluginIds().isEmpty(), "zz-ok must not leak from the refused plugin")
    }

    @Test
    fun concurrentRegistrationsOfOneHarnessIdYieldExactlyOneWinner() {
        val contenders = 8
        val plugins = (0 until contenders).map { ThrowawayPlugin("throwaway-race-$it", listOf(ThrowawayHarness("zz-contended"))) }
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(contenders)
        try {
            val futures = plugins.map { p -> pool.submit<PluginOutcome> { go.await(); registry.register(p) } }
            go.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it is PluginOutcome.Accepted })
            assertEquals(contenders - 1, outcomes.count { it is PluginOutcome.Refused && it.reason == RefusalReason.DUPLICATE_HARNESS_ID })
            assertEquals(listOf("zz-contended"), registry.harnessIds())
            assertEquals(1, registry.pluginIds().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
