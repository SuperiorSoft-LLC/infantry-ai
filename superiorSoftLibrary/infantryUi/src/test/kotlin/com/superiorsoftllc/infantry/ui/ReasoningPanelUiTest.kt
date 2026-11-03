package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import kotlin.test.Test

/** The reasoning button opens a panel holding every reasoning line; Close hides it again. */
@OptIn(ExperimentalTestApi::class)
class ReasoningPanelUiTest {
    @Test
    fun buttonOpensPanelWithAllReasoningAndCloseHidesIt() {
        val lines = listOf(OutputLine(LineKind.REASONING, "weighing the options", 0), OutputLine(LineKind.REASONING, "picking the second", 0))
        runDesktopComposeUiTest(900, 600) {
            setContent {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) { ReasoningSlot(lines, open) { open = it } }
            }
            onNodeWithTag(REASONING_BUTTON_TAG).assertExists()
            onNodeWithTag(REASONING_PANEL_TAG).assertDoesNotExist()
            onNodeWithTag(REASONING_BUTTON_TAG).performClick()
            onNodeWithText("weighing the options", substring = true).assertExists()
            onNodeWithText("picking the second", substring = true).assertExists()
            onNodeWithTag(REASONING_PANEL_CLOSE_TAG).performClick()
            onNodeWithTag(REASONING_PANEL_TAG).assertDoesNotExist()
        }
    }
}
