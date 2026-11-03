package com.superiorsoftllc.infantry.plugins.demo.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoLockStateTest {
    @Test fun `no demo=lock is off`() {
        val s = DemoLockState.of(mapOf("contract" to "1.150.0", "notice" to "eA"), mapOf(TURN_LIMIT_CODE to "x"))
        assertEquals(DemoLockState.Off, s)
    }

    @Test fun `demo=lock decodes the notice`() {
        // "This is a demo. You have 10 turns." base64url, no padding.
        val s = DemoLockState.of(mapOf("demo" to "lock", "notice" to "VGhpcyBpcyBhIGRlbW8uIFlvdSBoYXZlIDEwIHR1cm5zLg"), emptyMap())
        assertTrue(s.locked)
        assertEquals("This is a demo. You have 10 turns.", s.notice)
    }

    @Test fun `a daemon before POLICY-1 sent demoNotice`() {
        assertEquals("x", DemoLockState.of(mapOf("demo" to "lock", "demoNotice" to "eA"), emptyMap()).notice)
    }

    @Test fun `the turn-limit notice replaces the pinned one`() {
        val s = DemoLockState.of(mapOf("demo" to "lock", "notice" to "eA"), mapOf(TURN_LIMIT_CODE to "Your turns are used up."))
        assertEquals("Your turns are used up.", s.notice)
    }

    @Test fun `an undecodable notice is none`() {
        assertNull(DemoLockState.of(mapOf("demo" to "lock", "notice" to "%%%"), emptyMap()).notice)
    }
}
