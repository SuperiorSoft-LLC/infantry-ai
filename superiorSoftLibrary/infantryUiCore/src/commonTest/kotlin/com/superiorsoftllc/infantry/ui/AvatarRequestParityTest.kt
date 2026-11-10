// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** AVATAR-CHAT-MISMATCH-1: the card and the chat message build the same request (role, catalog colour, state) for one agent. */
class AvatarRequestParityTest {
    private val agent = AgentView(
        session = SessionRecord(id = "agent-1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"),
        role = "fullstack",
    )
    private val state = AppState(agents = mapOf("agent-1" to agent), roles = listOf(Role("fullstack", "Full-Stack", "#14B8A6")))

    @Test
    fun cardAndChatRequestsAgreeForOneAgent() {
        val card = avatarRequestFor(agent, state, size = 40)
        val chat = avatarRequestFor(agent, state).copy(sizeDp = 34)
        assertEquals(card.copy(sizeDp = 34), chat)
        assertEquals("#14B8A6", chat.catalogColor)
        assertEquals("BUSY", chat.state)
    }
}
