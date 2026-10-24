package com.superiorsoftllc.agenthq.persistence

import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.FinishedPayload.Outcome
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.session.SessionState
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4.5 -- the repository seam, exercised the way Phase 5 will use it: pods own runs, runs own
 * an event log in the frozen public vocabulary, and a daemon crash is recovered on the next start.
 * Like RunsDatabaseTest, the important assertions sit on the far side of a close + reopen.
 */
class AgentHqStoreTest {

    private fun newDatabasePath(): String {
        val file = Files.createTempFile("agenthq-store-test-", ".db")
        file.toFile().delete()
        return file.toAbsolutePath().toString()
    }

    private fun pod(id: String = "pod-1") =
        PodRecord(id = id, goal = "ship phase 5", state = "running", maxConcurrency = 3, createdAt = 100)

    private fun run(id: String, podId: String? = "pod-1", session: String = "session-1") = RunRecord(
        id = id, sessionId = session, provider = "stub", workingDir = "C:/w/$id",
        prompt = "do $id", state = SessionState.STARTING, startedAt = 200, podId = podId,
    )

    private val status = RunEvent.Status(StatusPayload(SessionState.BUSY, "provisioning worktree"))
    private val finished = RunEvent.Finished(FinishedPayload(exitCode = 0, outcome = Outcome.SUCCEEDED))

    @Test
    fun `a pod, its runs and their events round-trip across a reopen`() {
        val path = newDatabasePath()

        SqlAgentHqStore.open(path).use { store ->
            store.pods.create(pod())
            store.runs.upsert(run("run-a"))
            store.runs.upsert(run("run-b"))
            store.events.append("run-a", status, 300)
            store.events.append("run-a", RunEvent.TextDelta("hello "), 301)
            store.events.append("run-a", RunEvent.TextDelta("world"), 302)
            store.events.append("run-a", RunEvent.Error("transient"), 303)
            store.events.append("run-a", finished, 304)
            store.runs.upsert(store.runs.get("run-a")!!.copy(state = SessionState.EXITED, endedAt = 305, exitCode = 0))
        }

        SqlAgentHqStore.open(path).use { store ->
            assertEquals(pod(), store.pods.get("pod-1"))
            assertEquals(listOf("run-a", "run-b"), store.runs.byPod("pod-1").map { it.id })

            val a = store.runs.get("run-a")!!
            assertEquals(SessionState.EXITED, a.state)
            assertEquals(0, a.exitCode)
            assertTrue(a.isFinished)

            val log = store.events.forRun("run-a")
            assertEquals(
                listOf(status, RunEvent.TextDelta("hello "), RunEvent.TextDelta("world"), RunEvent.Error("transient"), finished),
                log.map { it.event },
            )
            assertEquals(listOf("status", "text_delta", "text_delta", "error", "finished"), log.map { it.event.type })
            assertTrue(log.zipWithNext().all { (x, y) -> x.seq < y.seq }, "seq must be strictly increasing")
            // The column records the vocabulary the event was WRITTEN UNDER, so it tracks the published
            // version rather than being pinned to one: a 1.0.0 event written before this bump keeps
            // saying 1.0.0, and only new rows follow the new version. Asserted against the constant so
            // a future bump does not fail here for no reason; the published value itself is pinned to a
            // literal in StubDaemonContractTest and PodDaemonIntegrationTest.
            assertTrue(
                log.all { it.vocabularyVersion == EventVocabulary.VERSION },
                "every stored event must record the current vocabulary ${EventVocabulary.VERSION}, " +
                    "got ${log.map { it.vocabularyVersion }.distinct()}",
            )
            assertEquals(304L, log.last().createdAt)
            assertEquals(0L, store.events.count("run-b"))
        }
    }

    @Test
    fun `afterSeq is a resume cursor`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            store.pods.create(pod())
            store.runs.upsert(run("run-a"))
            val seqs = (1..4).map { store.events.append("run-a", RunEvent.TextDelta("t$it"), it.toLong()) }

