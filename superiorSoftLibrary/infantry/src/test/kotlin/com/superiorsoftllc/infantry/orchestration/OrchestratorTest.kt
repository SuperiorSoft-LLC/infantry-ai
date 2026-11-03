// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.persistence.NodeRecord
import com.superiorsoftllc.infantry.persistence.RunEvent
import com.superiorsoftllc.infantry.persistence.RunRecord
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.FinishedPayload.Outcome
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrchestratorTest {

    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        toClose.asReversed().forEach { runCatching { it.close() } }
        toClose.clear()
    }

    private class Rig(
        val dir: File,
        val store: SqlInfantryStore,
        val runner: GatedRunner,
        val manager: SessionManager,
        val orch: Orchestrator,
    ) {
        fun node(podId: String, name: String): NodeRecord = store.nodes.byPod(podId).single { it.name == name }

        fun runsOf(podId: String, name: String): List<RunRecord> =
            store.nodes.attempts(node(podId, name).id).map { store.runs.get(it.runId)!! }

        fun lastOutcome(run: RunRecord): Outcome =
            (store.events.forRun(run.id).last().event as RunEvent.Finished).payload.outcome

        fun assertNoFailures() = assertTrue(orch.failures.isEmpty(), "orchestrator thread threw: ${orch.failures}")
    }

    private fun rig(holdMs: Long = 100, dir: File = Files.createTempDirectory("infantry-orch-").toFile()): Rig {
        val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
        val runner = GatedRunner(holdMs)
        val manager = SessionManager(
            StubProvider(), runner, JsonSessionStore(File(dir, "sessions-${System.nanoTime()}.json")),
            killGraceMs = 200, defaultWorkingDir = dir,
        )
        val orch = Orchestrator(store, manager)
        manager.eventSink = orch::onSessionEvent
        toClose += store
        toClose += orch // closed first (reverse order)
        return Rig(dir, store, runner, manager, orch)
    }

    private fun await(timeoutMs: Long = 10_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("not met within ${timeoutMs}ms: $what")
    }

    /** Waits until each named node has a live attempt; returns name -> sessionId. */
    private fun awaitSessions(r: Rig, podId: String, vararg names: String): Map<String, String> {
        var found: Map<String, String> = emptyMap()
        await(what = "live attempts for ${names.toList()}") {
            val status = r.orch.status(podId)!!
            found = status.nodes
                .filter { it.name in names && it.state == NodeState.RUNNING.name }
                .mapNotNull { n -> status.runs[n.id]?.lastOrNull()?.let { n.name to it.sessionId } }
                .toMap()
            found.size == names.size && r.runner.live.get() >= names.size
        }
        return found
    }

    private fun markers(prompt: String) = Regex(Regex.escape(RetryFeedback.MARKER)).findAll(prompt).count()

    // 1 -- THE GATE
    @Test
    fun `a pod fans out to N runs and converges`() {
        val r = rig(holdMs = 300)
        val pod = r.orch.submit(
            PodSpec(
                goal = "fan out and converge",
                maxConcurrency = 4,
                nodes = listOf(
                    NodeSpec("a", "task a"),
                    NodeSpec("b", "task b"),
                    NodeSpec("c", "task c"),
                    NodeSpec("merge", "merge a b c", dependsOn = setOf("a", "b", "c")),
                ),
            ),
        )
        assertEquals(PodState.RUNNING.name, pod.state)

        val done = r.orch.awaitTerminal(pod.id, 15_000)
        assertEquals(PodState.CONVERGED.name, done.state)
        assertNotNull(done.endedAt)

        for (name in listOf("a", "b", "c", "merge")) {
            val node = r.node(pod.id, name)
            assertEquals(NodeState.SUCCEEDED.name, node.state, name)
            assertEquals(1, node.attempts, name)
            assertNull(node.detail, name)
        }
        assertEquals(3, r.runner.maxLive.get(), "the three branches must have been live at the same time")
        assertEquals(4, r.runner.processes.size)

        val merge = r.runsOf(pod.id, "merge").single()
        for (branch in listOf("a", "b", "c")) {
            val b = r.runsOf(pod.id, branch).single()
            assertTrue(merge.startedAt >= b.endedAt!!, "merge must start only after $branch ended")
        }
        for (name in listOf("a", "b", "c", "merge")) {
            val run = r.runsOf(pod.id, name).single()
            assertEquals(SessionState.IDLE, run.state)
            assertEquals(0, run.exitCode)
            assertEquals(pod.id, run.podId)
            assertEquals(Outcome.SUCCEEDED, r.lastOutcome(run))
            val log = r.store.events.forRun(run.id)
            assertTrue(log.any { it.event is RunEvent.TextDelta }, "normalised text must be stored for $name")
            assertEquals(1, log.count { it.event is RunEvent.Finished }, "exactly one finished event for $name")
        }

        val queue = r.orch.reviewQueue()
        assertEquals(setOf("a", "b", "c", "merge"), queue.map { it.nodeName }.toSet())
        assertTrue(queue.all { it.podId == pod.id && it.podGoal == "fan out and converge" })
        r.assertNoFailures()
    }

    // 2
    @Test
    fun `the concurrency cap actually caps - observed max equals the cap`() {
        val r = rig(holdMs = 150)
        val pod = r.orch.submit(
            PodSpec(goal = "cap 2", maxConcurrency = 2, nodes = (1..6).map { NodeSpec("n$it", "independent $it") }),
        )
        assertEquals(PodState.CONVERGED.name, r.orch.awaitTerminal(pod.id, 15_000).state)
        assertEquals(6, r.runner.processes.size)
        assertEquals(2, r.runner.maxLive.get(), "observed max in flight must EQUAL the cap, not merely be <= it")
        r.assertNoFailures()

        val serial = rig(holdMs = 50)
        val one = serial.orch.submit(
            PodSpec(goal = "cap 1", maxConcurrency = 1, nodes = (1..3).map { NodeSpec("s$it", "serial $it") }),
        )
        assertEquals(PodState.CONVERGED.name, serial.orch.awaitTerminal(one.id, 15_000).state)
        assertEquals(1, serial.runner.maxLive.get())
        assertEquals(listOf("serial 1", "serial 2", "serial 3"), serial.runner.prompts.toList(), "ready ties break by spec order")
        serial.assertNoFailures()
    }

    // 3
    @Test
    fun `dependencies order the runs, not the order of the spec list`() {
        val r = rig(holdMs = 50)
        val chain = r.orch.submit(
            PodSpec(
                goal = "chain", maxConcurrency = 3,
                nodes = listOf(
                    NodeSpec("c", "step c", setOf("b")),
                    NodeSpec("b", "step b", setOf("a")),
                    NodeSpec("a", "step a"),
                ),
            ),
        )
        assertEquals(PodState.CONVERGED.name, r.orch.awaitTerminal(chain.id, 15_000).state)
        assertEquals(listOf("step a", "step b", "step c"), r.runner.prompts.toList())
        assertEquals(1, r.runner.maxLive.get(), "a chain has no parallelism even with spare slots")
        val a = r.runsOf(chain.id, "a").single()
        val b = r.runsOf(chain.id, "b").single()
        val c = r.runsOf(chain.id, "c").single()
        assertTrue(b.startedAt >= a.endedAt!!)
        assertTrue(c.startedAt >= b.endedAt!!)
        r.assertNoFailures()

        val d = rig(holdMs = 200)
        val diamond = d.orch.submit(
            PodSpec(
                goal = "diamond", maxConcurrency = 4,
                nodes = listOf(
                    NodeSpec("top", "top"),
                    NodeSpec("left", "left", setOf("top")),
                    NodeSpec("right", "right", setOf("top")),
                    NodeSpec("bottom", "bottom", setOf("left", "right")),
                ),
            ),
        )
        assertEquals(PodState.CONVERGED.name, d.orch.awaitTerminal(diamond.id, 15_000).state)
        val order = d.runner.prompts.toList()
        assertEquals("top", order.first())
        assertEquals("bottom", order.last())
        assertEquals(setOf("left", "right"), order.subList(1, 3).toSet())
        assertEquals(2, d.runner.maxLive.get(), "left and right run together")
        d.assertNoFailures()
    }

    // 4
    @Test
    fun `a cycle is rejected before anything is written or spawned`() {
        val r = rig()
        val e = assertFailsWith<InvalidPodException> {
            r.orch.submit(
                PodSpec(
                    goal = "cyclic",
                    nodes = listOf(
                        NodeSpec("a", "x", setOf("c")),
                        NodeSpec("b", "x", setOf("a")),
                        NodeSpec("c", "x", setOf("b")),
                        NodeSpec("free", "x"),
                    ),
                ),
            )
        }
        assertEquals("dependency cycle; unschedulable nodes: a, b, c", e.message)
        assertTrue(r.store.pods.all().isEmpty(), "a rejected pod leaves no row")
        assertTrue(r.runner.processes.isEmpty(), "a rejected pod spawns nothing")
        r.assertNoFailures()
    }

    // 5
    @Test
    fun `a failed run is retried with feedback and then succeeds`() {
        val r = rig(holdMs = 50)
        val pod = r.orch.submit(
            PodSpec(goal = "flaky", nodes = listOf(NodeSpec("flaky", "do it FAIL_ONCE", maxAttempts = 3))),
        )
        assertEquals(PodState.CONVERGED.name, r.orch.awaitTerminal(pod.id, 15_000).state)

        val node = r.node(pod.id, "flaky")
        assertEquals(NodeState.SUCCEEDED.name, node.state)
        assertEquals(2, node.attempts)

        val (first, second) = r.runsOf(pod.id, "flaky")
        assertEquals(SessionState.FAILED, first.state)
        assertEquals(3, first.exitCode)
        assertEquals(Outcome.FAILED, r.lastOutcome(first))
        assertTrue(
            r.store.events.forRun(first.id).any { (it.event as? RunEvent.Error)?.message == "gated failure #1" },
            "the provider's error line must be stored as an error event",
        )
        assertEquals(Outcome.SUCCEEDED, r.lastOutcome(second))
        assertTrue(first.sessionId != second.sessionId, "a retry is a NEW session, never a resume")

        val delivered = r.runner.prompts.toList()
        assertEquals(2, delivered.size)
        assertEquals("do it FAIL_ONCE", delivered[0])
        assertEquals(0, markers(delivered[0]))
        assertTrue(delivered[1].startsWith("do it FAIL_ONCE\n\n---\n"), delivered[1])
        assertEquals(1, markers(delivered[1]))
        assertTrue(delivered[1].contains("(attempt 2 of 3)"), delivered[1])
        assertTrue(delivered[1].contains("\nexit code: 3\n"), delivered[1])
        assertTrue(delivered[1].contains("\n- gated failure #1\n"), delivered[1])
        assertEquals(delivered, listOf(first.prompt, second.prompt), "the stored prompt is the delivered prompt")
        r.assertNoFailures()
    }

    // 6
    @Test
    fun `retries exhaust - the node fails, its dependents are skipped, an independent branch still finishes`() {
        val r = rig(holdMs = 50)
        val pod = r.orch.submit(
            PodSpec(
                goal = "exhaust", maxConcurrency = 2,
                nodes = listOf(
                    NodeSpec("doomed", "always FAIL_ALWAYS", maxAttempts = 3),
                    NodeSpec("after", "never runs", dependsOn = setOf("doomed")),
                    NodeSpec("independent", "unrelated work"),
                ),
            ),
        )
        assertEquals(PodState.FAILED.name, r.orch.awaitTerminal(pod.id, 15_000).state)

        val doomed = r.node(pod.id, "doomed")
        assertEquals(NodeState.FAILED.name, doomed.state)
        assertEquals(3, doomed.attempts)
        assertEquals("attempts exhausted (3/3)", doomed.detail)

        val after = r.node(pod.id, "after")
        assertEquals(NodeState.SKIPPED.name, after.state)
        assertEquals(0, after.attempts)
        assertEquals("upstream 'doomed' FAILED", after.detail)
        assertNotNull(after.endedAt)

        assertEquals(NodeState.SUCCEEDED.name, r.node(pod.id, "independent").state)

        val doomedRuns = r.runsOf(pod.id, "doomed")
        assertEquals(3, doomedRuns.size)
        assertTrue(doomedRuns.all { it.exitCode == 3 && r.lastOutcome(it) == Outcome.FAILED })
        val doomedPrompts = r.runner.prompts.filter { it.startsWith("always FAIL_ALWAYS") }
        assertEquals(doomedPrompts, doomedRuns.map { it.prompt })
        assertEquals(listOf(0, 1, 1), doomedPrompts.map { markers(it) }, "feedback never nests")
        assertTrue(doomedPrompts[2].contains("(attempt 3 of 3)"))
        assertTrue(r.runner.prompts.none { it.startsWith("never runs") })
        assertEquals(4, r.runner.processes.size)
        assertTrue(r.orch.reviewQueue().isEmpty(), "a pod that did not converge lands nothing in review")
        r.assertNoFailures()
    }

    // 7
    @Test
    fun `a cancelled pod schedules nothing further`() {
        val r = rig()
        val pod = r.orch.submit(
            PodSpec(
                goal = "cancel me", maxConcurrency = 2,
                nodes = listOf(
                    NodeSpec("hang1", "HANG one", maxAttempts = 3),
                    NodeSpec("hang2", "HANG two", maxAttempts = 3),
                    NodeSpec("later", "after hang1", setOf("hang1")),
                    NodeSpec("queued", "waiting for a slot"),
                ),
            ),
        )
        awaitSessions(r, pod.id, "hang1", "hang2")

        r.orch.cancelPod(pod.id, force = true)
        val done = r.orch.awaitTerminal(pod.id, 10_000)
        assertEquals(PodState.CANCELLED.name, done.state)

        for (name in listOf("hang1", "hang2")) {
            val node = r.node(pod.id, name)
            assertEquals(NodeState.CANCELLED.name, node.state, name)
            assertEquals(1, node.attempts, name)
            assertEquals("killed (forced)", node.detail, name)
            assertEquals(Outcome.KILLED_FORCED, r.lastOutcome(r.runsOf(pod.id, name).single()))
        }
        for (name in listOf("later", "queued")) {
            val node = r.node(pod.id, name)
            assertEquals(NodeState.CANCELLED.name, node.state, name)
            assertEquals(0, node.attempts, name)
            assertEquals("pod cancelled before start", node.detail, name)
        }
        Thread.sleep(500)
        assertEquals(2, r.runner.processes.size, "nothing may start after cancelPod")
        assertTrue(r.orch.reviewQueue().isEmpty())
        // Cancelling a terminal pod is a no-op, not an error.
        assertEquals(PodState.CANCELLED.name, r.orch.cancelPod(pod.id).state)
        r.assertNoFailures()
    }

    // 8
    @Test
    fun `a killed run is never retried - a graceful exit 0 is not success, a forced exit 137 is not failure`() {
        val r = rig()
        val pod = r.orch.submit(
            PodSpec(
                goal = "external kill", maxConcurrency = 2,
                nodes = listOf(
                    NodeSpec("soft", "HANG GRACEFUL", maxAttempts = 3),
                    NodeSpec("hard", "HANG", maxAttempts = 3),
                ),
            ),
        )
        val sessions = awaitSessions(r, pod.id, "soft", "hard")

        // Killed through SessionManager directly -- exactly what a client's `kill` frame does --
        // so this proves the orchestrator reads killOutcome rather than remembering its own request.
        r.manager.kill(sessions.getValue("soft"))
        r.manager.kill(sessions.getValue("hard"))

        assertEquals(PodState.FAILED.name, r.orch.awaitTerminal(pod.id, 10_000).state, "not converged, not pod-cancelled")

        val soft = r.node(pod.id, "soft")
        assertEquals(NodeState.CANCELLED.name, soft.state)
        assertEquals(1, soft.attempts)
        assertEquals("killed (graceful)", soft.detail)
        val softRun = r.runsOf(pod.id, "soft").single()
        assertEquals(0, softRun.exitCode, "the graceful kill exited 0 ...")
        assertEquals(Outcome.KILLED_GRACEFUL, r.lastOutcome(softRun), "... and is still not a success")
        assertEquals(SessionState.EXITED, softRun.state)

        val hard = r.node(pod.id, "hard")
        assertEquals(NodeState.CANCELLED.name, hard.state)
        assertEquals(1, hard.attempts)
        assertEquals("killed (forced)", hard.detail)
        val hardRun = r.runsOf(pod.id, "hard").single()
        assertEquals(137, hardRun.exitCode, "the forced kill exited non-zero ...")
        assertEquals(Outcome.KILLED_FORCED, r.lastOutcome(hardRun), "... and is still not a retryable failure")

        Thread.sleep(500)
        assertEquals(2, r.runner.processes.size, "a killed run must never be retried")
        r.assertNoFailures()
    }

    // 9
    @Test
    fun `cancelNode cancels one branch, skips its dependents, and the rest still runs`() {
        val r = rig(holdMs = 50)
        val pod = r.orch.submit(
            PodSpec(
                goal = "cancel one", maxConcurrency = 2,
                nodes = listOf(
                    NodeSpec("slow", "HANG slow", maxAttempts = 3),
                    NodeSpec("dependent", "needs slow", setOf("slow")),
                    NodeSpec("other", "fine"),
                ),
            ),
        )
        awaitSessions(r, pod.id, "slow")
        val slowId = r.orch.status(pod.id)!!.nodes.single { it.name == "slow" }.id
        r.orch.cancelNode(slowId, force = true)

        assertEquals(PodState.FAILED.name, r.orch.awaitTerminal(pod.id, 10_000).state)
        assertEquals("killed (forced)", r.node(pod.id, "slow").detail)
        assertEquals(NodeState.CANCELLED.name, r.node(pod.id, "slow").state)
        assertEquals(NodeState.SKIPPED.name, r.node(pod.id, "dependent").state)
        assertEquals("upstream 'slow' CANCELLED", r.node(pod.id, "dependent").detail)
        assertEquals(NodeState.SUCCEEDED.name, r.node(pod.id, "other").state)
        assertEquals(2, r.runner.processes.size)

        // cancelNode on a node that has not started yet
        val r2 = rig()
        val pod2 = r2.orch.submit(
            PodSpec(goal = "cancel pending", nodes = listOf(NodeSpec("gate", "HANG gate"), NodeSpec("waiting", "w", setOf("gate")))),
        )
        awaitSessions(r2, pod2.id, "gate")
        val ids = r2.orch.status(pod2.id)!!.nodes.associate { it.name to it.id }
        val waiting = r2.orch.cancelNode(ids.getValue("waiting"))
        assertEquals(NodeState.CANCELLED.name, waiting.state)
        assertEquals("cancelled before start", waiting.detail)
        r2.orch.cancelNode(ids.getValue("gate"), force = true)
        assertEquals(PodState.FAILED.name, r2.orch.awaitTerminal(pod2.id, 10_000).state)
        assertEquals(1, r2.runner.processes.size)
        r.assertNoFailures()
        r2.assertNoFailures()
    }

    // 10
    @Test
    fun `the review queue is filled by convergence and drained by review decisions`() {
        val r = rig(holdMs = 30)
        val pod = r.orch.submit(PodSpec(goal = "review me", maxConcurrency = 2, nodes = listOf(NodeSpec("a", "a"), NodeSpec("b", "b"))))
        assertEquals(PodState.CONVERGED.name, r.orch.awaitTerminal(pod.id, 10_000).state)

        val queue = r.orch.reviewQueue()
        assertEquals(listOf("a", "b"), queue.map { it.nodeName }.sorted())
        val itemA = queue.single { it.nodeName == "a" }
        val runA = r.runsOf(pod.id, "a").last()
        assertEquals(runA.id, itemA.runId)
        assertEquals(runA.sessionId, itemA.sessionId)
        assertEquals(runA.workingDir, itemA.workingDir)
        assertNull(itemA.worktreeBranch, "no repo was given, so no worktree")

        val ids = queue.associate { it.nodeName to it.nodeId }
        assertEquals(ReviewState.APPROVED.name, r.orch.review(ids.getValue("a"), ReviewState.APPROVED).reviewState)
        assertEquals(listOf("b"), r.orch.reviewQueue().map { it.nodeName })
        assertFailsWith<IllegalStateException> { r.orch.review(ids.getValue("a"), ReviewState.REJECTED) }
        assertFailsWith<IllegalArgumentException> { r.orch.review(ids.getValue("b"), ReviewState.PENDING) }
        r.orch.review(ids.getValue("b"), ReviewState.REJECTED)
        assertTrue(r.orch.reviewQueue().isEmpty())

        val failed = r.orch.submit(PodSpec(goal = "no review", nodes = listOf(NodeSpec("x", "FAIL_ALWAYS"))))
        assertEquals(PodState.FAILED.name, r.orch.awaitTerminal(failed.id, 10_000).state)
        assertTrue(r.orch.reviewQueue().isEmpty())
        val xId = r.orch.status(failed.id)!!.nodes.single().id
        assertFailsWith<IllegalStateException> { r.orch.review(xId, ReviewState.APPROVED) }
        r.assertNoFailures()
    }

    // 11
    @Test
    fun `after a daemon crash the open pod is INTERRUPTED and nothing is rescheduled`() {
        val dir = Files.createTempDirectory("infantry-orch-crash-").toFile()
        val r1 = rig(dir = dir)
        val pod = r1.orch.submit(
            PodSpec(goal = "crash", maxConcurrency = 1, nodes = listOf(NodeSpec("running", "HANG"), NodeSpec("queued", "later"))),
        )
        awaitSessions(r1, pod.id, "running")
        val runId = r1.orch.status(pod.id)!!.runs.values.flatten().single().id

        // The daemon dies: no kill, no finish. The hung child is simply abandoned.
        r1.orch.close()
        r1.store.close()

        val store2 = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath).also { toClose += it }
        assertEquals(listOf(runId), store2.recoverAfterCrash(now = 9_999L))
        val runner2 = GatedRunner()
        val manager2 = SessionManager(
            StubProvider(), runner2, JsonSessionStore(File(dir, "sessions-after-crash.json")),
            killGraceMs = 200, defaultWorkingDir = dir,
        )
        val orch2 = Orchestrator(store2, manager2).also { toClose += it }
        manager2.eventSink = orch2::onSessionEvent

        assertEquals(listOf(pod.id), orch2.recover(now = 10_000L))
        val recovered = store2.pods.get(pod.id)!!
        assertEquals(PodState.INTERRUPTED.name, recovered.state)
        assertEquals(10_000L, recovered.endedAt)
        val nodes = store2.nodes.byPod(pod.id).associateBy { it.name }
        assertEquals(NodeState.FAILED.name, nodes.getValue("running").state)
        assertEquals("daemon restarted mid-run", nodes.getValue("running").detail)
        assertEquals(NodeState.SKIPPED.name, nodes.getValue("queued").state)
        assertEquals("daemon restarted before start", nodes.getValue("queued").detail)
        assertEquals(
            Outcome.ORPHANED,
            (store2.events.forRun(runId).last().event as RunEvent.Finished).payload.outcome,
        )
        Thread.sleep(300)
        assertTrue(runner2.processes.isEmpty(), "recovery never reschedules")
        assertTrue(orch2.recover(now = 11_000L).isEmpty(), "recover is idempotent")
        assertTrue(orch2.failures.isEmpty(), "orchestrator thread threw: ${orch2.failures}")
    }

    // 12 -- the stub provider's OWN transport (ScriptedRunner), no test runner
    @Test
    fun `the stub provider's own transport drives a pod end to end`() {
        val dir = Files.createTempDirectory("infantry-orch-stub-").toFile()
        val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath).also { toClose += it }
        val provider = StubProvider(delayMs = 0L)
        val manager = SessionManager(provider, provider.transport()!!, JsonSessionStore(File(dir, "sessions.json")), defaultWorkingDir = dir)
        val orch = Orchestrator(store, manager).also { toClose += it }
        manager.eventSink = orch::onSessionEvent

        val pod = orch.submit(
            PodSpec(
                goal = "stub pod", maxConcurrency = 2,
                nodes = listOf(NodeSpec("a", "a"), NodeSpec("b", "b"), NodeSpec("c", "c", setOf("a", "b"))),
            ),
        )
        assertEquals(PodState.CONVERGED.name, orch.awaitTerminal(pod.id, 10_000).state)
        val runs = store.runs.byPod(pod.id)
        assertEquals(3, runs.size)
        for (run in runs) {
            assertEquals("stub-session-1", run.providerSessionId, "parsed from the scripted stream")
            assertEquals(4L, run.stdoutLines)
            // 1 scripted diagnostic + the ONE boot line per launch (contract 1.64.0; the plumbing is Activity).
            assertEquals(2L, run.stderrLines)
            val log = store.events.forRun(run.id)
            assertTrue(log.any { (it.event as? RunEvent.TextDelta)?.text?.contains("[stub]") == true })
            assertEquals(Outcome.SUCCEEDED, (log.last().event as RunEvent.Finished).payload.outcome)
        }
        assertTrue(orch.failures.isEmpty(), "orchestrator thread threw: ${orch.failures}")
    }
}
