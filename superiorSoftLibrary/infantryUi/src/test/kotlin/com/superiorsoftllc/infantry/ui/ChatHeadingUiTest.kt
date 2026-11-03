package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import kotlin.test.Test

/** CHATFMT-H: an assistant reply heading renders as a styled text block, never a raw '#'. */
@OptIn(ExperimentalTestApi::class)
class ChatHeadingUiTest {
    @Test
    fun headingRendersWithoutTheHash() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                ChatLine(OutputLine(LineKind.TEXT, "# Demo reply", 1), "agent-1", "Agent")
            }
            onNodeWithText("Demo reply").assertExists()
            onNodeWithText("# Demo reply").assertDoesNotExist()
        }
    }
}