            assertEquals(seqs.drop(2), store.events.forRun("run-a", afterSeq = seqs[1]).map { it.seq })
            assertTrue(store.events.forRun("run-a", afterSeq = seqs.last()).isEmpty())
        }
    }

    /** The regression story 1's `INSERT OR REPLACE` would have caused once events cascade. */
    @Test
    fun `re-upserting a run keeps its event log`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            store.pods.create(pod())
            store.runs.upsert(run("run-a"))
            store.events.append("run-a", status, 1)
            store.events.append("run-a", RunEvent.TextDelta("x"), 2)

            store.runs.upsert(run("run-a").copy(state = SessionState.BUSY, stdoutLines = 7))

            assertEquals(2L, store.events.count("run-a"))
            assertEquals(SessionState.BUSY, store.runs.get("run-a")!!.state)
            assertEquals(7L, store.runs.get("run-a")!!.stdoutLines)
            assertEquals(1, store.runs.byPod("pod-1").size)
        }
    }

    @Test
    fun `foreign keys are enforced - no event without a run, no run in a missing pod`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            assertFails { store.events.append("ghost-run", status, 1) }
            assertFails { store.runs.upsert(run("run-a", podId = "ghost-pod")) }
            assertNull(store.runs.get("run-a"))
            // A run outside any pod is legitimate (every Phase 3 session run).
            store.runs.upsert(run("run-solo", podId = null))
            assertNull(store.runs.get("run-solo")!!.podId)
        }
    }

    @Test
    fun `deletes cascade - run takes its events, pod takes its runs and their events`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            store.pods.create(pod("pod-1"))
            store.pods.create(pod("pod-2"))
            store.runs.upsert(run("run-a", "pod-1"))
            store.runs.upsert(run("run-b", "pod-1"))
            store.runs.upsert(run("run-c", "pod-2"))
            store.runs.upsert(run("run-solo", podId = null))
            for (id in listOf("run-a", "run-b", "run-c", "run-solo")) store.events.append(id, status, 1)

            store.runs.delete("run-a")
            assertEquals(0L, store.events.count("run-a"))
            assertEquals(1L, store.events.count("run-b"))

            store.pods.delete("pod-1")
            assertNull(store.pods.get("pod-1"))
            assertNull(store.runs.get("run-b"))
            assertEquals(0L, store.events.count("run-b"))

            // Nothing outside the deleted pod is touched.
            assertEquals(1L, store.events.count("run-c"))
            assertEquals(1L, store.events.count("run-solo"))
            assertEquals(listOf("pod-2"), store.pods.all().map { it.id })
        }
    }

    @Test
    fun `pod lifecycle - open pods, state updates, finish, duplicate id refused`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            store.pods.create(pod("pod-1"))
            store.pods.create(pod("pod-2"))
            assertFails { store.pods.create(pod("pod-1")) }

            store.pods.updateState("pod-1", "blocked")
            assertEquals("blocked", store.pods.get("pod-1")!!.state)

            store.pods.finish("pod-2", "shipped", 999)
            assertEquals(listOf("pod-1"), store.pods.open().map { it.id })
            assertEquals(999L, store.pods.get("pod-2")!!.endedAt)
        }
        assertFailsWith<IllegalArgumentException> { pod().copy(maxConcurrency = 0) }
    }

    @Test
    fun `a failed transaction writes nothing`() {
        SqlAgentHqStore.open(newDatabasePath()).use { store ->
            store.pods.create(pod())
            assertFails {
                store.transaction {
                    store.runs.upsert(run("run-a"))
                    store.events.append("run-a", status, 1)
                    error("crash mid-write")
                }
            }
            assertNull(store.runs.get("run-a"))
        }
    }

    /**
     * THE GATE: crash recovery against a REAL crash. A child JVM ([CrashingDaemonMain]) opens the
     * store, writes a finished run and a run still in flight, prints READY and blocks with the store
     * open; the test then kills it with destroyForcibly() (TerminateProcess on Windows, SIGKILL on
     * Unix) -- no close(), no shutdown hook, no checkpoint. The "restarted daemon" is this JVM.
     */
    /**
     * BRANCH-PER-TASK 1/3, step B1: a ticket's own branch and the commit it was cut from are stored, survive a
     * reopen, and a plain edit does not drop them. A ticket never started keeps both null.
     */
    @Test
    fun `a ticket's branch and base commit survive a reopen and an edit, and stay null until a start sets them`() {
        val path = newDatabasePath()
        val base = "0123456789abcdef0123456789abcdef01234567"

        SqlAgentHqStore.open(path).use { store ->
            store.tickets.create(
                TicketRow(id = "t-new", title = "Never started", status = "todo", position = 0, createdAt = 1, updatedAt = 1),
            )
            store.tickets.create(
                TicketRow(
                    id = "t-started", title = "Started", status = "in_progress", position = 0, createdAt = 1, updatedAt = 1,
                    branch = "ticket/t-started-started", base = base,
                ),
            )
        }

        SqlAgentHqStore.open(path).use { store ->
            assertNull(store.tickets.get("t-new")!!.branch)
            assertNull(store.tickets.get("t-new")!!.base)
            val started = store.tickets.get("t-started")!!
            assertEquals("ticket/t-started-started", started.branch, "the branch is stored, not dropped")
            assertEquals(base, started.base, "the base commit is stored, not dropped")
            store.tickets.update(started.copy(title = "Renamed", updatedAt = 2))
        }

        SqlAgentHqStore.open(path).use { store ->
            val renamed = store.tickets.get("t-started")!!
            assertEquals("Renamed", renamed.title)
            assertEquals("ticket/t-started-started", renamed.branch, "an edit keeps the branch")
            assertEquals(base, renamed.base, "an edit keeps the base")
        }
    }

    @Test
    fun `a daemon killed mid-run is recovered on the next open`() {
        val path = newDatabasePath()
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashingDaemonMain::class.java.name, path)
            .redirectErrorStream(true)
            .start()
        try {
            // stderr is merged so a child failure shows up in the message; skip noise lines
            // (e.g. SLF4J's "no providers" warning) until READY or EOF.
            val seen = mutableListOf<String>()
            val reader = child.inputReader()
            while (true) {
                val line = reader.readLine() ?: break
                seen += line
                if (line == "READY") break
            }
            assertEquals("READY", seen.lastOrNull(), "child daemon did not reach its crash point:\n" + seen.joinToString("\n"))
        } finally {
            child.destroyForcibly()
            child.waitFor()
        }

        SqlAgentHqStore.open(path).use { restarted ->
            // Every committed write survived the kill.
            assertEquals(listOf("run-live"), restarted.runs.unfinished().map { it.id })
            assertEquals(2L, restarted.events.count("run-live"))

            assertEquals(listOf("run-live"), restarted.recoverAfterCrash(now = 9_000))

            val live = restarted.runs.get("run-live")!!
            assertEquals(SessionState.ORPHANED, live.state)
            assertEquals(9_000L, live.endedAt)
            assertNull(live.exitCode, "an exit code nobody observed must not be invented")
            assertEquals(4L, live.stdoutLines)
            assertEquals("ses_1", live.providerSessionId, "the resume handle must survive recovery")
            assertEquals(SqlAgentHqStore.ORPHAN_DETAIL, live.error)

            val log = restarted.events.forRun("run-live").map { it.event }
            assertEquals(3, log.size)
            assertEquals(
                RunEvent.Finished(FinishedPayload(null, Outcome.ORPHANED, SqlAgentHqStore.ORPHAN_DETAIL)),
                log.last(),
            )

            // The run that had ended is untouched.
            val done = restarted.runs.get("run-done")!!
            assertEquals(SessionState.EXITED, done.state)
            assertEquals(260L, done.endedAt)
            assertEquals(1L, restarted.events.count("run-done"))

            // Idempotent.
            assertTrue(restarted.recoverAfterCrash(now = 10_000).isEmpty())
            assertEquals(3L, restarted.events.count("run-live"))
        }

        // ...and the recovery itself is durable.
        SqlAgentHqStore.open(path).use { again ->
            assertEquals(SessionState.ORPHANED, again.runs.get("run-live")!!.state)
            assertTrue(again.runs.unfinished().isEmpty())
        }
    }

    /**
     * BUG-FLAKE-2: agents.delete and tickets.delete read (does it exist?) then write. As a DEFERRED transaction the
     * read pins a WAL snapshot, so another connection's open write makes the upgrade fail at once with SQLITE_BUSY
     * (busy_timeout never runs) and the delete is lost. Each must take the write lock first and WAIT. Forced: the
     * other connection holds an open write transaction for the whole time the delete runs.
     */
    private fun deleteUnderConcurrentWriter(seed: (SqlAgentHqStore) -> Unit, delete: (SqlAgentHqStore) -> Boolean, gone: (SqlAgentHqStore) -> Boolean) {
        val path = newDatabasePath()
        SqlAgentHqStore.open(path).use { store ->
            SqlAgentHqStore.open(path).use { other ->
                seed(store)
                val holding = java.util.concurrent.CountDownLatch(1)
                var writerFailure: Throwable? = null
                val writer = Thread {
                    try {
                        other.writeTransaction {
                            other.pods.create(pod("pod-writer"))
                            holding.countDown()
                            Thread.sleep(400) // the delete starts while this write lock is held
                        }
                    } catch (t: Throwable) {
                        writerFailure = t
                        holding.countDown()
                    }
                }
                writer.start()
                assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "the competing writer never took the lock")

                val deleted = runCatching { delete(store) }
                writer.join(10_000)

                assertNull(writerFailure, "the competing writer commits: $writerFailure")
                assertNull(deleted.exceptionOrNull(), "a concurrent writer must not abort the delete: ${deleted.exceptionOrNull()}")
                assertEquals(true, deleted.getOrNull(), "the row existed")
                assertEquals(pod("pod-writer"), store.pods.get("pod-writer"), "the other write landed too")
                assertTrue(gone(store), "the row is gone")
            }
        }
    }

    @Test
    fun `an agent delete waits for a concurrent writer instead of failing`() {
        deleteUnderConcurrentWriter(
            seed = { it.agents.save(com.superiorsoftllc.agenthq.protocol.AgentProfile(id = "agent-1", name = "A", role = "dev"), 100) },
            delete = { it.agents.delete("agent-1") },
            gone = { it.agents.get("agent-1") == null },
        )
    }

    @Test
    fun `a ticket delete waits for a concurrent writer instead of failing`() {
        deleteUnderConcurrentWriter(
            seed = { it.tickets.create(TicketRow(id = "t1", title = "T", status = "todo", position = 0, createdAt = 1, updatedAt = 1)) },
            delete = { it.tickets.delete("t1") },
            gone = { it.tickets.get("t1") == null },
        )
    }

    @Test
    fun `a trigger delete waits for a concurrent writer instead of failing`() {
        // BUG-FLAKE-1: the deferred read-then-write delete failed at once with SQLITE_BUSY under a concurrent writer.
        deleteUnderConcurrentWriter(
            seed = {
                it.triggers.upsert(
                    com.superiorsoftllc.agenthq.protocol.TriggerView(
                        id = "trg-1", name = "Pull next", kind = "manual",
                        action = com.superiorsoftllc.agenthq.protocol.TriggerAction(), enabled = true, state = "armed",
                        coalesceMs = 0, ceilingRuns = 6, ceilingWindowMs = 60_000, createdAt = 100,
                    ),
                    null,
                )
            },
            delete = { it.triggers.delete("trg-1") },
            gone = { it.triggers.get("trg-1") == null },
        )
    }

    @Test
    fun `a trigger upsert waits for a concurrent writer instead of failing`() {
        // ticket a8a6fd98: upsert reads the row's pending mail id, then writes it back. As a deferred transaction it
        // failed at once with SQLITE_BUSY while the board committed the same run's end, so a finished run's
        // `running -> armed` was lost and the trigger stayed `running` (DaemonIntegrationTest's trigger test).
        val trigger = com.superiorsoftllc.agenthq.protocol.TriggerView(
            id = "trg-1", name = "Pull next", kind = "manual",
            action = com.superiorsoftllc.agenthq.protocol.TriggerAction(), enabled = true, state = "running",
            coalesceMs = 0, ceilingRuns = 6, ceilingWindowMs = 60_000, createdAt = 100,
        )
        deleteUnderConcurrentWriter(
            seed = { it.triggers.upsert(trigger, null) },
            delete = { it.triggers.upsert(trigger.copy(state = "armed"), null); true },
            gone = { it.triggers.get("trg-1")?.state == "armed" },
        )
    }
}

/**
 * The child process of the crash test: the daemon that dies. Writes, signals READY, then blocks
 * forever with the store open until the test kills it.
 */
object CrashingDaemonMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SqlAgentHqStore.open(args[0])
        store.pods.create(PodRecord(id = "pod-1", goal = "g", state = "running", createdAt = 100))
        val base = RunRecord(
            id = "run-done", sessionId = "session-1", provider = "stub", workingDir = "C:/w",
            prompt = "p", state = SessionState.STARTING, startedAt = 200, podId = "pod-1",
        )
        store.runs.upsert(base)
        store.events.append("run-done", RunEvent.Finished(FinishedPayload(0, Outcome.SUCCEEDED)), 250)
        store.runs.upsert(base.copy(state = SessionState.EXITED, endedAt = 260, exitCode = 0))

        store.runs.upsert(base.copy(id = "run-live", state = SessionState.BUSY, stdoutLines = 4, providerSessionId = "ses_1"))
        store.events.append("run-live", RunEvent.Status(StatusPayload(SessionState.BUSY, "working")), 300)
        store.events.append("run-live", RunEvent.TextDelta("half an ans"), 301)

        println("READY")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
