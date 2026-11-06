// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RUNTIME-LOAD-5: the HOST's scope on the sessions read model. A plugin cannot read past it: a worker session reaches its own
 * project only, `all` and foreign projects are for pm / owner roles, an agent connection without a session or with a session the
 * daemon does not know is refused, a community plugin without the grant reads nothing, and the policy gate applies.
 */
class LocalSessionReadsTest {
    private fun session(id: String, role: String, project: String?) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, role = role, projectId = project,
    )

    private val records = listOf(session("s-a1", "backend", "A"), session("s-b1", "backend", "B"), session("s-pm", "pm", "A"))
    private var gate: String? = null
    private val reads = LocalSessionReads(
        sessions = { records },
        replay = { id ->
            if (id != "s-a1") null
            else LocalSessionReads.Replay(
                gap = null,
                frames = listOf(
                    Envelope(Envelope.TEXT_DELTA, "hello ", id),
                    Envelope(Envelope.TEXT_DELTA, "world", id),
                    Envelope(Envelope.STDERR, "warn", id),
                    Envelope(Envelope.FINISHED, """{"outcome":"SUCCEEDED","exitCode":0}""", id),
                ),
            )
        },
        gate = { gate },
    )

    private fun session(id: String) = Caller(owner = true, activeSession = id)
    private val owner = Caller(owner = true)

    private fun <T> ok(r: SessionsResult<T>): T = assertIs<SessionsResult.Ok<T>>(r, "$r").value
    private fun refused(r: SessionsResult<*>): String = assertIs<SessionsResult.Refused>(r, "$r").reason

    @Test fun `a worker reads its own project only`() {
        val worker = session("s-a1")
        assertEquals(listOf("s-a1", "s-pm"), ok(reads.list(worker, "A")).map { it.id })
        assertTrue("not this session's project" in refused(reads.list(worker, "B")))
        assertTrue("is for the owner" in refused(reads.list(worker, null)), "every project = all, refused for a worker")
        assertTrue("belongs to project 'B'" in refused(reads.find("s-b1", worker)))
        assertTrue("belongs to project 'B'" in refused(reads.transcript("s-b1", worker)), "no transcript of a foreign session")
        assertEquals("A", ok(reads.scope(worker, null)).projectId)
        assertTrue(ok(reads.scope(worker, "all")).refusal!!.contains("is for the owner"))
    }

    @Test fun `only the owner reaches every project, a pm is confined to its own (PROJECT-ISOLATION-1)`() {
        assertTrue("is for the owner" in refused(reads.list(session("s-pm"), "all")))
        assertTrue("not this session's project" in refused(reads.list(session("s-pm"), "B")))
        assertEquals(3, ok(reads.list(owner, "all")).size)
        assertEquals(listOf("s-b1"), ok(reads.list(owner, "B")).map { it.id })
        assertEquals(3, ok(reads.list(owner, null)).size)
        assertEquals(null, ok(reads.caller(owner)))
        assertEquals("p9", ok(reads.scope(owner, null, "p9")).projectId, "an owner's default project is the client's")
    }

    @Test fun `an unscoped caller is refused, fail closed`() {
        assertTrue("needs a session" in refused(reads.list(Caller(owner = false, agent = true), "A")))
        assertTrue("unknown to this daemon" in refused(reads.list(session("s-gone"), "A")))
    }

    @Test fun `find resolves a unique prefix and words a miss`() {
        assertEquals("s-b1", ok(reads.find("s-b", owner)).id)
        assertEquals("no session 'zz' on this daemon", assertIs<SessionsResult.Missing>(reads.find("zz", owner)).text)
        assertTrue(assertIs<SessionsResult.Missing>(reads.find("s-", owner)).text.startsWith("session id 's-' is ambiguous"))
    }

    @Test fun `the host renders the transcript`() {
        assertEquals(listOf("hello world", "[stderr] warn", "[finished] SUCCEEDED exit=0"), ok(reads.transcript("s-a1", owner)).lines)
        assertEquals("no replay history for session 's-pm'", ok(reads.transcript("s-pm", owner)).note)
    }

    @Test fun `the active policy gates the read`() {
        gate = "refused by preset"
        assertEquals("refused by preset", refused(reads.list(owner, null)))
    }

    @Test fun `a community plugin needs the grants`() {
        val none = GrantedSessionReads({ reads }) { false }
        assertEquals("missing grant 'sessions.read' in modules.json", refused(none.list(owner, null)))
        val readOnly = GrantedSessionReads({ reads }) { it == "sessions.read" }
        assertEquals(3, ok(readOnly.list(owner, null)).size)
        assertEquals("missing grant 'sessions.transcript' in modules.json", refused(readOnly.transcript("s-a1", owner)))
    }
}
