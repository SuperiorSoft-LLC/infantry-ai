// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.protocol.CliLocation
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.protocol.SecretState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FIRST-RUN-HARNESS-CHECKLIST-1: Configuration > Agents lists every harness the daemon knows, installed (green, with version) or
 * not installed (red, with Install or a how-to), keeps the path controls, and shows the auth state of installed ones only.
 */
@OptIn(ExperimentalTestApi::class)
class HarnessChecklistUiTest {
    private val report = HarnessInstallReport(
        listOf(
            HarnessInstallStatus("claude", "Claude Code", "@anthropic-ai/claude-code", "installed", "2.1.3", "/x/claude"),
            HarnessInstallStatus("pi", "Pi", "pi-pkg", "missing"),
        ),
        "/p", "/npm",
    )
    private val locations = listOf(
        CliLocation("claude", "Claude Code", path = "/x/claude", source = "path"),
        CliLocation("pi", "Pi"),
        CliLocation("acme", "Acme"),
        CliLocation("git", "git", path = "/usr/bin/git", source = "path"),
    )
    private val providers = listOf(ProviderInfo("claude"), ProviderInfo("pi", available = false), ProviderInfo("acme", available = false, unavailableReason = "acme not found"))
    private val secrets = SecretState(
        "dpapi",
        listOf(
            SecretRow("harness.claude.key", "Claude Code API key", authModes = listOf("api-key", "subscription-login"), authMode = "subscription-login", signedIn = "true"),
            SecretRow("harness.pi.key", "Pi API key"),
        ),
    )

    @Test fun `every harness is listed green or red, path controls stay, auth only for the installed ones`() = runDesktopComposeUiTest(1100, 1400) {
        val installs = mutableListOf<List<String>>()
        val edited = mutableListOf<String>()
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {},
                cliLocations = locations, onCliLocation = { _, _ -> },
                harnessInstall = report, onHarnessCheck = {}, onHarnessInstall = { ids, _ -> installs += ids },
                secrets = secrets, providers = providers,
                harnessAuthEditor = { row -> edited += row.name; Text("editor for ${row.name}") },
                onOpenChecklist = {},
                initialTab = ConfigTab.AGENTS,
            )
        }
        onNodeWithTag(HARNESS_STATE_TAG_PREFIX + "claude").assertTextEquals("Installed 2.1.3")
        onNodeWithTag(HARNESS_STATE_TAG_PREFIX + "pi").assertTextEquals("Not installed")
        onNodeWithTag(HARNESS_STATE_TAG_PREFIX + "acme").assertTextEquals("Not installed")
        onNodeWithTag(HARNESS_HOWTO_TAG_PREFIX + "acme").assertExists()
        onNodeWithTag(OPEN_CHECKLIST_TAG).assertExists()
        // The path controls are kept on every card, and git (not an agent tool) keeps its own row.
        onNodeWithTag(CLI_FIELD_TAG_PREFIX + "claude").assertExists()
        onNodeWithTag(CLI_FIELD_TAG_PREFIX + "git").assertExists()
        // Auth: claude signed in; pi is not installed so it is never asked for its key.
        onNodeWithTag(HARNESS_AUTH_TAG_PREFIX + "harness.claude.key").assertTextEquals("Signed in")
        assertEquals(0, onAllNodesWithTag(HARNESS_AUTH_TAG_PREFIX + "harness.pi.key").fetchSemanticsNodes().size)
        onNodeWithTag(HARNESS_AUTH_SETUP_TAG_PREFIX + "harness.claude.key").performClick()
        waitForIdle()
        assertEquals("harness.claude.key", edited.lastOrNull())
        onNodeWithTag(HARNESS_INSTALL_TAG_PREFIX + "pi").performClick()
        assertEquals(listOf(listOf("pi")), installs)
    }
}
