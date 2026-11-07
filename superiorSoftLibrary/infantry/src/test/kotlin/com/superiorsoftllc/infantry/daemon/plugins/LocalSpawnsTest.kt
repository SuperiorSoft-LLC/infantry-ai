// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.SpawnGuard
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.NoBoard
import com.superiorsoftllc.infantry.daemon.api.board.StartResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.Tickets
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnRequest
import com.superiorsoftllc.infantry.daemon.api.spawns.TicketStartOutcome
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-LOAD-6: the HOST spawn guardrails. The limits live in the daemon: a caller (any plugin calling for it) cannot exceed the
 * depth, the concurrency or the kill scope, and what the client passes ([SpawnNarrowing]) only ever narrows them.
 */
class LocalSpawnsTest {
    private fun session(
        id: String, role: String = "backend", project: String? = "A", spawner: String? = null,
        state: SessionState = SessionState.IDLE, kill: KillOutcome? = null, ticket: String? = null,
    ) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = state, role = role,
        projectId = project, spawnerSessionId = spawner, killOutcome = kill, ticketId = ticket, providerSessionId = "p-$id",
    )

    private val records = mutableListOf(
        session("s-pm", role = "pm"),
        session("s-w1", spawner = "s-pm"),
        session("s-other", project = "A"),
        session("s-b", project = "B"),
    )
    private val limits = mutableMapOf(LimitRegistry.MAX_DEPTH.key to 1, LimitRegistry.MCP_MAX_SPAWNS.key to 2, LimitRegistry.MAX_PER_POD.key to 10)
    private val released = mutableSetOf<String>()
    private val guard = SpawnGuard(
        sessions = { id -> records.firstOrNull { it.id == id } },
        maxPerPodSource = { limits.getValue(LimitRegistry.MAX_PER_POD.key) },
        maxDepthSource = { limits.getValue(LimitRegistry.MAX_DEPTH.key) },
        all = { records.toList() },
        released = { it.id in released },
    )
    private var counter = 0
    private val starts = mutableListOf<String>()
    private val tasks = mutableListOf<Pair<String, String>>()
    private var ticket = TicketInfo(id = "ticket-1234abcd", title = "t", projectId = "A", status = TicketStatus.TODO, position = 0, createdAt = 0, updatedAt = 0)
    private var startAnswer: (TicketInfo) -> StartResult = { t ->
        val worker = session("s-tw${++counter}")
        records += worker
        StartResult(t.copy(status = TicketStatus.IN_PROGRESS, sessionId = worker.id), ok = true, reason = "started", detail = "cut from dev abc1234; launch warning: budget")
    }
    private val tickets = object : Tickets by NoBoard.tickets {
        override fun get(id: String, by: Caller): BoardResult<TicketInfo> =
            if (ticket.id.endsWith(id) || ticket.id == id) BoardResult.Ok(ticket) else BoardResult.Refused("no ticket '$id' on this daemon")
        override fun start(id: String, by: Caller): BoardResult<StartResult> {
            starts += id
            return BoardResult.Ok(startAnswer(ticket))
        }
    }
    private var gate: String? = null
    private val spawns = LocalSpawns(
        sessions = { records.toList() },
        guard = guard,
        limit = { def: LimitDef -> limits[def.key] ?: def.default.toInt() },
        tickets = { tickets },
        create = { request -> session("s-new${++counter}", role = request.role ?: "backend", project = request.projectId).also { records += it }.let(com.superiorsoftllc.infantry.daemon.StartedSession::Local) },
        firstTask = { started, task -> tasks += started.id to task },
        setSpawner = { id, spawner -> records.replaceAll { if (it.id == id) it.copy(spawnerSessionId = spawner) else it } },
        gate = { gate },
    )

    private fun from(id: String) = Caller(owner = true, activeSession = id)
    private val owner = Caller(owner = true)
    private fun <T> ok(r: SessionsResult<T>): T = assertIs<SessionsResult.Ok<T>>(r, "$r").value
    private fun refused(r: SessionsResult<*>): String = assertIs<SessionsResult.Refused>(r, "$r").reason

    @Test fun `a root spawns, records itself as the spawner, and sends the task as the first turn`() {
        val spawned = ok(spawns.spawn(SpawnRequest(role = "backend", projectId = "A", task = "do it"), from("s-pm")))
        assertEquals(1, spawned.depth)
        assertEquals("s-pm", spawned.session.spawnerSessionId)
        assertEquals(2, spawned.openSpawns, "s-w1 and the new one")
        assertTrue(spawned.taskSent)
        assertEquals(listOf(spawned.session.id to "do it"), tasks)
    }

    @Test fun `depth comes from the persisted spawner chain, and nothing is created beyond it`() {
        val before = records.size
        val why = refused(spawns.spawn(SpawnRequest(role = "backend"), from("s-w1")))
        assertTrue(why.startsWith("depth limit. This session runs at depth 1, so the session would be at depth 2"), why)
        assertEquals(before, records.size)
        assertTrue("worker would be at depth 2" in refused(spawns.startTicket("1234abcd", from("s-w1"))))
        assertEquals(emptyList(), starts, "a refused start never reaches the board")
    }

    @Test fun `the client's values only narrow`() {
        limits[LimitRegistry.MCP_MAX_SPAWNS.key] = 5
        // A wider client maximum is not honoured: the daemon's depth 1 still refuses s-w1.
        assertTrue(refused(spawns.admit(from("s-w1"), SpawnNarrowing(maxDepth = 9))).startsWith("depth limit"))
        // A claimed depth deeper than the host's counts; a shallower one does not lower it.
        assertTrue(refused(spawns.admit(from("s-pm"), SpawnNarrowing(depth = 1))).startsWith("depth limit. This session runs at depth 1"))
        assertEquals(1, ok(spawns.admit(from("s-pm"), SpawnNarrowing(depth = 0))).depth)
        assertTrue(refused(spawns.admit(from("s-pm"), SpawnNarrowing(maxSpawns = 1))).startsWith("concurrency limit. This session already has 1 open session(s) of a maximum of 1"))
        assertEquals(5, ok(spawns.admit(from("s-pm"), SpawnNarrowing(maxSpawns = 50))).maxSpawns)
    }

    @Test fun `concurrency counts the caller's open spawns, not killed, ended or released ones`() {
        records += session("s-w2", spawner = "s-pm")
        val why = refused(spawns.spawn(SpawnRequest(role = "backend"), from("s-pm")))
        assertTrue(why.startsWith("concurrency limit. This session already has 2 open session(s) of a maximum of 2"), why)
        assertTrue("s-w1, s-w2" in why && "infantry_kill" in why, why)
        assertTrue(refused(spawns.startTicket("1234abcd", from("s-pm"))).startsWith("concurrency limit"))
        assertEquals(emptyList(), starts)

        records.replaceAll { if (it.id == "s-w2") it.copy(killOutcome = KillOutcome.FORCED) else it }
        assertEquals(listOf("s-w1"), ok(spawns.admit(from("s-pm"))).open, "a killed session gives its slot back")
        records.replaceAll { if (it.id == "s-w2") it.copy(state = SessionState.BUSY) else it }
        limits[LimitRegistry.MCP_MAX_SPAWNS.key] = 3
        assertEquals(listOf("s-w1", "s-w2"), ok(spawns.admit(from("s-pm"))).open, "running again, it holds one")
        records.replaceAll { if (it.id == "s-w2") it.copy(state = SessionState.EXITED) else it }
        released += "s-w1"
        assertEquals(emptyList(), ok(spawns.admit(from("s-pm"))).open, "ended, or a finished ticket's idle worker")
    }

    @Test fun `a ticket start is admitted, started on the board and its worker counted`() {
        val out = ok(spawns.startTicket("1234abcd", from("s-pm")))
        assertEquals(TicketStartOutcome.STARTED, out.outcome)
        assertEquals("s-pm", out.startedBy)
        assertEquals("cut from dev abc1234", out.note)
        assertEquals("launch warning: budget", out.warning)
        assertEquals(1, out.depth)
        assertEquals(2, out.openSpawns)
        assertEquals(listOf(ticket.id), starts)
    }

    @Test fun `an in-progress ticket with a healthy worker starts nothing and passes even at the limit`() {
        records += session("s-w2", spawner = "s-pm")
        records += session("s-run", state = SessionState.BUSY)
        ticket = ticket.copy(status = TicketStatus.IN_PROGRESS, sessionId = "s-run")
        val out = ok(spawns.startTicket("1234abcd", from("s-pm")))
        assertEquals(TicketStartOutcome.ALREADY_IN_PROGRESS, out.outcome)
        assertEquals("s-run", out.worker?.id)
        assertEquals(emptyList(), starts)
    }

    @Test fun `queued, held and refused starts come back as outcomes`() {
        startAnswer = { t -> StartResult(t, ok = false, reason = "queued", detail = "role:backend") }
        ok(spawns.startTicket("1234abcd", from("s-pm"))).let { assertEquals(TicketStartOutcome.QUEUED, it.outcome); assertEquals("role:backend", it.lane) }
        startAnswer = { t -> StartResult(t, ok = false, reason = "start_refused", detail = "no profile") }
        ok(spawns.startTicket("1234abcd", from("s-pm"))).let { assertEquals(TicketStartOutcome.REFUSED, it.outcome); assertEquals("no profile", it.detail) }
    }

    @Test fun `kill scope - own spawns, a pm its own project, never itself, the owner any`() {
        assertEquals("s-w1", ok(spawns.mayKill("s-w1", from("s-pm"))).id, "its own spawn")
        assertEquals("s-other", ok(spawns.mayKill("s-other", from("s-pm"))).id, "a pm: its own project")
        assertTrue("a pm may only kill sessions in its own project" in refused(spawns.mayKill("s-b", from("s-pm"))))
        assertTrue("is this session itself" in refused(spawns.mayKill("s-pm", from("s-pm"))))
        val why = refused(spawns.mayKill("s-other", from("s-w1")))
        assertTrue(why.startsWith("session s-other was not spawned by this session (s-w1); a backend may only kill"), why)
        assertEquals("s-b", ok(spawns.mayKill("s-b", owner)).id, "the owner without a session")
        assertTrue("no session 'zz'" in assertIs<SessionsResult.Missing>(spawns.mayKill("zz", owner)).text)
    }

    @Test fun `fail closed - an agent without a session, or an unknown session`() {
        val agent = Caller(owner = false, agent = true)
        assertTrue("needs a session" in refused(spawns.spawn(SpawnRequest(role = "backend"), agent)))
        assertTrue("needs a session" in refused(spawns.mayKill("s-w1", agent)))
        assertTrue("unknown to this daemon" in refused(spawns.startTicket("1234abcd", from("s-ghost"))))
        assertTrue("unknown to this daemon" in refused(spawns.target("s-w1", from("s-ghost"))))
    }

    @Test fun `an owner connection's spawns are counted per client`() {
        limits[LimitRegistry.MCP_MAX_SPAWNS.key] = 1
        val one = SpawnNarrowing(clientKey = "mcp-1")
        ok(spawns.spawn(SpawnRequest(role = "backend"), owner, one))
        assertTrue(refused(spawns.spawn(SpawnRequest(role = "backend"), owner, one)).startsWith("concurrency limit. This connection already has 1"))
        ok(spawns.spawn(SpawnRequest(role = "backend"), owner, SpawnNarrowing(clientKey = "mcp-2")))
    }

    @Test fun `the policy gate and the grant`() {
        gate = "refused by preset"
        assertEquals("refused by preset", refused(spawns.spawn(SpawnRequest(role = "backend"), from("s-pm"))))
        assertEquals("refused by preset", refused(spawns.mayKill("s-w1", from("s-pm"))))
        gate = null
        val community = GrantedSpawns({ spawns }) { false }
        assertTrue("missing grant 'sessions.spawn'" in refused(community.startTicket("1234abcd", from("s-pm"))))
        assertNull(starts.firstOrNull())
        assertEquals("s-w1", ok(GrantedSpawns({ spawns }) { true }.target("s-w1", from("s-pm"))).id)
    }

    @Test fun `the pod cap applies under the spawn limits`() {
        limits[LimitRegistry.MAX_PER_POD.key] = 1
        limits[LimitRegistry.MCP_MAX_SPAWNS.key] = 5
        assertTrue(refused(spawns.spawn(SpawnRequest(role = "backend"), from("s-pm"))).startsWith(SpawnGuard.REFUSED_CAP))
    }
}
