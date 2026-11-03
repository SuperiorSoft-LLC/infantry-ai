// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.shell.preferencesReads
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlin.test.assertEquals

/**
 * SHOT-PREFS (3e4eb7ed): the tab-shot render path must never read java.util.prefs (the registry on Windows, which the
 * live app writes), or a shot depends on what the owner last toggled. With TabShotsMain's pin installed, the whole App
 * composes and the store is never opened.
 */
@OptIn(ExperimentalTestApi::class)
class ShotPrefsGuardUiTest {
    @Test fun `the shot render path never reads java util prefs`() {
        val before = uiPrefsOverride
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            pinShotUiPrefs()
            val reads = preferencesReads.get()
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(Controller(scope), "ws://127.0.0.1:1/ws", null) }
                waitForIdle()
            }
            assertEquals(reads, preferencesReads.get(), "a shot render opened java.util.prefs: pin the pref in SHOT_UI_PREFS instead")
            assertEquals("off", uiPrefsOverride?.get(BLUR_PREF_KEY), "the baselines are rendered with blurBehindDialogs=off")
        } finally {
            scope.cancel()
            uiPrefsOverride = before
        }
    }
}
