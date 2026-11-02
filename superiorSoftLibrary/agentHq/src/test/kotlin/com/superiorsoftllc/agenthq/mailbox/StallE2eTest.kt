package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgNotify
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.RunPhaseOverlay
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.isBusy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STALL-2 (ticket a46744ec) item 3: the whole path from a job's end to the worker's next turn, per harness stub: the
 * watcher's notice goes through the real [MailRouter] into a real [SessionManager], and the worker is RESUMED with the
 * exit code and the output tail, whether it was idle, busy (waiting on something else) or compacting when the job ended.
 * Plus the daemon-restart case and the request count that proves the watcher, heartbeat and stall detector call no model.
 */
class StallE2eTest {
    /** A harness stub named like the real one; every [spec] is one harness invocation, i.e. one model request. */
    private class HarnessStub(override val id: String) : AgentProvider {
        val requests = AtomicInteger()
        override fun spec(request: RunRequest): ProcessSpec {
            requests.incrementAndGet()
            return ProcessSpec(listOf(id, request.resumeSessionId ?: "FRESH"))
        }
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-$id" else null
    }

    /** [ScriptedRunner] plus one assistant text line per turn, as every real harness prints (opencode fails a silent turn). */
    private class TalkingRunner(private val autoExit: Boolean) : ProcessRunner {
        override val id = "fake"
        val processes = mutableListOf<ScriptedProcess>()
        val current: ScriptedProcess? get() = processes.lastOrNull()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            val p = ScriptedProcess(listener)
            processes += p
            listener.onStdout("{\"session_id\":\"prov-${spec.command.first()}\"}")
            listener.onStdout("{\"type\":\"text\",\"text\":\"ok\"}")
            if (autoExit) listener.onExit(0)
            return p
        }
    }

    private class NoAgents : AgentRepository {
        override fun save(profile: AgentProfile, at: Long) = Unit
        override fun get(id: String): AgentProfile? = null
        override fun all(): List<AgentProfile> = emptyList()
        override fun delete(id: String) = false
    }

    private class Rig(harness: String, autoExit: Boolean, storeFile: File = tempSessionStoreFile(), pidAlive: (Long) -> Boolean = { true }) {
        val provider = HarnessStub(harness)
        val runner = TalkingRunner(autoExit)
        val manager = SessionManager(provider, runner, JsonSessionStore(storeFile), killGraceMs = 50, pidProbe = pidAlive, bindingPolicy = com.superiorsoftllc.agenthq.daemon.plugins.TicketBindingPolicy())
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val router = MailRouter(manager = manager, mailbox = mailbox, agentProfiles = AgentProfiles(NoAgents(), manager))
        var now = System.currentTimeMillis()
        val notifier = BgJobNotifier(
            jobs = { manager.list().flatMap { r -> r.bgJobs.map { r.id to it } } },
            send = router::send,
            defaultTailLines = { 20 },
            defaultTimeoutMs = { 15 * 60_000L },
            patternGraceMs = { 20_000L },
            clock = { now },
            inTurn = { sid -> manager.get(sid)?.isBusy() == true },
            pidAlive = pidAlive,
        )

        init {
            manager.eventSink = { e -> router.onSessionEvent(e) }
            manager.bgJobListener = notifier
        }

        fun job(id: String, log: File? = null, pid: Long = ProcessHandle.current().pid()) =
            BgJob(id, "./gradlew test", pid = pid, startedAt = now - 90_000, logPath = log?.absolutePath, notify = BgNotify())

        /** The prompt of the worker's newest turn. */
        fun lastPrompt() = runner.processes.last().input.toString()
    }

    private fun assertResumedWith(r: Rig, startsBefore: Int, exit: String, tail: String) {
        assertEquals(startsBefore + 1, r.runner.processes.size, "the worker was resumed exactly once")
        val prompt = r.lastPrompt()
        assertTrue(prompt.contains("finished, $exit"), prompt)
        assertTrue(prompt.contains("task finished: "), prompt) // BG-NATIVE-1: one line (id, exit, log); the tail is no longer sent
    }

    private val harnesses = listOf("claude", "opencode", "pi")

    @Test
    fun aJobEndingWhileTheWorkerIsIdle_resumesItWithExitCodeAndTail_perHarness() {
        for (h in harnesses) {
            val r = Rig(h, autoExit = true)
            val s = r.manager.startNew("work")
            assertEquals(SessionState.IDLE, r.manager.get(s.id)!!.state, "$h: ${r.manager.get(s.id)!!.lastError}")
            val job = r.job("j-$h")
            r.manager.bgJobBeat(s.id, job)
            val before = r.runner.processes.size
            r.manager.bgJobDone(s.id, job.id)
            r.notifier.done(s.id, job, 3, listOf("> Task :test FAILED", "BUILD FAILED in 41s"))
            assertResumedWith(r, before, "exit 3", "BUILD FAILED in 41s")
        }
    }

    @Test
    fun aJobEndingWhileTheWorkerIsBusyWaiting_isQueued_thenResumesItWhenTheTurnEnds_perHarness() {
        for (h in harnesses) {
            val r = Rig(h, autoExit = false)
            val s = r.manager.startNew("waiting on the gate")
            assertTrue(r.manager.get(s.id)!!.isBusy(), h)
            val job = r.job("j-$h")
            r.manager.bgJobBeat(s.id, job)
            r.manager.bgJobDone(s.id, job.id)
            val before = r.runner.processes.size
            r.notifier.done(s.id, job, 0, listOf("BUILD SUCCESSFUL in 3m"))
            assertEquals(before, r.runner.processes.size, "$h: never a second turn next to the one in flight")
            r.runner.current!!.exit(0)
            assertResumedWith(r, before, "exit 0", "BUILD SUCCESSFUL in 3m")
        }
    }

    @Test
    fun aJobEndingWhileTheWorkerIsCompacting_isQueued_thenResumesItAfterTheCompaction_perHarness() {
        for (h in harnesses) {
            val r = Rig(h, autoExit = false)
            val s = r.manager.startNew("long context")
            RunPhaseOverlay.set(s.id, "compacting")
            try {
                val job = r.job("j-$h")
                r.manager.bgJobBeat(s.id, job)
                r.manager.bgJobDone(s.id, job.id)
                val before = r.runner.processes.size
                r.notifier.done(s.id, job, 1, listOf("e: Unresolved reference: foo"))
                assertEquals(before, r.runner.processes.size, "$h: nothing is pushed into a compaction")
                RunPhaseOverlay.set(s.id, null)
                r.runner.current!!.exit(0)
                assertResumedWith(r, before, "exit 1", "e: Unresolved reference: foo")
            } finally {
                RunPhaseOverlay.set(s.id, null)
            }
        }
    }

    @Test
    fun daemonRestart_keepsTheWatch_orSaysExitUnknown_neverSilence() {
        val store = tempSessionStoreFile()
        var wrapperAlive = true
        val first = Rig("claude", autoExit = true, storeFile = store, pidAlive = { wrapperAlive })
        val s = first.manager.startNew("work")
        val log = File.createTempFile("stall-e2e", ".log").apply { deleteOnExit(); writeText("compiling\n") }
        val kept = first.job("kept", log, pid = 900_001)
        val lost = first.job("lost", File.createTempFile("stall-e2e", ".log").apply { deleteOnExit(); writeText("x\n") }, pid = 900_002)
        val exited = first.job("exited", File.createTempFile("stall-e2e", ".log").apply { deleteOnExit(); writeText("y\n") }, pid = 900_003)
        listOf(kept, lost, exited).forEach { first.manager.bgJobBeat(s.id, it) }
        File(exited.logPath!!.removeSuffix(".log") + ".exit").apply { deleteOnExit(); writeText("4") }

        // The daemon goes away; a new one loads the same store. The `kept` wrapper lives on, the other two are gone.
        val gone = setOf(900_002L, 900_003L)
        val second = Rig("claude", autoExit = true, storeFile = store, pidAlive = { it !in gone && wrapperAlive })
        second.manager.loadAndReconcile()
        assertEquals(setOf("kept", "lost", "exited"), second.manager.bgJobs(s.id).map { it.id }.toSet(), "watched jobs survive the restart")
        val before = second.runner.processes.size
        second.manager.expireBgJobs()
        assertEquals(listOf("kept"), second.manager.bgJobs(s.id).map { it.id }, "only the live runner's job is still watched")
        val prompts = second.runner.processes.drop(before).joinToString("\n---\n") { it.input.toString() }
        assertTrue(prompts.contains("finished, exit 4"), "the exit file's code is reported: $prompts")
        assertTrue(prompts.contains("exit unknown"), "no exit file: an honest unknown: $prompts")

        // The kept runner's own done reaches the new daemon and resumes the worker with the real exit code.
        val mid = second.runner.processes.size
        second.manager.bgJobDone(s.id, kept.id)
        second.notifier.done(s.id, kept, 0, null)
        assertResumedWith(second, mid, "exit 0", "compiling")
        wrapperAlive = false
    }

    @Test
    fun theWatcherHeartbeatAndStallDetector_makeNoModelRequest() {
        val r = Rig("claude", autoExit = false)
        val s = r.manager.startNew("long job", ticketId = "t1")
        val log = File.createTempFile("stall-e2e", ".log").apply { deleteOnExit(); writeText("started\n") }
        r.manager.bgJobBeat(s.id, r.job("quiet", log))
        val stall = StallDetector(
            r.manager, send = r.router::send,
            ticketInProgress = { true }, deliberateWait = { false }, lastJobEndedAt = { null }, quietSeconds = { null },
            stallMs = { 5 * 60_000L }, recipientOf = { null }, clock = { r.now },
        )
        val requests = r.provider.requests.get()
        val starts = r.runner.processes.size
        val meter = WatchMeter.counts()
        // Two simulated hours of scans: quiet notices, a growing heartbeat, stall checks, all against a busy worker.
        repeat(240) {
            r.now += 30_000L
            r.notifier.scanned()
            stall.tick()
        }
        assertEquals(requests, r.provider.requests.get(), "no harness request came from the watcher, heartbeat or stall detector")
        assertEquals(starts, r.runner.processes.size)
        val after = WatchMeter.counts()
        assertTrue(after.events + after.heartbeats > meter.events + meter.heartbeats, "they did work: $meter -> $after")
        assertTrue(r.mailbox.inbox(s.id).isNotEmpty(), "their notices wait in the mailbox for the worker's next turn")
    }

    private companion object {
        fun tempSessionStoreFile(): File = File.createTempFile("agenthq-stall-e2e", ".json").also { it.delete(); it.deleteOnExit() }
    }
}
