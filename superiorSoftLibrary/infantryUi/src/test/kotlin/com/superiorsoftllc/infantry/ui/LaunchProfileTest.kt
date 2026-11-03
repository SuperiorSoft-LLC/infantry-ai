// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PKG-1: Gradle `run` stays the dev window; only the installed launcher (or the force flag) picks the installed profile. */
class LaunchProfileTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test fun jpackageAppPathSelectsTheInstalledProfile() {
        assertTrue(LaunchProfile.isInstalled(props(LaunchProfile.APP_PATH_PROPERTY to "C:/Program Files/Infantry/Infantry.exe")))
    }

    @Test fun noAppPathIsDev() {
        assertFalse(LaunchProfile.isInstalled(props()))
        assertFalse(LaunchProfile.isInstalled(props(LaunchProfile.APP_PATH_PROPERTY to " ")))
        assertFalse(LaunchProfile.isInstalled(props(LaunchProfile.FORCE_PROPERTY to "false")))
    }

    @Test fun theForceFlagSelectsTheInstalledProfile() {
        assertTrue(LaunchProfile.isInstalled(props(LaunchProfile.FORCE_PROPERTY to "true")))
    }
}
