// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartWorktree
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-8 (docs/RUNNER-POOL.md 3.1, 5): the runner keeps its session events in an outbox (seq per session) until the
 * orchestrator acknowledges them; [RunnerWatch] replays them deduped by (session, seq), keeps a silent runner's sessions as they
 * are, raises one attention item after `limits.runners.lostAfterMin`, and with `runners.onLost = restart-elsewhere` restarts the
 * tickets off the lost runner.
 */
class RunnerWatchTest {

    /** A remote runner over a real [RemoteStarts] outbox; [up] false = the link is down (health and events unanswered). */
    private class FakeRemote(val starts: RemoteStarts) : RunnerLink {
        var up = true
        /** Drop the next events answer after the runner handled the call (the ack reached it, the answer never came back). */
        var loseNextAnswer = false
        override fun ticketIdOf(sessionId: String): String? = null
        override fun health(): OrchestratorReply<RunnerHealth> =
            if (up) OrchestratorReply.Ok(RunnerHealth(RunnerHealth.UP)) else OrchestratorReply.Unavailable("link down")
        override fun events(sinceSeq: Long): OrchestratorReply<List<RunnerEvent>> =
            if (up) OrchestratorReply.Ok(starts.events(sinceSeq)) else OrchestratorReply.Unavailable("link down")
        override fun events(acked: Map<String, Long>): OrchestratorReply<List<RunnerEvent>> {
            if (!up) return OrchestratorReply.Unavailable("link down")
            val answer = starts.events(acked)
            if (loseNextAnswer) {
                loseNextAnswer = false
                return OrchestratorReply.Unavailable("answer lost")
            }
            return OrchestratorReply.Ok(answer)
        }
    }

