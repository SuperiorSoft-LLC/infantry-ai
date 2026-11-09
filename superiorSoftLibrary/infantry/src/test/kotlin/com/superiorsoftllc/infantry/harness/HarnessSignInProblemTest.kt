// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.harness.api.Harness as ApiHarness
import com.superiorsoftllc.infantry.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator as ApiTranslator

/**
 * RUNNER-CHECK-SIGNIN-1: API 2.18.0 [ApiHarness.signInProblem] reaches the registry, which the runner hello reads for `loggedIn`.
 * A harness that does not answer (an older plugin), answers blank, or throws reads as null (unknown), never as "not signed in".
 */
class HarnessSignInProblemTest {
    private val failures = mutableListOf<PluginFailure>()

    private fun registry(answer: () -> String?, override: Boolean = true): HarnessRegistry {
        val state = File.createTempFile("signin", "").also { it.delete(); it.mkdirs() }
        val plugin = object : ApiPlugin {
            override val pluginId = "Acme_SignIn"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(
                if (override) object : Base() { override fun signInProblem() = answer() } else object : Base() {},
            )
        }
        val registry = HarnessRegistry()
        val host = ModuleHarnessHost("Acme_SignIn", state, HarnessHostDeclaration(), { true }, HarnessHostServices(), {})
        assertIs<PluginOutcome.Accepted>(registry.register(ApiHarnessPluginBridge(plugin, host, onFailure = { failures += it })))
        return registry
    }

    private abstract class Base : ApiHarness {
        override val id = "signy"
        override val capabilities = emptySet<HarnessCapability>()
        override fun process(request: HarnessRequest) = HarnessProcess(listOf("echo"))
        override fun newTranslator() = object : ApiTranslator {
            override fun translate(line: String) = emptyList<ApiEvent>()
            override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
        }
    }

    @Test fun `a harness that is not signed in says why`() =
        assertEquals("Run signy login.", registry({ "Run signy login." }).signInProblem("signy"))

    @Test fun `the default and a blank answer are unknown`() {
        assertNull(registry({ null }, override = false).signInProblem("signy"))
        assertNull(registry({ "  " }).signInProblem("signy"))
    }

    @Test fun `a throw is attributed and reads as unknown`() {
        assertNull(registry({ error("boom") }).signInProblem("signy"))
        assertTrue(failures.any { it.plugin == "Acme_SignIn" })
    }

    @Test fun `an unknown harness id is unknown`() = assertNull(registry({ "x" }).signInProblem("nope"))
}
