// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.superiorsoftllc.infantry.ui.net.Controller

/**
 * CI-UI-1: ONBOARD-2's guided setup opens over the shell on every start while no harness can answer, which is the case on a
 * CI machine with no agent CLI installed (a developer machine has one, so it never shows locally). A fixture that drives the
 * shell does what a person does: waits for the daemon's answer, then presses "Not now".
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.dismissSetupWizard(controller: Controller) {
    runCatching { waitUntil(timeoutMillis = 10_000) { controller.state.value.let { it.harnessInstall != null && it.secrets != null } } }
    waitForIdle()
    if (onAllNodesWithTag(SETUP_WIZARD_TAG_FOR_TESTS).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("setup-later").performClick()
        waitForIdle()
    }
}

private const val SETUP_WIZARD_TAG_FOR_TESTS = "setup-wizard"
