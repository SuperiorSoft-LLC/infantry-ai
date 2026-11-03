package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** KILL-TIMEOUT-1: a script's loopback call (and a plugin call) gets the direct ack; an ordinary client does not. */
class DirectAckTest {
    @Test fun loopbackAndPluginAreAcked() {
        assertTrue(wantsDirectAck(Envelope(Envelope.KILL, "s1 force", requestId = "loopback:abc")))
        assertTrue(wantsDirectAck(Envelope(Envelope.KILL, "s1", requestId = "plugin:abc")))
    }
    @Test fun ordinaryClientIsNot() {
        assertFalse(wantsDirectAck(Envelope(Envelope.KILL, "s1", requestId = "abc")))
        assertFalse(wantsDirectAck(Envelope(Envelope.KILL, "s1")))
    }
}
