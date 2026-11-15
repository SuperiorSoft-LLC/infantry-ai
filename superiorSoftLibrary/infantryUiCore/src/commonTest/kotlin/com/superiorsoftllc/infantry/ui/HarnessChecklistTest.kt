// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.HarnessChecklist
import com.superiorsoftllc.infantry.ui.protocol.CliLocation
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FIRST-RUN-HARNESS-CHECKLIST-1: the agent tools list both Configuration > Agents and the first-run checklist draw. */
class HarnessChecklistTest {
    private fun h(id: String, label: String, installed: Boolean, version: String? = "1.0") = HarnessInstallStatus(
        id = id, label = label, npmPackage = "pkg-$id", state = if (installed) "installed" else "missing",
        installedVersion = if (installed) version else null, path = if (installed) "/x/$id" else null,
    )

    private val report = HarnessInstallReport(listOf(h("claude", "Claude Code", true, "2.1.3"), h("opencode", "OpenCode", true), h("pi", "Pi", false)), "/p", "/npm")

    private val secrets = listOf(
        SecretRow("harness.claude.key", "Claude Code API key", authModes = listOf("api-key", "subscription-login"), authMode = "subscription-login", signedIn = "true"),
        SecretRow("harness.claude.subscription.key", "Claude subscription token", forMode = "subscription-login"),
        SecretRow("harness.opencode.key", "OpenCode API key", set = true),
        SecretRow("harness.opencode.commandcode.key", "Command Code API key"),
        SecretRow("harness.pi.key", "Pi API key"),
    )

    @Test
    fun listsEveryHarnessFromTheReportAndTheProvidersWithInstalledState() {
        val providers = listOf(ProviderInfo("claude"), ProviderInfo("acme", available = false, unavailableReason = "acme not found on PATH"))
        val locations = listOf(CliLocation("acme", "Acme CLI"), CliLocation("git", "git", path = "/usr/bin/git"))
        val items = HarnessChecklist.items(report, providers, locations, secrets)
        assertEquals(listOf("claude", "opencode", "pi", "acme"), items.map { it.id })
        val claude = items[0]
        assertTrue(claude.installed)
        assertEquals("Installed 2.1.3", claude.status)
        val acme = items[3]
        assertFalse(acme.installed)
        assertEquals("Not installed", acme.status)
        assertEquals("Acme CLI", acme.label)
        // No built-in installer for it: the exact how-to instead.
        assertTrue(acme.howTo!!.startsWith("acme not found on PATH."))
        assertNull(items[2].howTo, "the installer can install pi: its Install button, not a how-to")
    }

    @Test
    fun authStateOnlyForInstalledHarnessesAndApiKeyCredentialsOnly() {
        val items = HarnessChecklist.items(report, emptyList(), null, secrets).associateBy { it.id }
        val claude = items.getValue("claude").credentials
        assertEquals(listOf("harness.claude.key"), claude.map { it.row.name }, "the subscription-mode token is not an API key")
        assertEquals(HarnessChecklist.Auth.SIGNED_IN, claude.single().auth)
        assertEquals("Claude Code sign-in", claude.single().label)
        val opencode = items.getValue("opencode").credentials
        assertEquals(listOf("harness.opencode.key" to HarnessChecklist.Auth.KEY_SET, "harness.opencode.commandcode.key" to HarnessChecklist.Auth.NEEDS_SETUP), opencode.map { it.row.name to it.auth })
        assertTrue(opencode[0].primary && !opencode[1].primary)
        // Not installed: never asked for credentials.
        assertTrue(items.getValue("pi").credentials.isEmpty())
    }

    @Test
    fun authStates() {
        val base = SecretRow("harness.claude.key", "Claude Code API key", authModes = listOf("api-key", "subscription-login"))
        assertEquals(HarnessChecklist.Auth.NEEDS_SETUP, HarnessChecklist.auth(base))
        assertEquals(HarnessChecklist.Auth.KEY_SET, HarnessChecklist.auth(base.copy(set = true, authMode = "api-key", signedIn = "true")))
        assertEquals(HarnessChecklist.Auth.SIGNED_IN, HarnessChecklist.auth(base.copy(loginState = "done")))
        assertEquals(HarnessChecklist.Auth.SHARED, HarnessChecklist.auth(base.copy(usesSecret = "anthropic")))
        assertEquals(HarnessChecklist.Auth.KEY_SET, HarnessChecklist.auth(base.copy(overriddenByEnv = true)))
    }

    @Test
    fun summarySaysWhatIsInstalledAndWhatIsMissing() {
        val items = HarnessChecklist.items(report, emptyList(), null, secrets)
        assertEquals("You have Claude Code installed and OpenCode installed. Not installed: Pi.", HarnessChecklist.summary(items))
        val none = HarnessChecklist.items(HarnessInstallReport(listOf(h("pi", "Pi", false)), "/p"), emptyList(), null, null)
        assertEquals("No agent tool is installed yet. Not installed: Pi.", HarnessChecklist.summary(none))
    }
}
