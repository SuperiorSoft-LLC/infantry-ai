package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import kotlin.test.Test

/** AVATAR-2: every chat message carries its speaker's avatar, drawn the way the window draws it. */
@OptIn(ExperimentalTestApi::class)
class ChatAvatarUiTest {
    @Test
    fun twoMessagesFromDifferentSpeakersRenderTwoAvatarsWithTheirInitials() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                Column {
                    ChatLine(OutputLine(LineKind.TEXT, "hello from the agent", 0), agentId = "agent-1", agentName = "Scrum Master", agentRole = "qa")
                    ChatLine(OutputLine(LineKind.USER, "hi there", 0), agentId = "agent-1", agentName = "Scrum Master", agentRole = "qa")
                }
            }
            // One avatar per message: the agent's role portrait (M2-1: its role, never its name "SM")
            // and the human's own portrait (USER-PORTRAIT: the bundled default, not a "Y" placeholder).
            onAllNodesWithTag(AVATAR_TAG).assertCountEquals(2)
            onNodeWithContentDescription("qa avatar").assertExists()
            onNodeWithText("SM").assertDoesNotExist()
            onNodeWithContentDescription("your portrait").assertExists()
            onAllNodesWithTag(CARD_COMMS_ICON_TAG).assertCountEquals(2)
        }
    }

    @Test
    fun ownerMessageHasNoPromptPrefixAndKeepsTypedGreaterThan() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                Column {
                    ChatLine(OutputLine(LineKind.USER, "hi there", 0), agentId = "a", agentName = "A")
                    ChatLine(OutputLine(LineKind.USER, "> quoted", 0), agentId = "a", agentName = "A")
                }
            }
            onNodeWithText("hi there").assertExists()
            onNodeWithText("> hi there", substring = true).assertDoesNotExist()
            onNodeWithText("> quoted").assertExists()
            onAllNodesWithTag(CARD_TITLE_TAG).assertCountEquals(2)
        }
    }
}
