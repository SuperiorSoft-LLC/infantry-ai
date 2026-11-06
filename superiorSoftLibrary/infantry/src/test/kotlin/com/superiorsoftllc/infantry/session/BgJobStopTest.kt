// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.host.ProcessTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** HARNESS-GAP G16: `bg stop` ends the job's recorded tree (an orphaned grandchild too) and checks every start time. */
class BgJobStopTest {
    private class Store : SessionStore {
        override fun load() = listOf(SessionRecord(id = "s", provider = "fake", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE))
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }
    private object P : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
    }
    private object R : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("no run expected")
    }
    private class FakeTable(val starts: Map<Long, Long>) : ProcessTable {
        val killed = mutableListOf<Long>()
        override fun startInstant(pid: Long) = starts[pid]
        override fun killTree(pid: Long, startedAt: Long) { if (starts[pid] == startedAt) killed += pid }
    }
    private fun manager() = SessionManager(P, R, Store(), pidProbe = { true }, clock = { 1_000L }, backgroundPollMs = 0, idleSweepMs = 0).also { it.loadAndReconcile() }

    @Test fun `stop kills the recorded orphan grandchild and the wrapper, not a recycled pid`() {
        val m = manager()
        // 10 = wrapper, 20 = grandchild (its parent already exited), 30 = recorded member whose pid was recycled
        m.bgJobBeat("s", BgJob("j1", "dev server", 10, 1_000, pidStart = 900, members = listOf(BgMember(20, 950), BgMember(30, 940))))
        val table = FakeTable(mapOf(10L to 900L, 20L to 950L, 30L to 5_000L))
        assertNotNull(m.bgJobStop("j1", table))
        assertEquals(listOf(20L, 10L), table.killed)
    }

    @Test fun `stop leaves a wrapper pid that started after the job`() {
        val m = manager()
        m.bgJobBeat("s", BgJob("j2", "x", 10, 1_000))
        val table = FakeTable(mapOf(10L to 9_000L))
        m.bgJobStop("j2", table)
        assertEquals(emptyList(), table.killed)
        assertNull(m.bgJobStop("nope", table))
    }
}
