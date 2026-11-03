// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.ThemeAccent
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.model.UiFlags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** THEME-1: Configuration > Theme picks an accent and dark / light, applies it live through InfantryTheme, and resets. */
@OptIn(ExperimentalTestApi::class)
class ThemeSettingsUiTest {
    @AfterTest fun restore() { Palette.theme = ThemeSettings() }

    @Test fun `picking an accent and light mode re-themes live, and reset returns to red on dark`() {
        var primary = Color.Unspecified
        var background = Color.Unspecified
        runDesktopComposeUiTest(900, 900) {
            setContent {
                InfantryTheme {
                    primary = MaterialTheme.colorScheme.primary
                    background = MaterialTheme.colorScheme.background
                    ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, theme = Palette.theme, onTheme = { Palette.theme = it })
                }
            }
            assertEquals(Color(ThemeAccent.RED.argb), primary)
            onNodeWithTag(THEME_RESET_TAG).performScrollTo().assertIsNotEnabled() // the default needs no reset
            onNodeWithTag(THEME_ACCENT_TAG_PREFIX + ThemeAccent.INFANTRY.name).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(THEME_ACCENT_TAG_PREFIX + ThemeAccent.INFANTRY.name).assertIsSelected()
            assertEquals(Color(ThemeAccent.INFANTRY.argb), primary)
            assertEquals(Color(ThemeAccent.INFANTRY.argb), Palette.Accent)
            val darkBg = background
            onNodeWithTag(THEME_DARK_SWITCH_TAG).performScrollTo().performClick()
            waitForIdle()
            assertFalse(Palette.theme.dark)
            assert(background != darkBg) { "light mode must change the background" }
            onNodeWithTag(THEME_RESET_TAG).performScrollTo().assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(ThemeSettings(), Palette.theme)
            assertEquals(Color(ThemeAccent.RED.argb), primary)
            assertEquals(darkBg, background)
        }
    }

    @Test fun `no theme callback means no theme section`() {
        runDesktopComposeUiTest(900, 900) {
            setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
            onNodeWithTag(THEME_RESET_TAG).assertDoesNotExist()
        }
    }
}
