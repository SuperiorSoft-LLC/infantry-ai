package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.claude.ClaudeContextLimit
import com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.provider.HarnessProvider
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): each built-in's translation of the abstract limit, as goldens, and the
 * capability each reports. The provider layer hands a limit only to a harness that applies one.
 */
class ContextLimitTranslationTest {
    private object NoRunner : ProcessRunner {
        override val id = "none"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("not run in this test")
    }

    @Test
    fun claude_theLimitIsTheAutoCompactWindowEnv_andNoLimitAddsNoEnv() {
        assertEquals(mapOf("CLAUDE_CODE_AUTO_COMPACT_WINDOW" to "200000"), ClaudeContextLimit.env(200_000))
        assertEquals(emptyMap(), ClaudeContextLimit.env(null))

        val limited = ClaudeCommand.spec(model = "opus", executable = "claude.exe", contextLimit = 200_000)
        val plain = ClaudeCommand.spec(model = "opus", executable = "claude.exe")
        assertEquals("200000", limited.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
        assertFalse("CLAUDE_CODE_AUTO_COMPACT_WINDOW" in plain.env, "default behaviour unchanged: ${plain.env}")
        assertEquals(plain.command, limited.command, "the limit changes the env, never the argv")
    }

    @Test
    fun claude_aLimitChangeChangesThePersistentSessionFingerprint_soItRestartsAndResumes() {
        val adapter = ClaudeSessionAdapter(executable = { "claude.exe" })
        val none = adapter.fingerprint(RunRequest("p"), emptyMap())
        val small = adapter.fingerprint(RunRequest("p", contextLimit = 200_000), emptyMap())
        val big = adapter.fingerprint(RunRequest("p", contextLimit = 1_000_000), emptyMap())
        assertNotEquals(none, small)
        assertNotEquals(small, big)
        assertEquals(none, adapter.fingerprint(RunRequest("p"), emptyMap()), "no limit: the fingerprint is as before")
    }

    @Test
    fun claude_reportsSupported_onBothPaths() {
        assertEquals(ContextLimitSupport.SUPPORTED, ClaudeHarness(NoRunner).contextLimit.support)
        assertEquals(ContextLimitSupport.SUPPORTED, ClaudeContextLimit.CAPABILITY.support, "the session provider's capability")
    }

    @Test
    fun claudeHarness_handsTheLimitToItsSpec() {
        var seen: HarnessRequest? = null
        val provider = HarnessProvider(ClaudeHarness(NoRunner, { r -> seen = r; ClaudeHarness.defaultSpec(r) }))
        provider.spec(RunRequest("p", contextLimit = 300_000))
        assertEquals(300_000, seen?.contextLimit)
        assertEquals("300000", ClaudeHarness.defaultSpec(HarnessRequest(prompt = "p", harnessId = "claude", project = "", contextLimit = 300_000)).env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    }

    @Test
    fun opencode_reportsUnsupported_andSaysWhy() {
        val cap = OpenCodeProvider(modelLister = { emptyList() }).contextLimit()
        assertEquals(ContextLimitSupport.UNSUPPORTED, cap.support)
        assertFalse(cap.applies)
        assertTrue(cap.how.isNotBlank())
    }

    @Test
    fun anUnsupportedHarnessIsNeverHandedTheLimit() {
        var seen: HarnessRequest? = null
        // One-shot pi (no session adapter) declares no mapping.
        val provider = HarnessProvider(PiHarness(NoRunner, { r -> seen = r; ProcessSpec(listOf("pi")) }))
        assertEquals(ContextLimitSupport.UNSUPPORTED, provider.contextLimit().support)
        provider.spec(RunRequest("p", contextLimit = 300_000))
        assertNull(seen!!.contextLimit, "recorded on the session, not passed to a harness that cannot apply it")
    }

    @Test
    fun theWireNamesOfTheSupportLevels() {
        assertEquals(listOf("supported", "best_effort", "unsupported"), ContextLimitSupport.entries.map { it.wire })
        assertFalse(ContextLimitCapability.UNSUPPORTED.applies)
    }
}
