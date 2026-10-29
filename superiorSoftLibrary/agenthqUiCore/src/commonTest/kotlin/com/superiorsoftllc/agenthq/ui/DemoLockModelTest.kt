package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEMO-LOCK (ticket 2f154f5f): `ready` demo=lock / demoNotice= reach the connection; absent = the normal app. */
class DemoLockModelTest {
    @Test
    fun readyWithoutDemoKeysLeavesTheAppUnlocked() {
        val s = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.115.0 instance=A since=0 build=dev"), 0)
        assertFalse(s.connection.demoLock)
        assertNull(s.connection.demoNotice)
    }

    @Test
    fun readyWithDemoLockCarriesTheNotice() {
        // "This is a demo. You have 10 turns." base64url, no padding.
        val s = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.115.0 instance=A since=0 build=dev demo=lock demoNotice=VGhpcyBpcyBhIGRlbW8uIFlvdSBoYXZlIDEwIHR1cm5zLg"), 0)
        assertTrue(s.connection.demoLock)
        assertEquals("This is a demo. You have 10 turns.", s.connection.demoNotice)
    }

    @Test
    fun writeControlsAreOffOnlyUnderTheLock() {
        // ticket db5b3b70: canWrite = live and not locked; a locked daemon refuses every write, so the UI dims them.
        val normal = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.115.0 instance=A since=0 build=dev"), 0)
        assertTrue(normal.connection.isLive)
        assertTrue(normal.connection.canWrite)
        val locked = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.115.0 instance=A since=0 build=dev demo=lock"), 0)
        assertTrue(locked.connection.isLive)
        assertFalse(locked.connection.canWrite)
        assertFalse(AppState().connection.canWrite, "not connected")
    }
}
