package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.HarnessProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.EffortLevels
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
    fun theWireNamesOfTheSupportLevels() {
        assertEquals(listOf("supported", "best_effort", "unsupported"), EffortSupport.entries.map { it.wire })
        assertFalse(EffortCapability.UNSUPPORTED.applies)
    }
}
