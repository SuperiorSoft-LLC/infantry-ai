// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CHAT-SENDING-1: a resend of a client message id the daemon already took is recognised, so it is acknowledged and never run twice. */
class PromptSendIdsTest {
    @Test fun `a repeated client id is a duplicate, a released one runs again`() {
        val ids = PromptSendIds { 10 }
        val key = ids.keyOf("s1", "send:abc")!!
        assertTrue(ids.claim(key), "first send runs")
        assertFalse(ids.claim(key), "the same id again is a duplicate")
        ids.release(key)
        assertTrue(ids.claim(key), "a refused prompt is forgotten, so its retry runs")
    }

    @Test fun `only client message ids on a named session are remembered`() {
        val ids = PromptSendIds { 10 }
        assertNull(ids.keyOf("s1", "queue-1"), "a window's own counter is not unique across windows")
        assertNull(ids.keyOf(null, "send:abc"))
        assertNull(ids.keyOf("s1", null))
        assertTrue(ids.keyOf("s1", "send:x") != ids.keyOf("s2", "send:x"), "per session")
    }

    @Test fun `the oldest id is forgotten past the setting`() {
        val ids = PromptSendIds { 2 }
        listOf("send:1", "send:2", "send:3").forEach { assertTrue(ids.claim(ids.keyOf("s", it)!!)) }
        assertTrue(ids.claim(ids.keyOf("s", "send:1")!!), "evicted, so no longer known")
        assertFalse(ids.claim(ids.keyOf("s", "send:3")!!))
    }

    @Test fun `the ack echoes the session and the client id`() {
        val req = Envelope(Envelope.PROMPT, "hi", "s1", requestId = "send:abc")
        val ack = promptAck(req, duplicate = true)
        assertEquals(Envelope.PROMPT_ACK, ack.type)
        assertEquals("duplicate", ack.data)
        assertEquals("s1", ack.sessionId)
        assertEquals("send:abc", ack.requestId)
    }
}
