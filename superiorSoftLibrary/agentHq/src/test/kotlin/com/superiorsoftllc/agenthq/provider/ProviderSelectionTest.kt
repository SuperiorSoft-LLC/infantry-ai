package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.harness.AgentProvider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.3.0 (story 9.0): which providers a daemon may spawn besides its default
 * (`AGENTHQ_PROVIDERS`), and how a session's model reaches a CLI command line. Pure functions only --
 * no CLI needs to be installed and the environment is never read for a value under test.
 */
class ProviderSelectionTest {

    /** H8b-3: opencode is a harness module; the default these tests pass is the stub (a core builder). */
    private val opencode: AgentProvider = StubProvider()

    // --- AGENTHQ_PROVIDERS ------------------------------------------------------------------------

    @Test
    fun unsetOrBlankMeansTheDefaultOnly() {
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag(null, opencode))
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag("", opencode))
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag(" , ,", opencode))
    }

    @Test
    fun entriesKeepTheirOrderAndTheDefaultIsNotListedTwice() {
        val ids = ProviderFactory.additionalFromFlag("claude, OpenCode ,stub", opencode).map { it.id }
        assertEquals(emptyList(), ids, "the default (stub) is always available, so it is dropped; claude and opencode are served by modules")
    }

    @Test
    fun aliasesCollapseToOneEntry() {
        val ids = ProviderFactory.additionalFromFlag("cc,claude,claude-code,scripted,stub", opencode).map { it.id }
        assertEquals(emptyList(), ids, "H7b-3: the default (stub) is dropped and claude is served by the Infantry_Claude module, so its names add no builder")
    }

    @Test
    fun claudeIsNoLongerABuiltInProvider_aDefaultNamingItIsRefusedByName() {
        assertTrue("claude" !in ProviderFactory.ids(), "core registers no claude provider; the Infantry_Claude module serves it")
        val failure = assertFailsWith<IllegalStateException> { ProviderFactory.fromFlag("claude") }
        assertTrue(failure.message!!.contains("claude"), failure.message)
    }

    @Test
    fun opencodeIsNoLongerABuiltInProvider_aDefaultNamingItIsRefusedByName() {
        assertTrue("opencode" !in ProviderFactory.ids(), "core registers no opencode provider; the Infantry_OpenCode module serves it")
        val failure = assertFailsWith<IllegalStateException> { ProviderFactory.fromFlag("opencode") }
        assertTrue(failure.message!!.contains("opencode"), failure.message)
        assertFailsWith<IllegalStateException> { ProviderFactory.fromFlag("oc") }
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag("oc,opencode", opencode), "an AGENTHQ_PROVIDERS entry naming it is satisfied by the module")
    }

    @Test
    fun anUnknownHarnessNameIsRefusedWithTheKnownIds() {
        val failure = assertFailsWith<IllegalStateException> { ProviderFactory.fromFlag("no-such-harness") }
        assertTrue(failure.message!!.contains("no-such-harness") && failure.message!!.contains("stub"), failure.message)
    }

    @Test
    fun anUnknownEntryFailsLoudlyAndNamesTheVariable() {
        val failure = assertFailsWith<IllegalStateException> {
            ProviderFactory.additionalFromFlag("claude,gemini", opencode)
        }
        assertTrue(failure.message!!.contains(ProviderFactory.PROVIDERS_ENV), failure.message)
        assertTrue(failure.message!!.contains("gemini"), failure.message)
    }

    // H8b-3: the opencode model-list parsing tests (plain-text list, blank/help lines, unparseable) moved to the Infantry_OpenCode plugin (OpenCodeMovedTest).
}
