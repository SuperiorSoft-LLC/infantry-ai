// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.MessageOrigin
import com.superiorsoftllc.infantry.ui.model.OutputLine
import kotlin.test.Test

/** Contract 1.125.0: a turn is drawn from its typed origin: portrait + role for an agent, label only for outside senders. */
@OptIn(ExperimentalTestApi::class)
class MessageOriginUiTest {
    private fun turn(origin: MessageOrigin) = OutputLine(LineKind.USER, "hello", 0, origin)

    @Test
    fun anAgentTurnShowsThatRolesPortraitAndTheRoleName() {
        runDesktopComposeUiTest(900, 600) {
            setContent { ChatLine(turn(MessageOrigin.Agent("s1", "a1", "architect")), agentId = "me", agentName = "Me", agentRole = "qa") }
            onAllNodesWithTag(AVATAR_TAG).assertCountEquals(1)
            onNodeWithContentDescription("architect avatar").assertExists()
            onNodeWithTag(ORIGIN_LABEL_TAG).assertTextEquals("architect")
        }
    }

    @Test
    fun externalAndSystemTurnsShowTheSenderLabelOnlyAndNoPortrait() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                androidx.compose.foundation.layout.Column {
                    ChatLine(turn(MessageOrigin.External("github-bridge", "mail")), agentId = "me", agentName = "Me", agentRole = "qa")
                    ChatLine(turn(MessageOrigin.System("handover")), agentId = "me", agentName = "Me", agentRole = "qa")
                }
            }
            onAllNodesWithTag(AVATAR_TAG).assertCountEquals(0)
            onAllNodesWithTag(ORIGIN_LABEL_TAG).assertCountEquals(2)
        }
    }

    @Test
    fun anOwnerTurnIsAsBeforeTheUsersDiscAndNoLabel() {
        runDesktopComposeUiTest(900, 600) {
            setContent { ChatLine(turn(MessageOrigin.Owner), agentId = "me", agentName = "Me", agentRole = "qa") }
            onAllNodesWithTag(AVATAR_TAG).assertCountEquals(1)
            onNodeWithContentDescription("your portrait").assertExists()
            onAllNodesWithTag(ORIGIN_LABEL_TAG).assertCountEquals(0)
        }
    }
}
