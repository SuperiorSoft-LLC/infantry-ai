// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIDGE-EVENTS-1 (was RestartResumeTest, RESUME-NOTIFY-1): core only DETECTS a cut turn and publishes it as `daemon.started`.
 * A session that was mid-turn when the daemon stopped is in cutTurns with who waits on it; a session at rest between turns is not;
 * a process that outlived the daemon is in orphaned, never cutTurns. The process (prompt, notice, attention item) is the shipped
 * script, tested in Infantry_Lua's ResumeAfterRestartScriptTest. A cut session can still be resumed on the same conversation,
 * which is what the script's infantry_prompt does.
 */
class DaemonStartedEventTest {

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-123" else null
    }

    private class FakeProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() { alive = false; listener.onExit(1) }
        override fun awaitExit(): Int = 0
        fun exit(code: Int) { alive = false; listener.onExit(code) }
    }

    private class FakeRunner : ProcessRunner {
        override val id = "fake"
        val specs = mutableListOf<ProcessSpec>()
        var process: FakeProcess? = null
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return FakeProcess(listener).also { process = it; listener.onStdout("{\"session_id\":\"prov-123\"}") }
        }
    }

    private fun storeFile(): File = File.createTempFile("infantry-restart-resume", ".json").also { it.delete() }

    /** The daemon that comes back: same store, the old child is gone. */
    private fun restarted(file: File, runner: FakeRunner = FakeRunner(), alive: Boolean = false) =
        SessionManager(FakeProvider(), runner, JsonSessionStore(file), killGraceMs = 50, pidProbe = { _: Long -> alive })

    private fun payload(report: ReconcileReport, manager: SessionManager, starter: (SessionRecord) -> String? = { null }): JsonObject =
        Json.parseToJsonElement(DaemonStartedEvent.payload(report, manager::get, starter)).jsonObject

    private fun ids(o: JsonObject, key: String) = o.getValue(key).jsonArray.map { it.jsonObject.getValue("sessionId").jsonPrimitive.content }

    @Test
    fun midTurnAtStop_isInCutTurns_withItsFields_andCanBeResumed() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        assertEquals(SessionState.BUSY, session.state, "the turn is in flight when the daemon stops")

        val runnerB = FakeRunner()
        val managerB = restarted(file, runnerB)
        val report = managerB.loadAndReconcile()
        assertEquals(listOf(session.id), report.cutTurns, "the cut turn is reported")

        val p = payload(report, managerB) { "pm-1" }
        assertEquals(listOf(session.id), ids(p, "cutTurns"))
        val cut = p.getValue("cutTurns").jsonArray.single().jsonObject
        assertEquals("pm-1", cut.getValue("starter").jsonPrimitive.content, "who waits on it")
        assertEquals(session.id, cut.getValue("sessionId").jsonPrimitive.content)
        assertTrue(ids(p, "orphaned").isEmpty())
        assertEquals(JsonArray(emptyList()), p.getValue("orphanedIds"))

        val resumed = managerB.resume(session.id, "Continue where you left off.")
        assertEquals(SessionState.BUSY, resumed.state, "the session runs again")
        assertEquals(listOf("fake", "prov-123"), runnerB.specs.last().command, "it continues the same conversation")
    }

    @Test
    fun sessionAtRestBetweenTurns_isNotInThePayload() {
        val file = storeFile()
        val runnerA = FakeRunner()
        val session = SessionManager(FakeProvider(), runnerA, JsonSessionStore(file), killGraceMs = 50).startNew("short turn")
        runnerA.process!!.exit(0)

        val managerB = restarted(file)
        val report = managerB.loadAndReconcile()
        assertTrue(report.cutTurns.isEmpty(), "an idle session's turn was not cut: ${report.cutTurns}")
        val p = payload(report, managerB)
        assertTrue(ids(p, "cutTurns").isEmpty() && ids(p, "orphaned").isEmpty(), "nothing to do for a session at rest: $p")
        assertEquals(SessionState.IDLE, managerB.get(session.id)!!.state)
    }

    @Test
    fun orphanStillRunning_isInOrphaned_neverCutTurns() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        val managerB = restarted(file, alive = true)
        val report = managerB.loadAndReconcile()
        assertTrue(report.cutTurns.isEmpty())
        assertEquals(listOf(session.id), report.orphanedIds)

        val p = payload(report, managerB)
        assertTrue(ids(p, "cutTurns").isEmpty(), "a process that outlived the daemon is never prompted twice")
        assertEquals(listOf(session.id), ids(p, "orphaned"))
        assertEquals(listOf(session.id), p.getValue("orphanedIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(SessionState.ORPHANED, managerB.get(session.id)!!.state)
    }

    @Test
    fun aSessionGoneFromTheStore_isLeftOut_andAStarterEqualToItselfIsDropped() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        val managerB = restarted(file)
        val report = managerB.loadAndReconcile()
        val p = payload(report.copy(cutTurns = report.cutTurns + "gone-1", orphanedIds = listOf("gone-2")), managerB) { it.id }
        assertEquals(listOf(session.id), ids(p, "cutTurns"))
        assertTrue("starter" !in p.getValue("cutTurns").jsonArray.single().jsonObject, "its own id is no starter")
        assertTrue(ids(p, "orphaned").isEmpty())
    }
}
