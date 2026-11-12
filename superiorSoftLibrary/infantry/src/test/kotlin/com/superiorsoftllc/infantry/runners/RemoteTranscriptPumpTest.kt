// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RUNNER-OUTPUT-1: a runner session's transcript lines become sequenced-able chat frames, each line published once. */
class RemoteTranscriptPumpTest {
    @Test
    fun `turn and answer lines become a turn and a text frame, never stdout`() {
        val frames = RemoteTranscriptPump.frames("s1", listOf("[turn] ping, reply pong", "pong", "error: boom", """{"raw":1}"""))
        assertEquals(listOf(Envelope.TURN, Envelope.TEXT_DELTA, Envelope.ERROR, Envelope.STDOUT), frames.map { it.type })
        assertEquals(listOf("ping, reply pong", "pong", "boom"), frames.take(3).map { it.data })
        assertEquals(setOf("s1"), frames.map { it.sessionId }.toSet())
    }

    @Test
    fun `a growing answer line goes out as its new part only, and each line once`() {
        var lines = listOf("[turn] ping", "po")
        val published = mutableListOf<Envelope>()
        val reads = mutableListOf<Int>()
        val pump = RemoteTranscriptPump(
            read = { _, from -> reads += from; OrchestratorReply.Ok(lines.drop(from)) },
            publish = { published += it },
        )
        assertNull(pump.pull("s1"))
        lines = listOf("[turn] ping", "pong", "done")
        assertNull(pump.pull("s1"))
        assertNull(pump.pull("s1"))
        assertEquals(listOf(Envelope.TURN to "ping", Envelope.TEXT_DELTA to "po", Envelope.TEXT_DELTA to "ng", Envelope.TEXT_DELTA to "\ndone"), published.map { it.type to it.data })
        assertEquals(listOf(0, 1, 2), reads)
    }

    @Test
    fun `an unreachable runner says why and publishes nothing`() {
        val published = mutableListOf<Envelope>()
        val pump = RemoteTranscriptPump(read = { _, _ -> OrchestratorReply.Unavailable("runner 'r': down") }, publish = { published += it })
        assertEquals("runner 'r': down", pump.pull("s1"))
        assertEquals(emptyList(), published)
    }

    @Test
    fun `a restart keeps each remote session, its runner, state and the lines already taken`() {
        val file = kotlin.io.path.createTempDirectory("rop1").toFile().resolve("runner-sessions.json")
        val before = RemoteSessionMirror(sessionIds = { listOf("s1") }, store = file, runnerOf = { "infantry-runner" })
        before.placed("s1", RemoteSessionMirror.Placed("opencode", "m", null, null, projectId = "p1"))
        before.status("s1", "IDLE")
        before.taken("s1", 3)
        val after = RemoteSessionMirror(sessionIds = { listOf("s1") }, store = file)
        assertEquals(mapOf("s1" to "infantry-runner"), after.restore())
        assertEquals(3, after.taken("s1"))
        val rec = after.get("s1")!!
        assertEquals("p1", rec.projectId)
        assertEquals(com.superiorsoftllc.infantry.session.SessionState.IDLE, rec.state)
        after.forget("s1")
        assertEquals(emptyMap(), RemoteSessionMirror(sessionIds = { emptyList() }, store = file).restore())
    }
}
