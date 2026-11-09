// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.CliHarness
import com.superiorsoftllc.infantry.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.infantry.harness.HarnessCapability
import com.superiorsoftllc.infantry.harness.HarnessEvent
import com.superiorsoftllc.infantry.harness.HarnessPlugin
import com.superiorsoftllc.infantry.harness.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.HarnessTranslator
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec

/**
 * MODEL-CATALOG-1: harnesses for the registry ONLY (ids of the test's choosing), so the `models` frame asks the
 * catalog of every one of them without a real CLI being installed. Nothing here is ever started: a harness's child
 * is only spawned by a run, and these tests only list models.
 */
internal object StubCatalogHarness {

    private class QuietTranslator : HarnessTranslator {
        override fun translate(line: String): List<HarnessEvent> = emptyList()
        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = HarnessEvent.Completion()
    }

    private class StubCliHarness(override val id: String) : CliHarness(
        PipedProcessRunner(),
        { ProcessSpec(listOf(System.getProperty("java.home") + "/bin/java", "-version")) },
        { QuietTranslator() },
        cliName = id,
    ) {
        override val capabilities = setOf(HarnessCapability.MODEL_SELECTION)
    }

    /** One plugin registering a harness per id. */
    fun plugin(vararg ids: String): HarnessPlugin = object : HarnessPlugin {
        override val pluginId = "stub-catalog-harness"
        override val contractVersion = HARNESS_CONTRACT_VERSION
        override fun register(registrar: HarnessRegistrar) = ids.forEach { registrar.register(StubCliHarness(it)) }
    }
}
