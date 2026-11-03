// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.HarnessProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
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
    fun theWireNamesOfTheSupportLevels() {
        assertEquals(listOf("supported", "best_effort", "unsupported"), ContextLimitSupport.entries.map { it.wire })
        assertFalse(ContextLimitCapability.UNSUPPORTED.applies)
    }
}
