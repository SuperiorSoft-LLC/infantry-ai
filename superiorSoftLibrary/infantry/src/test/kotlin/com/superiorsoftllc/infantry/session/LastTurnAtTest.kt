// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.daemon.plugins.LocalSessionReads
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * LAST-TURN-1 (ticket 09f5082f): `infantry_list_sessions` / `infantry_get_session` must show times that move with the session.
 *
 * Root cause: the session read model ([LocalSessionReads], behind both tools and every plugin's `SessionReads`) served the RAW
 * record, while the activity times live in [SessionActivityLog] and only the wire's session frame laid them over. The raw record
 * held whatever `sessions.json` stored at its last write (the store serializes with the overlay), so a session read either had no
 * `lastOutputAt` (a session started since the daemon came up) or one frozen at the daemon's start (the PM read 2h20m old).
 *
 * The turn end is the daemon's harness-neutral `SessionEvent.Exit` (`finished`), which every harness (headless claude, the
 * interactive claude PTY, opencode, pi, JSON descriptors) raises at the end of a turn; it calls [SessionActivityLog.turnEnded].
 */
class LastTurnAtTest {
    private fun record(id: String, lastOutputAt: Long? = null, lastTurnAt: Long? = null) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.BUSY,
        role = "frontend", projectId = "P", agentId = "agent-1", lastOutputAt = lastOutputAt, lastTurnAt = lastTurnAt,
    )

    @BeforeTest fun clean() = SessionActivityLog.clear()
    @AfterTest fun cleanAfter() = SessionActivityLog.clear()

    @Test fun `each finished turn moves lastTurnAt, output alone does not`() {
        SessionActivityLog.output("s1", "replying", now = 1_000)
        assertNull(SessionActivityLog.overlay(record("s1")).lastTurnAt, "no turn ended yet")
        SessionActivityLog.turnEnded("s1", now = 2_000)
        assertEquals(2_000, SessionActivityLog.overlay(record("s1")).lastTurnAt)
        SessionActivityLog.output("s1", "Bash: ls", now = 3_000, tool = true)
        SessionActivityLog.event("s1", "state busy", now = 3_500)
        val mid = SessionActivityLog.overlay(record("s1"))
        assertEquals(2_000, mid.lastTurnAt, "mid-turn output and events keep the last turn's time")
        assertEquals(3_000, mid.lastOutputAt)
        assertEquals(1, SessionActivityLog.toolCalls("s1"), "the turn stamp keeps the tool count")
        SessionActivityLog.turnEnded("s1", now = 4_000)
        val second = SessionActivityLog.overlay(record("s1"))
        assertEquals(4_000, second.lastTurnAt, "the second turn moves it again")
        assertEquals(3_000, second.lastOutputAt)
        assertEquals(4_000, second.lastEventAt)
    }

    @Test fun `a time the log has not seen since the start keeps the stored one`() {
        val stored = record("s2", lastOutputAt = 500, lastTurnAt = 400)
        SessionActivityLog.event("s2", "state busy", now = 900)
        val r = SessionActivityLog.overlay(stored)
        assertEquals(500, r.lastOutputAt)
        assertEquals(400, r.lastTurnAt)
    }

    @Test fun `the session read model serves the live times, not the stored record`() {
        // The record as sessions.json left it at the daemon's start: an old output time and no turn.
        val records = listOf(record("s-pm", lastOutputAt = 100))
        val reads = LocalSessionReads(sessions = { records })
        val owner = Caller(owner = true)
        SessionActivityLog.output("s-pm", "replying", now = 10_000)
        SessionActivityLog.turnEnded("s-pm", now = 11_000)
        val first = assertIs<SessionsResult.Ok<*>>(reads.find("s-pm", owner)).value as com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
        assertEquals(10_000, first.lastOutputAt)
        assertEquals(11_000, first.lastTurnAt)
        SessionActivityLog.output("s-pm", "replying", now = 20_000)
        SessionActivityLog.turnEnded("s-pm", now = 21_000)
        val listed = assertIs<SessionsResult.Ok<List<com.superiorsoftllc.infantry.daemon.api.sessions.SessionView>>>(reads.list(owner, "all")).value.single()
        assertEquals(20_000, listed.lastOutputAt, "a second turn moves lastOutputAt on the read model")
        assertEquals(21_000, listed.lastTurnAt, "and lastTurnAt")
    }
}
