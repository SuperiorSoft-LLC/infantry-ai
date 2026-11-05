// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.model.MessageOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommsCardStyleTest {
    @Test
    fun ownerIsComms() {
        val s = commsCardStyle(MessageOrigin.Owner)
        assertEquals("Comms", s.title)
        assertEquals(CardTone.OwnerAccent, s.tone)
        assertTrue(s.showIcon && s.showTime)
    }

    @Test
    fun outsideSendersAreIncomingCommsWithAnotherTone() {
        val owner = commsCardStyle(MessageOrigin.Owner)
        for (o in listOf(MessageOrigin.Agent("s1"), MessageOrigin.External("x"), MessageOrigin.System("mail"))) {
            val s = commsCardStyle(o)
            assertEquals("Incoming Comms", s.title)
            assertNotEquals(owner.tone, s.tone)
            assertTrue(s.showIcon && s.showTime)
        }
    }

    @Test
    fun agentReplyIsComms() {
        assertEquals("Comms", AgentReplyCardStyle.title)
        assertEquals(CardTone.AgentReply, AgentReplyCardStyle.tone)
    }
}

class OwnerMessageTextTest {
    @Test
    fun ownerBodyUsesTheThemeTextToneNotTheAccent() {
        assertEquals(com.superiorsoftllc.infantry.ui.Palette.Text, OwnerMessageText)
        assertNotEquals(com.superiorsoftllc.infantry.ui.Palette.Accent, OwnerMessageText)
    }
}
