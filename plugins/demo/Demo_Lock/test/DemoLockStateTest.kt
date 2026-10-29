package com.superiorsoftllc.agenthq.plugins.demo.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoLockStateTest {
    @Test fun `no demo=lock is off and allows writes`() {
        val s = DemoLockState.of(mapOf("contract" to "1.150.0", "demoNotice" to "eA"), mapOf(TURN_LIMIT_CODE to "x"))
        assertEquals(DemoLockState.Off, s)
        assertTrue(s.policy.canWrite)
        assertNull(s.policy.reason)
    }

    @Test fun `demo=lock refuses writes with the reason and decodes the notice`() {
        // "This is a demo. You have 10 turns." base64url, no padding.
        val s = DemoLockState.of(mapOf("demo" to "lock", "demoNotice" to "VGhpcyBpcyBhIGRlbW8uIFlvdSBoYXZlIDEwIHR1cm5zLg"), emptyMap())
        assertTrue(s.locked)
        assertFalse(s.policy.canWrite)
        assertEquals(DISABLED_REASON, s.policy.reason)
        assertEquals("This is a demo. You have 10 turns.", s.notice)
    }

    @Test fun `the turn-limit notice replaces the pinned one`() {
        val s = DemoLockState.of(mapOf("demo" to "lock", "demoNotice" to "eA"), mapOf(TURN_LIMIT_CODE to "Your turns are used up."))
        assertEquals("Your turns are used up.", s.notice)
    }

    @Test fun `an undecodable notice is none`() {
        assertNull(DemoLockState.of(mapOf("demo" to "lock", "demoNotice" to "%%%"), emptyMap()).notice)
    }
}
