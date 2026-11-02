package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.provider.HarnessProvider
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.EffortLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EFFORT-1 (docs/EFFORT.md): each built-in's translation of the abstract effort level, as goldens, and the capability each
 * reports. The provider layer hands a level only to a harness that applies one.
 */
class EffortTranslationTest {
    private object NoRunner : ProcessRunner {
        override val id = "none"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("not run in this test")
    }

    @Test
    fun claude_theLevelIsTheEffortFlag_andNoLevelAddsNoFlag() {
        val withEffort = ClaudeCommand.spec(model = "opus", executable = "claude.exe", effort = "high").command
        val without = ClaudeCommand.spec(model = "opus", executable = "claude.exe").command
        val at = withEffort.indexOf("--effort")
        assertTrue(at >= 0 && withEffort[at + 1] == "high", "argv: $withEffort")
        assertFalse("--effort" in without, "default behaviour unchanged: $without")
    }

    @Test
    fun everyLevelOfTheDataListIsAccepted_byBothBuiltIns() {
        for (level in EffortLevels.ids) {
            assertTrue(level in ClaudeCommand.spec(executable = "claude.exe", effort = level).command)
        }
    }

    @Test
    fun theBuiltInsReportSupported_andOpencodeUnsupportedWithAReason() {
        assertEquals(EffortSupport.SUPPORTED, ClaudeHarness(NoRunner).effort.support)
        assertEquals(EffortSupport.SUPPORTED, com.superiorsoftllc.agenthq.claude.ClaudeEffort.CAPABILITY.support, "the session provider's capability")
        val opencode = OpenCodeProvider(modelLister = { emptyList() }).effort()
        assertEquals(EffortSupport.UNSUPPORTED, opencode.support)
        assertFalse(opencode.applies)
        assertTrue(opencode.how.isNotBlank())
    }

    @Test
    fun aSupportedHarnessIsHandedTheLevel_andAnUnsupportedOneIsNot() {
        var seen: HarnessRequest? = null
        HarnessProvider(ClaudeHarness(NoRunner, { r -> seen = r; ClaudeHarness.defaultSpec(r) })).spec(RunRequest("p", effort = "max"))
        assertEquals("max", seen?.effort)

        // A harness with no EFFORT_SELECTION capability and no declared mapping.
        var plainSeen: HarnessRequest? = null
        val plain = object : CliHarness(NoRunner, { r -> plainSeen = r; ProcessSpec(listOf("x")) }, { error("unused") }, cliName = "plain") {
            override val id = "plain"
            override val capabilities = emptySet<HarnessCapability>()
        }
        val provider = HarnessProvider(plain)
        assertEquals(EffortSupport.UNSUPPORTED, plain.effort.support)
        provider.spec(RunRequest("p", effort = "max"))
        assertNull(plainSeen!!.effort, "recorded on the session, not passed to a harness that cannot apply it")
    }

    @Test
    fun theWireNamesOfTheSupportLevels() {
        assertEquals(listOf("supported", "best_effort", "unsupported"), EffortSupport.entries.map { it.wire })
        assertFalse(EffortCapability.UNSUPPORTED.applies)
    }
}
