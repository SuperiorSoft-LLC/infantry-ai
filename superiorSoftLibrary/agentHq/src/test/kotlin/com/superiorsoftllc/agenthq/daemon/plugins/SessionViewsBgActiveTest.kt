package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionText
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** BG-ACTIVE-1: a session with only a daemon bg job reads active; with nothing left it falls back to idle. */
class SessionViewsBgActiveTest {
    private fun rec(jobs: List<BgJob>) = SessionRecord(
        id = "s", provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, bgJobs = jobs,
    )

    @Test fun bgJobOnlyReadsActiveWithItsLabel() {
        val v = SessionViews.of(rec(listOf(BgJob("j", "gradle test", 1L, 1L))))
        assertEquals("gradle test", v.backgroundWork)
        assertEquals("working (background: gradle test)", SessionText.liveState(v))
    }

    @Test fun bothGoneFallsBackToIdle() {
        val v = SessionViews.of(rec(emptyList()))
        assertNull(v.backgroundWork)
        assertEquals("idle", SessionText.liveState(v))
    }
}
