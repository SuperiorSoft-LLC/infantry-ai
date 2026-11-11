// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 3 gate, at the unit level: a session can be killed and resumed, its state is durable across
 * a daemon restart, and a child that outlived its daemon is reconciled as an orphan.
 *
 * The fakes are deliberate: the provider seam is already covered by Phase 2's ProviderSeamTest, and
 * the lifecycle is provider-neutral, so a vendor-neutral fake is the correct unit here. A live
 * opencode run is the integration follow-up (see README).
 */
class SessionLifecycleTest {

    /** The daemon's Json, so a served frame is encoded exactly as the daemon encodes it. */
    private val daemonJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tempStore(): Pair<JsonSessionStore, File> {
        val file = File.createTempFile("infantry-sessions", ".json")
        file.delete()
        return JsonSessionStore(file) to file
    }

    // ----- fakes -----

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) =
            ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) =
            if (line.contains("session_id")) "prov-123" else null
    }

    private class FakeProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        @Volatile var killed = false
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() {
            killed = true
            alive = false
            listener.onExit(1)
        }
        override fun awaitExit(): Int = 0

        /** The one-shot CLI finishes its turn on its own, as a real run does. */
        fun exit(code: Int) {
            alive = false
            listener.onExit(code)
        }
    }

    private class FakeRunner : ProcessRunner {
        override val id = "fake"
        val specs = mutableListOf<ProcessSpec>()
        var process: FakeProcess? = null
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            val p = FakeProcess(listener)
            process = p
            // A provider that surfaces a session id on its stream, as both real CLIs do.
            listener.onStdout("{\"session_id\":\"prov-123\"}")
            return p
        }
    }

    // ----- tests -----

    @Test
    fun startNew_reachesBusy_andCapturesProviderSessionId() {
        val (store, file) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(FakeProvider(), runner, store, killGraceMs = 50)

        val session = manager.startNew("hello")

        assertEquals(SessionState.BUSY, session.state)
        assertEquals("prov-123", manager.get(session.id)!!.providerSessionId)
        assertEquals(4242L, manager.get(session.id)!!.childPid)
        assertTrue(file.readText().contains(session.id), "session must be persisted")
    }

    @Test
    fun kill_terminatesRunsAndRecordsOutcome() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(FakeProvider(), runner, store, killGraceMs = 50)

        val session = manager.startNew("hello")
        val killed = manager.kill(session.id)

        assertEquals(SessionState.EXITED, killed.state)
        assertTrue(runner.process!!.killed, "kill must reach the process tree")
        assertNotNull(killed.killOutcome)
        assertEquals(null, killed.childPid)
    }

    /**
     * BUG-KILL-IDLE-1: a session whose turn finished (IDLE, no run in flight) must still END on kill. It used to be
     * returned unchanged, so a killed helper kept looking alive and kept its parent's pod slot in SpawnGuard.
     */
    @Test
    fun kill_ofAnIdleSession_marksItExited() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(FakeProvider(), runner, store, killGraceMs = 50)
        val session = manager.startNew("hello")
        runner.process!!.exit(0)
        val idle = manager.get(session.id)!!
        assertEquals(SessionState.IDLE, idle.state, "precondition: the turn ended cleanly and the session is at rest")
        assertEquals(1, idle.runCount)

        val killed = manager.kill(session.id)

        assertEquals(SessionState.EXITED, killed.state, "kill must resolve an idle session to a terminal state")
        assertEquals(KillOutcome.GRACEFUL, killed.killOutcome)
        assertEquals(SessionState.EXITED, manager.get(session.id)!!.state, "the stored record is ended too")
    }

    /** A session spawned with no task is IDLE from birth; kill ends it the same way. */
    @Test
    fun kill_ofASessionThatNeverRan_marksItExited() {
        val (store, _) = tempStore()
        val manager = SessionManager(FakeProvider(), FakeRunner(), store, killGraceMs = 50)
        val session = manager.startNew("")
        assertEquals(SessionState.IDLE, session.state)

        assertEquals(SessionState.EXITED, manager.kill(session.id).state)
    }

    /** Killing a session that has already ended is a no-op: its state, outcome and exit code are kept as they were. */
    @Test
    fun kill_ofAnEndedSession_leavesItUnchanged() {
        val (_, file) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(FakeProvider(), runner, JsonSessionStore(file), killGraceMs = 50)

        val exited = manager.startNew("hello").id
        manager.kill(exited, force = true)
        val exitedBefore = manager.get(exited)!!
        assertEquals(exitedBefore, manager.kill(exited), "a second kill of an EXITED session changes nothing")

        val failed = manager.startNew("hello").id
        runner.process!!.exit(3)
        val failedBefore = manager.get(failed)!!
        assertEquals(SessionState.FAILED, failedBefore.state, "precondition")
        assertEquals(failedBefore, manager.kill(failed), "a kill of a FAILED session keeps its failure")

        // ORPHANED: a new daemon over the same store, while the old child pid is still alive.
        val orphanId = manager.startNew("hello").id
        val reopened = SessionManager(
            FakeProvider(), FakeRunner(), JsonSessionStore(file),
            killGraceMs = 50, pidProbe = { _: Long -> true },
        )
        reopened.loadAndReconcile()
        val orphanBefore = reopened.get(orphanId)!!
        assertEquals(SessionState.ORPHANED, orphanBefore.state, "precondition")
        assertEquals(orphanBefore, reopened.kill(orphanId), "a kill of an ORPHANED session keeps it as reconciled")
    }

    @Test
    fun resume_refusesWhileStillRunning() {
        val (store, _) = tempStore()
        val manager = SessionManager(FakeProvider(), FakeRunner(), store, killGraceMs = 50)
        val session = manager.startNew("hello")
        assertFailsWith<IllegalStateException> { manager.resume(session.id, "next") }
    }

    @Test
    fun restart_reconcilesDeadChild_thenResumeKeepsContext() {
        val (_, file) = tempStore()
        val managerA = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50)
        val session = managerA.startNew("hello")

        // A new daemon (new daemonInstance) opens the same store; the child did NOT survive.
        val runnerB = FakeRunner()
        val managerB = SessionManager(
            FakeProvider(), runnerB, JsonSessionStore(file),
            killGraceMs = 50, pidProbe = { _: Long -> false },
        )
        val report = managerB.loadAndReconcile()
        assertEquals(1, report.exited)
        assertEquals(SessionState.EXITED, managerB.get(session.id)!!.state)

        val resumed = managerB.resume(session.id, "next turn")
        assertEquals(SessionState.BUSY, resumed.state)
        assertEquals(1, resumed.resumeCount)
        assertEquals("prov-123", resumed.providerSessionId)
        // Context intact = the CLI is re-invoked with the session id it gave us, not a fresh one.
        assertEquals(listOf("fake", "prov-123"), runnerB.specs.last().command)
    }

    /**
     * The restart above, with the one detail that made it flake in the wild: the previous daemon's
     * replacement of `sessions.json` was still in flight -- or was interrupted -- when the new daemon
     * opened the store, so the store file is momentarily GONE and the complete snapshot is only in the
     * `.tmp` it is moved from.
     *
     * That state is not contrived: on Windows the plain replace is `DeleteFile` then a file copy, and
     * a rename-replace leaves a momentarily stale negative directory entry -- both measured, see
     * [JsonSessionStoreTest]. A reader that lands in either window used to be told "no sessions", so
     * the new daemon started with nothing and the next `resume` answered with EXACTLY the message this
     * ticket was reported with: "unknown session <id>".
     */
    @Test
    fun restart_whenThePreviousWriteWasInterrupted_stillResumesTheSession() {
        val (_, file) = tempStore()
        val managerA = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50)
        val session = managerA.startNew("hello")

        // The replacement never landed: the store file is gone, its complete replacement is not.
        val replacement = File(file.parentFile, file.name + ".tmp")
        assertTrue(file.renameTo(replacement), "the store file must exist to be moved aside")

        val managerB = SessionManager(
            FakeProvider(), FakeRunner(), JsonSessionStore(file),
            killGraceMs = 50, pidProbe = { _: Long -> false },
        )
        val report = managerB.loadAndReconcile()

        // Then: the session is there to reconcile, and the resume keeps its context.
        assertEquals(
            1,
            report.exited,
            "the interrupted replacement held the only copy of the session and the restart loaded " +
                "nothing: a daemon that starts with zero sessions cannot resume any of them",
        )
        val resumed = managerB.resume(session.id, "next turn")
        assertEquals(1, resumed.resumeCount)
        assertEquals("prov-123", managerB.get(session.id)!!.providerSessionId)
    }

    @Test
    fun restart_flagsSurvivingChildAsOrphan() {
        val (_, file) = tempStore()
        val managerA = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50)
        val session = managerA.startNew("hello")

        // New daemon, but the child pid is still alive: it outlived the old daemon -> orphan.
        val managerB = SessionManager(
            FakeProvider(), FakeRunner(), JsonSessionStore(file),
            killGraceMs = 50, pidProbe = { _: Long -> true },
        )
        val report = managerB.loadAndReconcile()

        assertEquals(1, report.orphaned)
        assertEquals(SessionState.ORPHANED, managerB.get(session.id)!!.state)
    }

    // ----- agent-to-agent mail identity (AGENT-UX-13) -----

    /**
     * A spawned agent must be able to INITIATE a handoff to a colleague, not only answer one. That
     * needs two facts it cannot guess: the daemon's agent-facing mail base (in `childEnv`) and its
     * OWN session id, which is the `from` of anything it sends. The session id is per run, so the
     * manager adds it; without it an agent has no identity to address a colleague from.
     */
    @Test
    fun a_spawned_child_receives_its_mail_identity_in_the_environment() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(
            FakeProvider(), runner, store, killGraceMs = 50,
            childEnv = mapOf("INFANTRY_MAIL_BASE" to "http://127.0.0.1:8787"),
        )

        val session = manager.startNew("hello")
        val env = runner.specs.last().env

        assertEquals(
            session.id,
            env["INFANTRY_SESSION_ID"],
            "the child must know its own session id to send a handoff 'from' itself",
        )
        assertEquals(
            "http://127.0.0.1:8787",
            env["INFANTRY_MAIL_BASE"],
            "the child must know where the daemon's mail API is",
        )
    }

    /** Ticket 66153313: every run carries the build limits env, read per run (a settings change applies to the next run). */
    /** FIRST-RUN-KEYS-1: a harness's declared API key reaches its process in the declared env var, read at EVERY launch. */
    @Test
    fun a_launch_carries_the_harness_credential_read_at_each_launch() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        var key: String? = null
        val manager = SessionManager(
            FakeProvider(), runner, store, killGraceMs = 50,
            credentialEnv = { id -> if (id == FakeProvider().id && key != null) mapOf("FAKE_API_KEY" to key!!) else emptyMap() },
        )

        manager.startNew("first")
        assertNull(runner.specs.last().env["FAKE_API_KEY"], "no key set: nothing is injected")

        key = "sk-test-1234567890abcdef"
        manager.startNew("second")
        assertEquals("sk-test-1234567890abcdef", runner.specs.last().env["FAKE_API_KEY"])
    }

    /** FIRST-RUN-KEYS-1: a run whose harness declares an unset key says so in the chat. */
    @Test
    fun a_run_without_the_declared_key_says_the_key_is_missing() {
        val (store, _) = tempStore()
        val manager = SessionManager(
            FakeProvider(), FakeRunner(), store, killGraceMs = 50,
            credentialMissing = { _, _ -> "No API key set for fake" },
        )
        val events = mutableListOf<SessionEvent>()
        manager.eventSink = { events += it }
        manager.startNew("first")
        assertTrue(events.any { it is SessionEvent.Activity && it.detail.contains("No API key set") })
    }

    @Test
    fun a_spawned_child_receives_the_build_limits_env_read_per_run() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        var workers = 6
        val manager = SessionManager(
            FakeProvider(), runner, store, killGraceMs = 50,
            buildEnv = {
                com.superiorsoftllc.infantry.runner.BuildLimits(
                    com.superiorsoftllc.infantry.runner.BuildPriority.BELOW_NORMAL, workers, 0,
                ).sessionEnv()
            },
        )

        manager.startNew("hello")
        val env = runner.specs.last().env
        assertEquals("belownormal", env["INFANTRY_BUILD_PRIORITY"])
        assertEquals("6", env["INFANTRY_BUILD_MAX_WORKERS"])
        assertEquals(true, env["GRADLE_OPTS"]?.contains("-Dorg.gradle.workers.max=6"))

        workers = 3
        manager.startNew("again")
        assertEquals("3", runner.specs.last().env["INFANTRY_BUILD_MAX_WORKERS"])
    }

    @Test
    fun a_spawned_child_with_a_project_receives_its_project_id_in_the_environment() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(
            FakeProvider(), runner, store, killGraceMs = 50,
            childEnv = mapOf("INFANTRY_MAIL_BASE" to "http://127.0.0.1:8787"),
        )

        val session = manager.startNew("hello", projectId = "proj-xyz")
        val env = runner.specs.last().env

        assertEquals(
            "proj-xyz",
            env["INFANTRY_PROJECT_ID"],
            "a session with a project must pass it to the child so the child can roster that project",
        )
    }

    @Test
    fun a_spawned_child_without_a_project_omits_the_project_id_env_var() {
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(
            FakeProvider(), runner, store, killGraceMs = 50,
            childEnv = mapOf("INFANTRY_MAIL_BASE" to "http://127.0.0.1:8787"),
        )

        val session = manager.startNew("hello")
        val env = runner.specs.last().env

        assertNull(
            env["INFANTRY_PROJECT_ID"],
            "a session with no project must not set INFANTRY_PROJECT_ID",
        )
    }

    // ----- live property tests -----

    @Test
    fun live_idleSession_withNoChildPid_isFalse() {
        // Given: an idle session (no run in flight, childPid null)
        val record = SessionRecord(
            id = "test-idle",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.IDLE,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = 0,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be false
        assertFalse(record.live, "IDLE session with childPid=null should have live=false")
    }

    @Test
    fun live_startingSession_isTrue() {
        // Given: a session in STARTING state
        val record = SessionRecord(
            id = "test-starting",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.STARTING,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = null,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be true
        assertTrue(record.live, "STARTING session should have live=true")
    }

    @Test
    fun live_busySession_isTrue() {
        // Given: a session in BUSY state
        val record = SessionRecord(
            id = "test-busy",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.BUSY,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = null,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be true
        assertTrue(record.live, "BUSY session should have live=true")
    }

    @Test
    fun live_waitingInputSession_isTrue() {
        // Given: a session in WAITING_INPUT state
        val record = SessionRecord(
            id = "test-waiting",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.WAITING_INPUT,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = null,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be true
        assertTrue(record.live, "WAITING_INPUT session should have live=true")
    }

    @Test
    fun live_exitedSession_withChildPidNull_isFalse() {
        // Given: a session in EXITED state with childPid null
        val record = SessionRecord(
            id = "test-exited",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.EXITED,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = 1,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be false
        assertFalse(record.live, "EXITED session with childPid=null should have live=false")
    }

    @Test
    fun live_orphanedSession_isFalse() {
        // Given: a session in ORPHANED state
        val record = SessionRecord(
            id = "test-orphaned",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.ORPHANED,
            childPid = 4242L,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = null,
            killOutcome = null,
            lastError = null,
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be false
        assertFalse(record.live, "ORPHANED session should have live=false")
    }

    @Test
    fun live_failedSession_isFalse() {
        // Given: a session in FAILED state
        val record = SessionRecord(
            id = "test-failed",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.FAILED,
            childPid = null,
            runCount = 0,
            resumeCount = 0,
            lastExitCode = 1,
            killOutcome = null,
            lastError = "process crashed",
            worktreePath = null,
            worktreeBranch = null,
            repoRoot = null,
            worktreeBaseRef = null,
            model = null,
            agentId = null,
            role = null,
            ticketId = null,
            contextSources = emptyList(),
        )
        // Then: live should be false
        assertFalse(record.live, "FAILED session should have live=false")
    }

    // ----- live as served: through the manager, on the wire, and off disk -----

    @Test
    fun live_processRunningThroughManager_isTrue_andHistoryOnceItEnds() {
        // Given: a manager driving the fake process
        val (store, _) = tempStore()
        val runner = FakeRunner()
        val manager = SessionManager(FakeProvider(), runner, store, killGraceMs = 50)

        // When: a run is in flight and the process is alive
        val session = manager.startNew("hello")
        assertTrue(runner.process!!.isAlive, "the fake process must be running")
        assertEquals(SessionState.BUSY, session.state)
        assertTrue(session.live, "a session whose process is running is live")

        // When: that run ends
        val killed = manager.kill(session.id)
        assertEquals(SessionState.EXITED, killed.state)
        assertEquals(null, killed.childPid)
        assertFalse(killed.live, "the same session is history once its run has ended")
    }

    @Test
    fun live_servedSessionsFrame_carriesTheFlag() {
        // Given: one record whose process is running and one old record (IDLE, childPid null, exit 0)
        val (store, _) = tempStore()
        val manager = SessionManager(FakeProvider(), FakeRunner(), store, killGraceMs = 50)
        val running = manager.startNew("hello")
        val history = SessionRecord(
            id = "old-idle",
            provider = "test",
            workingDir = "",
            spawnedAt = System.currentTimeMillis(),
            lastHeartbeat = System.currentTimeMillis(),
            state = SessionState.IDLE,
            childPid = null,
            lastExitCode = 0,
        )

        // When: the daemon serves the `sessions` frame (the same serializer DaemonMain uses)
        val wire = daemonJson.encodeToString(
            ListSerializer(SessionRecordSerializer),
            listOf(history, running),
        )
        val rows: List<JsonObject> =
            daemonJson.parseToJsonElement(wire).jsonArray.map { it.jsonObject }
        val historyRow = rows.single { it["id"]?.jsonPrimitive?.content == "old-idle" }
        val runningRow = rows.single { it["id"]?.jsonPrimitive?.content == running.id }

        // Then: the served frame carries live=false for the old record and live=true for the
        // running one, and a client decodes the same frame unchanged
        assertEquals(
            false,
            historyRow["live"]?.jsonPrimitive?.content?.toBoolean()!!,
            "an IDLE record (childPid null, exit 0) must be served live=false",
        )
        assertEquals(
            true,
            runningRow["live"]?.jsonPrimitive?.content?.toBoolean()!!,
            "a record whose process is running must be served live=true",
        )
        val decoded = daemonJson.decodeFromString(
            ListSerializer(SessionRecord.serializer()),
            wire,
        )
        assertFalse(decoded.first { it.id == "old-idle" }.live, "a client reads the old record as history")
        assertTrue(decoded.first { it.id == running.id }.live, "a client reads the running record as live")
    }

    @Test
    fun live_legacySessionsJson_withoutTheField_decodesAsHistory() {
        // Given: a sessions.json written before this change -- no `live` key anywhere
        val (store, file) = tempStore()
        file.writeText(
            """{"version":1,"sessions":[{"id":"legacy-1","provider":"test","workingDir":"",""" +
                """"spawnedAt":0,"lastHeartbeat":0,"state":"IDLE","childPid":null,"lastExitCode":0}]}""",
        )

        // When: the store loads it
        val loaded = store.load().single { it.id == "legacy-1" }
        assertFalse(loaded.live, "an old sessions.json row decodes as history")

        // Then: the flag rides along when the store writes the set back
        store.saveAll(listOf(loaded))
        val written: JsonArray = Json.parseToJsonElement(file.readText()).jsonObject.getValue("sessions").jsonArray
        assertEquals(false, written.single().jsonObject["live"]?.jsonPrimitive?.content?.toBoolean()!!)
        assertFalse(store.load().single { it.id == "legacy-1" }.live)
    }
}