    private object NoLocal : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
    }

    private var now = 1_000_000L
    private val starts = RemoteStarts(cloneRoot = { File("unused") }, outboxMax = { 100 }, log = {})
    private val remote = FakeRemote(starts)
    private val hub = RunnerHub(NoLocal, remotes = { listOf("r2") }, clock = { now }).also { h -> h.register("fake") { _, _ -> remote } }

    private fun session(id: String) {
        starts.started(id, StartWorktree(File("wt-$id").path, "ticket/$id", "main", "repo"))
        hub.placed(id, "r2")
    }

    private fun round(watch: RunnerWatch) {
        hub.heartbeat(lostAfterMs = 60_000)
        watch.tick()
    }

    @Test
    fun `the runner outbox numbers events per session and drops what the orchestrator acknowledged`() {
        session("s1"); session("s2")
        starts.onStatus("s1", "RUNNING"); starts.onStatus("s2", "RUNNING"); starts.onStatus("s1", "IDLE")
        assertEquals(listOf("s1" to 1L, "s2" to 1L, "s1" to 2L), starts.events(emptyMap()).map { it.sessionId to it.seq })
        assertEquals(listOf("s1" to 2L), starts.events(mapOf("s1" to 1L, "s2" to 1L)).map { it.sessionId to it.seq })
        assertTrue(starts.events(mapOf("s1" to 2L)).isEmpty())
    }

    @Test
    fun `the outbox is bounded by limits_runners_outboxMax, oldest first`() {
        val logged = mutableListOf<String>()
        val small = RemoteStarts(cloneRoot = { File("unused") }, outboxMax = { 2 }, log = { logged += it })
        small.started("s1", StartWorktree("wt", "ticket/s1", "main", "repo"))
        repeat(3) { small.onStatus("s1", "RUNNING") }
        assertEquals(listOf(2L, 3L), small.events(emptyMap()).map { it.seq })
        assertTrue(logged.single().contains("limits.runners.outboxMax"), logged.toString())
    }

    @Test
    fun `a link dropped mid-session replays its events with no duplicate and no gap`() {
        session("s1"); session("s2")
        val applied = mutableListOf<RunnerEvent>()
        val back = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()
        val watch = RunnerWatch(
            hub, lostAfterMs = { 30 * 60_000L }, restartElsewhere = { false },
            apply = { _, e -> applied += e }, reconnected = { _, s -> back += s }, clock = { now }, log = { logs += it },
        )
        starts.onStatus("s1", "RUNNING"); starts.onStatus("s2", "RUNNING")
        round(watch)
        assertEquals(listOf("s1" to 1L, "s2" to 1L), applied.map { it.sessionId to it.seq })

        // The answer to the next read is lost on the wire: nothing is applied, and nothing is dropped from the outbox.
        starts.onStatus("s1", "IDLE")
        remote.loseNextAnswer = true
        round(watch)
        assertEquals(2, applied.size)
        assertTrue(watch.isOffline("r2"))

        // The link drops; the sessions keep working on the runner, their events wait in its outbox.
        remote.up = false
        now += 60_000
        starts.onStatus("s2", "IDLE"); starts.onStatus("s1", "EXITED")
        round(watch)
        assertEquals(2, applied.size, "nothing is read from a silent runner")
        assertTrue(watch.isOffline("r2"))

        // Back: every event replays once, in seq order per session; a second round applies nothing new.
        remote.up = true
        now += 60_000
        round(watch)
        round(watch)
        val bySession = applied.groupBy({ it.sessionId }, { it.seq })
        assertEquals(listOf(1L, 2L, 3L), bySession["s1"], applied.toString())
        assertEquals(listOf(1L, 2L), bySession["s2"], applied.toString())
        assertEquals("EXITED", applied.last { it.sessionId == "s1" }.payload)
        assertEquals(listOf(listOf("s1", "s2")), back, "the reconnect is told once, with the runner's sessions")
        assertFalse(watch.isOffline("r2"))
        assertTrue(logs.none { it.contains("dropped") }, logs.toString())
        assertTrue(starts.events(emptyMap()).isEmpty(), "the outbox emptied once the orchestrator acknowledged it")
    }

    @Test
    fun `a gap left by an overflowing outbox is logged, never filled with a duplicate`() {
        val small = RemoteStarts(cloneRoot = { File("unused") }, outboxMax = { 1 }, log = {})
        val link = FakeRemote(small)
        val pool = RunnerHub(NoLocal, remotes = { listOf("r2") }, clock = { now }).also { h -> h.register("fake") { _, _ -> link } }
        small.started("s1", StartWorktree("wt", "ticket/s1", "main", "repo"))
        pool.placed("s1", "r2")
        val applied = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val watch = RunnerWatch(pool, { 30 * 60_000L }, { false }, apply = { _, e -> applied += e.seq }, clock = { now }, log = { logs += it })
        repeat(3) { small.onStatus("s1", "RUNNING") }
        pool.heartbeat(60_000); watch.tick()
        assertEquals(listOf(3L), applied)
        assertTrue(logs.single().contains("events 1..2"), logs.toString())
    }

    @Test
    fun `a runner silent past lostAfterMin raises one attention item and keeps its tickets`() {
        session("s1")
        val raised = mutableListOf<String>()
        val restarted = mutableListOf<String>()
        val watch = RunnerWatch(
            hub, lostAfterMs = { 30 * 60_000L }, restartElsewhere = { false }, apply = { _, _ -> },
            attention = { runner, sessions, detail -> raised += "$runner $sessions $detail" },
            ticketOf = { "ticket-1" }, restart = { t, _, _ -> restarted += t; null }, clock = { now }, log = {},
        )
        remote.up = false
        round(watch) // silent from now
        now += 29 * 60_000L
        round(watch)
        assertTrue(raised.isEmpty(), "not lost before lostAfterMin")
        now += 60_000L
        round(watch)
        now += 10 * 60_000L
        round(watch)
        assertEquals(1, raised.size, "one item per outage: $raised")
        assertTrue(raised.single().startsWith("r2 [s1] "), raised.single())
        assertTrue(raised.single().contains("stay in progress"), raised.single())
        assertTrue(restarted.isEmpty(), "runners.onLost = attention restarts nothing")
        assertEquals(listOf("s1"), hub.sessionsOn("r2"), "the session stays placed on the lost runner")
    }

    @Test
    fun `restart-elsewhere restarts each ticket off the lost runner and says unpushed work is lost`() {
        session("s1"); session("s2")
        val raised = mutableListOf<String>()
        val restarted = mutableListOf<Triple<String, String, String>>()
        val watch = RunnerWatch(
            hub, lostAfterMs = { 30 * 60_000L }, restartElsewhere = { true }, apply = { _, _ -> },
            attention = { _, _, detail -> raised += detail },
            ticketOf = { sid -> if (sid == "s1") "ticket-1" else null }, // s2 is a spawn, not a ticket: not restarted
            restart = { t, r, p -> restarted += Triple(t, r, p); null }, clock = { now }, log = {},
        )
        remote.up = false
        round(watch)
        now += 30 * 60_000L
        round(watch)
        assertTrue(raised.single().contains("work not pushed from 'r2' is lost"), raised.single())
        assertEquals(listOf("ticket-1"), restarted.map { it.first })
        assertEquals("r2", restarted.single().second)
        assertEquals(listOf("s2"), hub.sessionsOn("r2"), "the restarted ticket's old session is no longer tracked on r2")
    }
}
