// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/** SPLASH-STUCK-1: a daemon start that throws shows the error, not the endless splash. */
@OptIn(ExperimentalTestApi::class)
class DaemonStartGateUiTest {
    @Test fun aThrowingStartReplacesTheSplashWithTheError() = runComposeUiTest {
        setContent {
            DaemonStartGate(null, { error("Unknown INFANTRY_PROVIDER='claude'") }, { null }, "C:/logs", {}) { }
        }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Unknown INFANTRY_PROVIDER", substring = true).assertExists() }.isSuccess }
        onNodeWithText("Retry").assertExists()
        onNodeWithText("Quit").assertExists()
        onNodeWithTag(SPLASH_TAG).assertDoesNotExist()
    }
}
