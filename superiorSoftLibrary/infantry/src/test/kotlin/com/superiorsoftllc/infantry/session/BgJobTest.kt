package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `infantry bg run`: the registered-job side of "running": register, heartbeat, deregister, expiry, and isRunning. */
class BgJobTest {
    private fun record() = SessionRecord(
        id = "s", provider = "fake", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE,
    )

    private class Store(private val initial: List<SessionRecord>) : SessionStore {
        override fun load() = initial
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }

    private object NoProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
    }

    private object NoRunner : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("no run expected")
    }

    private var now = 1_000L
    private val alive = mutableSetOf(77L)
    private fun manager() = managerRaw().also { it.loadAndReconcile() }
    private fun managerRaw() = SessionManager(
        NoProvider, NoRunner, Store(listOf(record())),
        pidProbe = { it in alive }, clock = { now }, backgroundPollMs = 0, idleSweepMs = 0,
    )
    private val job = BgJob("j1", "gate", 77L, 1_000L)

    @Test fun `a registered job alone makes the session running but not turn-busy`() {
        val r = record().copy(bgJobs = listOf(job))
        assertTrue(r.isRunning())
        assertFalse(r.isBusy())
        assertFalse(record().isRunning())
        val wire = Json.parseToJsonElement(Json.encodeToString(SessionRecordSerializer, r)).jsonObject
        assertEquals("BUSY", wire.getValue("state").jsonPrimitive.content)
    }

    @Test fun `register heartbeat and done`() {
        val m = manager()
        assertTrue(m.bgJobBeat("s", job))
        now = 5_000
        assertTrue(m.bgJobBeat("s", job))
        val jobs = m.get("s")!!.bgJobs
        assertEquals(1, jobs.size)
        assertEquals(5_000L, jobs.single().lastBeat)
        assertTrue(m.get("s")!!.isRunning())
        m.bgJobDone("s", "j1")
        assertFalse(m.get("s")!!.isRunning())
        assertFalse(m.bgJobBeat("nope", job))
    }

    @Test fun `a job expires when its pid is gone or its heartbeat goes quiet`() {
        val m = manager()
        m.bgJobBeat("s", job)
        m.expireBgJobs()
        assertTrue(m.get("s")!!.isRunning())
        alive.clear()
        m.expireBgJobs()
        assertFalse(m.get("s")!!.isRunning())
        alive += 77L
        m.bgJobBeat("s", job)
        now += 121_000
        m.expireBgJobs()
        assertFalse(m.get("s")!!.isRunning())
    }
}
