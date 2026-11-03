package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionNotifier
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DP-7e: api.sessionNotifier reaches live sessions of a role / one session, nothing for none, and is denied to a community plugin without `sessions.notify`. */
class SessionNotifierTest {
    private fun s(id: String, role: String, project: String, state: SessionState = SessionState.IDLE) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = state, role = role, projectId = project,
    )

    private val sent = mutableListOf<Triple<String, String, String>>()
    private val sessions = listOf(s("a", "backend", "P1"), s("b", "backend", "P2"), s("c", "pm", "P1"))
    private val local = LocalSessionNotifier({ sessions }, { to, subj, body -> sent += Triple(to, subj, body) })

    @Test fun `notifyRole mails only the live sessions of that role in that project`() {
        assertEquals(1, local.notifyRole("P1", "backend", "subj", "body"))
        assertEquals(listOf(Triple("a", "subj", "body")), sent)
    }

    @Test fun `nothing for a role with no live session or an unknown session`() {
        assertEquals(0, local.notifyRole("P1", "qa", "s", "b"))
        assertFalse(local.notifySession("zzz", "s", "b"))
        assertTrue(sent.isEmpty())
        assertTrue(local.notifySession("c", "s", "b"))
        assertEquals("c", sent.single().first)
    }

    @Test fun `a failing send is not counted and does not throw`() {
        val broken = LocalSessionNotifier({ sessions }, { _, _, _ -> error("down") })
        assertEquals(0, broken.notifyRole("P1", "backend", "s", "b"))
    }

    @Test fun `a community plugin without sessions_notify is refused, with it delivers`() {
        val denied = GrantedSessionNotifier({ local }) { false }
        assertEquals("missing grant 'sessions.notify' in modules.json", assertFailsWith<IllegalStateException> { denied.notifyRole("P1", "backend", "s", "b") }.message)
        assertFailsWith<IllegalStateException> { denied.notifySession("a", "s", "b") }
        assertEquals(1, GrantedSessionNotifier({ local }) { it == "sessions.notify" }.notifyRole("P1", "backend", "s", "b"))
    }

    @Test fun `the default port delivers nothing`() {
        assertEquals(0, NoSessionNotifier.notifyRole("P1", "backend", "s", "b"))
        assertFalse(NoSessionNotifier.notifySession("a", "s", "b"))
    }
}
