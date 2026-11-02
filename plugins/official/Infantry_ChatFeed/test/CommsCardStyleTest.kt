package com.superiorsoftllc.agenthq.plugins.official.chatfeed

import com.superiorsoftllc.agenthq.ui.model.MessageOrigin
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
