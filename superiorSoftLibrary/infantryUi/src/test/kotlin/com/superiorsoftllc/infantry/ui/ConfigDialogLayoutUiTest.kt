// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.UiFlags
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ticket cb1ceb93: the configuration modal is wider, has a cog in its header, and shows when its body scrolls. */
@OptIn(ExperimentalTestApi::class)
class ConfigDialogLayoutUiTest {
    @Test fun `on a wide window the dialog is wider than the Material default, up to its cap`() = runDesktopComposeUiTest(1600, 1000) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
        val w = onNodeWithTag(CONFIG_DIALOG_TAG).getBoundsInRoot().let { it.right - it.left }
        assertTrue(w > 800.dp, "wider than the 560dp default, got $w")
        assertTrue(w <= CONFIG_DIALOG_MAX_WIDTH, "capped at $CONFIG_DIALOG_MAX_WIDTH, got $w")
    }

    @Test fun `on a narrow window the dialog fits inside it`() = runDesktopComposeUiTest(480, 800) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
        val b = onNodeWithTag(CONFIG_DIALOG_TAG).getBoundsInRoot()
        assertTrue(b.left >= 0.dp && b.right <= 480.dp, "no horizontal cutoff, got $b")
    }

    @Test fun `the header carries the drawn cog`() = runDesktopComposeUiTest(1000, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
        onNodeWithTag(CONFIG_HEADER_ICON_TAG).assertExists()
    }

    @Test fun `a body that overflows shows the cue, and it goes once scrolled to the end`() = runDesktopComposeUiTest(1600, 1000) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, modules = SampleModules.factory(), initialTab = ConfigTab.PLUGINS) }
        waitForIdle()
        onNodeWithTag(SCROLL_CUE_END_TAG).assertExists()
        onNodeWithTag(SCROLL_CUE_START_TAG).assertDoesNotExist()
        onNodeWithTag(CONFIG_TAB_BODY_TAG).performMouseInput { moveTo(center); scroll(200f) }
        waitForIdle()
        onNodeWithTag(SCROLL_CUE_END_TAG).assertDoesNotExist()
        onNodeWithTag(SCROLL_CUE_START_TAG).assertExists()
    }

    @Test fun `a body that fits shows no cue`() = runDesktopComposeUiTest(1600, 1000) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, logs = LogsFolder("C:/Infantry/logs") { true }, initialTab = ConfigTab.ABOUT) }
        waitForIdle()
        onNodeWithTag(SCROLL_CUE_END_TAG).assertDoesNotExist()
        onNodeWithTag(SCROLL_CUE_START_TAG).assertDoesNotExist()
    }
}
