package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** DEMO-MERGE-1: without any AGENTHQ_DEMO_* setting the demo mode gates nothing and adds nothing. */
class DemoModeOffTest {
    private val off = DemoLock.fromEnvironment { null }

    @Test fun `no demo env means the lock is off, with no token, no turn limit and no ready fields`() {
        assertFalse(off.enabled)
        assertNull(off.publicToken)
        assertNull(off.turnLimit)
        assertEquals("", off.readyFields())
    }

    @Test fun `an off lock refuses no frame and no mail`() {
        for (type in listOf(Envelope.PROMPT, Envelope.KILL, Envelope.INTERRUPT, Envelope.SETTINGS_SET, Envelope.PROJECT_SAVE, "anything")) {
            assertNull(off.frameRefusal(Envelope(type, "x"), "s1"), type)
        }
        assertNull(off.mailRefusal(MailSendRequest(from = "a", to = "b", subject = "s", body = "b")))
    }

    @Test fun `the demo variables alone do not switch the lock on`() {
        val env = mapOf("AGENTHQ_DEMO_PUBLIC_TOKEN" to "t", "AGENTHQ_DEMO_TURNS_PER_IP" to "3")
        val lock = DemoLock.fromEnvironment { env[it] }
        assertFalse(lock.enabled)
        assertNull(lock.frameRefusal(Envelope(Envelope.KILL, ""), null))
    }

    @Test fun `AGENTHQ_DEMO_LOCK=1 switches it on and refuses a write frame`() {
        val lock = DemoLock.fromEnvironment { if (it == "AGENTHQ_DEMO_LOCK") "1" else null }
        assert(lock.enabled)
        assert(lock.frameRefusal(Envelope(Envelope.SETTINGS_SET, "x"), null) != null)
    }
}
