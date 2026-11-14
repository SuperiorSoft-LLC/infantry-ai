// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.HarnessInstallSetting
import com.superiorsoftllc.infantry.ui.model.SetupWizard
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ONBOARD-2: when the guided setup opens, where it starts, and what the first agent runs. */
class SetupWizardTest {
    private fun h(id: String, installed: Boolean) =
        HarnessInstallStatus(id = id, label = id, state = if (installed) "installed" else "missing", path = if (installed) "/x/$id" else null)

    private fun report(vararg hs: HarnessInstallStatus, npm: String? = "/usr/bin/npm") = HarnessInstallReport(hs.toList(), "/p", npm)

    private fun key(id: String, set: Boolean = false, signedIn: String = "unknown") = SecretRow("harness.$id.key", "$id API key", set = set, signedIn = signedIn)

    @Test
    fun opensOnlyWhenNothingCanAnswerAndNeverOnAGuess() {
        assertNull(SetupWizard.needed(null, emptyList(), emptyList()))
        assertNull(SetupWizard.needed(report(h("claude", false)), null, emptyList()))
        assertEquals(true, SetupWizard.needed(report(h("claude", false), h("opencode", false)), emptyList(), emptyList()))
        // Installed but no key and no login: still nothing to talk to.
        assertEquals(true, SetupWizard.needed(report(h("claude", true)), listOf(key("claude")), emptyList()))
        assertEquals(false, SetupWizard.needed(report(h("claude", true)), listOf(key("claude", set = true)), emptyList()))
        assertEquals(false, SetupWizard.needed(report(h("claude", true)), listOf(key("claude", signedIn = "true")), emptyList()))
        // ONBOARD-1's in-app login finished before the probe caught up.
        assertEquals(false, SetupWizard.needed(report(h("claude", true)), listOf(key("claude").copy(loginState = "done")), emptyList()))
        // A harness that declares no key, or offers free models, can answer once installed.
        assertEquals(false, SetupWizard.needed(report(h("pi", true)), emptyList(), emptyList()))
        val free = listOf(ProviderInfo("opencode", models = listOf("opencode/big-pickle", "opencode/grok-code-free")))
        assertEquals(false, SetupWizard.needed(report(h("opencode", true)), listOf(key("opencode")), free))
    }

    @Test
    fun nodeComesFirstOnlyWhileNpmIsMissingAndNothingIsInstalled() {
        assertEquals(SetupWizard.Step.NODE, SetupWizard.firstStep(report(h("claude", false), npm = null)))
        assertEquals(SetupWizard.Step.HARNESS, SetupWizard.firstStep(report(h("claude", false))))
        assertEquals(SetupWizard.Step.SIGN_IN, SetupWizard.firstStep(report(h("claude", true), npm = null)))
        assertEquals("opencode", SetupWizard.defaultPick(report(h("claude", false), h("opencode", true))))
        assertEquals("claude", SetupWizard.defaultPick(report(h("claude", false), h("opencode", false))))
    }

    @Test
    fun theFirstAgentUsesAFreeModelWhenNoKeyIsSet() {
        val providers = listOf(ProviderInfo("opencode", defaultModel = "anthropic/sonnet", models = listOf("anthropic/sonnet", "opencode/grok-code-free")))
        assertEquals("opencode/grok-code-free", SetupWizard.firstModel("opencode", listOf(key("opencode")), providers))
        assertEquals("anthropic/sonnet", SetupWizard.firstModel("opencode", listOf(key("opencode", set = true)), providers))
        assertNull(SetupWizard.firstModel("claude", emptyList(), providers))
    }

    @Test
    fun anInstallOutcomeIsWordedFromItsLogLine() {
        val log = listOf("[claude] npm install x", "node: installed", "claude: failed - npm exited 1: ETIMEDOUT")
        assertEquals(HarnessInstallSetting.Outcome(false, "Install failed: npm exited 1: ETIMEDOUT"), HarnessInstallSetting.outcomeFor("claude", log))
        assertEquals(HarnessInstallSetting.Outcome(true, "Installed"), HarnessInstallSetting.outcomeFor("node", log))
        assertEquals(HarnessInstallSetting.Outcome(true, "Installed 2.1.0"), HarnessInstallSetting.outcomeFor("pi", listOf("pi: installed 2.1.0")))
        assertNull(HarnessInstallSetting.outcomeFor("opencode", log))
        assertEquals("harness.install.node", HarnessInstallSetting.taskId(HarnessInstallSetting.NODE_ID))
    }

    @Test
    fun theNodeNoteShowsOnlyWithoutNpm() {
        assertNull(HarnessInstallSetting.nodeNote(report(h("claude", false))))
        assertTrue(HarnessInstallSetting.nodeNote(report(h("claude", false), npm = null))!!.contains("Node.js"))
        assertEquals("go", HarnessInstallSetting.nodeNote(HarnessInstallReport(emptyList(), "/p", null, "go")))
        assertFalse(SetupWizard.installed(h("claude", false)))
    }
}
