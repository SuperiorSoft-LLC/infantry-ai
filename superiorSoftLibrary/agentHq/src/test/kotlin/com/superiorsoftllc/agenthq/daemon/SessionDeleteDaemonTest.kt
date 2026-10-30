package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AGENT-UX-11, contract 1.28.0: a session can be DELETED from the client, over the wire, the
 * way it can be created. `session_delete <id>` removes the daemon's durable record for that session
 * (its `SessionRecord` in the in-memory manager and, on persist, its row in `sessions.json`), pushes
 * the refreshed `sessions` list to every client, and refuses -- without touching anything -- an unknown
 * id and a session that is currently RUNNING (the `live` flag AGENT-UX-4 added).
 *
 * What a delete deliberately does NOT touch: the git worktree on disk (that is `worktree_remove`'s
 * job) and the provider CLI's own conversation store (it is the CLI's data, not the daemon's).
 */
class SessionDeleteDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sessionsIn(frame: Envelope): List<String> =
        json.decodeFromString(ListSerializer(SessionRecord.serializer()), frame.data).map { it.id }

    /** Spawn one session over the wire and wait for its (stub) run to finish, returning its id. */
    private fun spawnFinished(fixture: DaemonFixture, prompt: String): String {
        val before = fixture.daemon.manager.list().size
        fixture.send(Envelope(Envelope.NEW))
        fixture.send(Envelope(Envelope.PROMPT, prompt))
        fixture.await(what = "session #$before to finish") { fixture.daemon.manager.list().size == before + 1 }
        val id = fixture.daemon.manager.list()[before].id
        fixture.await(what = "session $id at rest") {
            fixture.daemon.manager.get(id)?.live == false
        }
        return id
    }

    @Test
    fun deletingASessionRemovesOnlyThatSessionAndThePushedListDropsIt() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            val first = spawnFinished(fixture, "one")
            val second = spawnFinished(fixture, "two")
            assertEquals(listOf(first, second), fixture.daemon.manager.list().map { it.id }, "precondition: two sessions")

            // The connect handshake already pushed one `sessions` frame; the delete pushes the next.
            val pushed = fixture.client.of(Envelope.SESSIONS).size
            fixture.send(Envelope(Envelope.SESSION_DELETE, first))
            val refreshed = fixture.client.await(Envelope.SESSIONS, occurrence = pushed, timeoutMs = 8_000)

            assertEquals(listOf(second), sessionsIn(refreshed), "the pushed list no longer names the deleted session")
            assertEquals(listOf(second), fixture.daemon.manager.list().map { it.id }, "the deleted record is gone from the store")
            assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "errors: ${fixture.client.of(Envelope.ERROR)}")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun deletingAnUnknownSessionIsACleanNoOpThatRepushesTheList() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            val kept = spawnFinished(fixture, "one")

            // SESSION-GHOST-1: an unknown id is a clean no-op that re-pushes the sessions list; no error frame.
            val pushesBefore = fixture.client.frames.count { it.type == Envelope.SESSIONS }
            val errorsBefore = fixture.client.frames.count { it.type == Envelope.ERROR }
            fixture.send(Envelope(Envelope.SESSION_DELETE, "no-such-session"))
            fixture.await(what = "a sessions push after the no-op delete") { fixture.client.frames.count { it.type == Envelope.SESSIONS } > pushesBefore }
            assertEquals(errorsBefore, fixture.client.frames.count { it.type == Envelope.ERROR }, "no error frame")

            assertEquals(listOf(kept), fixture.daemon.manager.list().map { it.id }, "an unknown id deletes nothing")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun deletingARunningSessionIsRefusedAndTheSessionSurvives() {
        val gated = GatedRunner()
        val fixture = DaemonFixture.launch(runner = gated)
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.NEW))
            fixture.send(Envelope(Envelope.PROMPT, "HANG"))
            fixture.await(what = "a live session") { fixture.daemon.manager.list().singleOrNull()?.live == true }
            val running = fixture.daemon.manager.list().single()

            fixture.send(Envelope(Envelope.SESSION_DELETE, running.id))
            val refusal = fixture.client.await(Envelope.ERROR, timeoutMs = 8_000)
            assertTrue(refusal.data.startsWith("session_delete refused:"), refusal.data)

            val after = fixture.daemon.manager.get(running.id)
            assertTrue(after != null && after.live, "a refused delete leaves the running session intact: $after")
            assertEquals(listOf(running.id), fixture.daemon.manager.list().map { it.id })

            // Clean up the held run so the fixture stops promptly.
            fixture.daemon.manager.kill(running.id, force = true)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
