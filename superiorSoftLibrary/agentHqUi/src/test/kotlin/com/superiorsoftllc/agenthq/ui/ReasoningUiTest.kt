package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import kotlin.test.Test

/** THINK-2: the model's reasoning shows as one "Thinking" block, collapsed until it is clicked. */
@OptIn(ExperimentalTestApi::class)
class ReasoningUiTest {
    @Test
    fun reasoningIsCollapsedByDefaultAndTheClickExpandsIt() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                Column {
                    ReasoningBlock(
                        listOf(
                            OutputLine(LineKind.REASONING, "weighing the options", 0),
                            OutputLine(LineKind.REASONING, "picking the second", 0),
                        ),
                    )
                }
            }
            // Collapsed by default: the header names the block and counts its lines; the text is hidden.
            onNodeWithText("Thinking (2)").assertExists()
            onNodeWithText("weighing the options", substring = true).assertDoesNotExist()
            // The click expands it and shows every reasoning line, plain.
            onNodeWithTag(REASONING_HEADER_TAG).performClick()
            onNodeWithText("weighing the options", substring = true).assertExists()
            onNodeWithText("picking the second", substring = true).assertExists()
        }
    }
}
