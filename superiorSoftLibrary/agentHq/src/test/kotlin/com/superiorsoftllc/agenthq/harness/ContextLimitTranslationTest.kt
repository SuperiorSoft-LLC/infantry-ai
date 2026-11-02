package com.superiorsoftllc.agenthq.harness

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
    fun opencode_reportsUnsupported_andSaysWhy() {
        val cap = OpenCodeProvider(modelLister = { emptyList() }).contextLimit()
        assertEquals(ContextLimitSupport.UNSUPPORTED, cap.support)
        assertFalse(cap.applies)
        assertTrue(cap.how.isNotBlank())
    }

    @Test
    fun theWireNamesOfTheSupportLevels() {
        assertEquals(listOf("supported", "best_effort", "unsupported"), ContextLimitSupport.entries.map { it.wire })
        assertFalse(ContextLimitCapability.UNSUPPORTED.applies)
    }
}
