// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/** SLIDE-TAB-1: open on hover, close on exit after the grace delay, tap-outside close, Esc, keyboard toggle. */
@OptIn(ExperimentalTestApi::class)
class HoverRevealTabUiTest {
    private fun androidx.compose.ui.test.ComposeUiTest.host() = setContent {
        Box(Modifier.size(400.dp)) {
            Box(Modifier.testTag("outside").size(100.dp))
            HoverRevealTab(AhqIcons.Activity, "Session details", Modifier.testTag("host"), graceMs = 200) { Text("chips", Modifier.testTag("chips")) }
        }
    }

    @Test fun opensOnHoverAndClosesAfterTheGraceDelay() = runComposeUiTest {
        host()
        onNodeWithTag("chips").assertDoesNotExist()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performMouseInput { moveTo(center) }
        waitForIdle(); mainClock.advanceTimeBy(400)
        onNodeWithTag("chips").assertIsDisplayed()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performMouseInput { moveTo(center.copy(x = -50f, y = -50f)) }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("chips").assertIsDisplayed() // still inside the grace delay
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("chips").assertDoesNotExist()
    }

    @Test fun touchTapOpensAndStaysOpenUntilTouchOutside() = runComposeUiTest {
        host()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performTouchInput { click(center) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("chips").assertIsDisplayed()
        mainClock.advanceTimeBy(2000)
        onNodeWithTag("chips").assertIsDisplayed()
        onNodeWithTag("outside").performTouchInput { click(center) }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("chips").assertDoesNotExist()
    }

    @Test fun tappingTheTabAgainCloses() = runComposeUiTest {
        host()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performTouchInput { click(center) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performTouchInput { click(center) }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("chips").assertDoesNotExist()
    }

    @Test fun enterTogglesAndEscapeCloses() = runComposeUiTest {
        host()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("chips").assertIsDisplayed()
        onNodeWithTag(HOVER_REVEAL_TAB_TAG, useUnmergedTree = true).performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("chips").assertDoesNotExist()
    }
}
