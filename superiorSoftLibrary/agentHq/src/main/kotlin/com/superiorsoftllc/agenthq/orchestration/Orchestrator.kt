package com.superiorsoftllc.agenthq.orchestration

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.NodeAttempt
import com.superiorsoftllc.agenthq.persistence.NodeDependency
import com.superiorsoftllc.agenthq.persistence.NodeRecord
import com.superiorsoftllc.agenthq.persistence.PodRecord
import com.superiorsoftllc.agenthq.persistence.RunEvent
import com.superiorsoftllc.agenthq.persistence.RunRecord
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * A pod and everything about its nodes, read in one go. [runs] is keyed by node id, attempt order.
 *
 * Phase 5b: this is ALSO what the `pod` answer frame carries, verbatim -- the same type the
 * orchestrator returns internally, so there is no second wire DTO to drift from the real thing
 * (the same one-model rule [com.superiorsoftllc.agenthq.session.SessionRecord] already follows).
 */
@Serializable
data class PodStatus(
    val pod: PodRecord,
    val nodes: List<NodeRecord>,
    val runs: Map<String, List<RunRecord>>,
)

/** One entry of the review queue: the final (successful) attempt of a node of a CONVERGED pod. */
data class ReviewItem(
    val podId: String,
    val podGoal: String,
    val nodeId: String,
    val nodeName: String,
    val runId: String,
    val sessionId: String,
    val workingDir: String,
    /** The worktree branch of that attempt's session, when it ran in a worktree. */
    val worktreeBranch: String?,
)

/**
 * Phase 5 orchestrator: runs pods (DAGs of nodes) through [SessionManager]. One worker thread owns
 * every piece of orchestration state; session events are only ever enqueued onto it.
 */
class Orchestrator(
    private val store: AgentHqStore,
    private val sessions: SessionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /** BUG-G2b: composes a node's FIRST attempt (knowledge, skills, then the prompt); retries never pass through it. */
    private val firstTurn: PodFirstTurn = PodFirstTurn.RAW,
) : Closeable {

    private class LiveAttempt(
        val podId: String,
        val nodeId: String,
        val runId: String,
        val sessionId: String,
        val attempt: Int,
        val startedAt: Long,
        var stdoutLines: Long = 0,
        var stderrLines: Long = 0,
    )

    @Volatile private var workerThread: Thread? = null
    @Volatile private var closed = false

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agenthq-orchestrator").also { it.isDaemon = true; workerThread = it }
    }
    private val killer = Executors.newCachedThreadPool { r ->
        Thread(r, "agenthq-orchestrator-kill").apply { isDaemon = true }
    }

    // Worker thread only.
    private val bySession = HashMap<String, LiveAttempt>()
    private val byNode = HashMap<String, LiveAttempt>()
    private val launches = HashMap<String, PodSpec>()
    private val cancelRequested = HashSet<String>()

    private val failureList = CopyOnWriteArrayList<Throwable>()

    /** Every Throwable caught on the orchestrator thread. Tests assert it stays empty. */
    val failures: List<Throwable> get() = failureList

    /** Durable operator attention for a DAG that cannot make scheduling progress. */
    @Volatile
    var schedulerStallSink: ((String, String) -> Unit)? = null

    /**
     * BUG-G2d: told (podId, sessionId, the pod's [PodSpec.parentSessionId]) on the worker thread each time an attempt
     * starts a session, so the daemon's spawn guard charges it to the pod it was spawned from. Must not block.
     */
    @Volatile
    var sessionStartedSink: ((String, String, String?) -> Unit)? = null

    /** BUG-G2d: how many of [podId]'s nodes have not started yet (0 for an unknown or ended pod). */
    fun pendingNodes(podId: String): Int = onWorker {
        val pod = store.pods.get(podId) ?: return@onWorker 0
        if (pod.endedAt != null) 0 else store.nodes.byPod(podId).count { it.state == NodeState.PENDING.name }
    }

    private fun <T> onWorker(block: () -> T): T =
        if (Thread.currentThread() === workerThread) block()
        else try {
            worker.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failureList += t
            System.err.println("[agentHq] orchestrator: ${t.message}")
        }
    }

    /** Feed of SessionManager events. ENQUEUES ONLY; never blocks, never throws; a no-op after close(). */
    fun onSessionEvent(event: SessionEvent) {
        if (closed) return
        try {
            worker.execute { guarded { handle(event) } }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Validates (throws InvalidPodException, nothing written), persists pod+nodes+edges in one transaction, schedules. */
    fun submit(spec: PodSpec): PodRecord {
        PodValidator.validate(spec)
        return onWorker {
            val now = clock()
            val pod = PodRecord(
                id = idFactory(), goal = spec.goal, state = PodState.RUNNING.name,
                maxConcurrency = spec.maxConcurrency, createdAt = now,
            )
            store.transaction {
                store.pods.create(pod)
                val ids = HashMap<String, String>()
                spec.nodes.forEachIndexed { i, n ->
                    val id = idFactory()
                    ids[n.name] = id
                    store.nodes.create(
                        NodeRecord(
                            id = id, podId = pod.id, name = n.name, specIndex = i, prompt = n.prompt,
                            state = NodeState.PENDING.name, maxAttempts = n.maxAttempts, createdAt = now,
                        ),
                    )
                }
                for (n in spec.nodes) for (d in n.dependsOn.sorted()) {
                    store.nodes.addDependency(NodeDependency(ids.getValue(n.name), ids.getValue(d)))
                }
            }
            launches[pod.id] = spec
            schedule(pod.id)
            store.pods.get(pod.id)!!
        }
    }

    /** Pod-level cancel. No-op (returns the record) on a terminal pod. Throws IllegalArgumentException for an unknown id. */
    fun cancelPod(podId: String, force: Boolean = false): PodRecord = onWorker {
        val pod = store.pods.get(podId) ?: throw IllegalArgumentException("unknown pod $podId")
        if (PodState.valueOf(pod.state).isTerminal) return@onWorker pod
        val now = clock()
        store.transaction {
            store.pods.updateState(podId, PodState.CANCELLING.name)
            for (n in store.nodes.byPod(podId)) {
                if (n.state == NodeState.PENDING.name) {
                    store.nodes.update(n.copy(state = NodeState.CANCELLED.name, detail = DETAIL_POD_CANCELLED_BEFORE_START, endedAt = now))
                }
            }
        }
        for (a in bySession.values.filter { it.podId == podId }) asyncKill(a.sessionId, force)
        maybeFinish(podId)
        store.pods.get(podId)!!
    }

    /** Node-level cancel. No-op on a terminal node. Throws IllegalArgumentException for an unknown id. */
    fun cancelNode(nodeId: String, force: Boolean = false): NodeRecord = onWorker {
        val node = store.nodes.get(nodeId) ?: throw IllegalArgumentException("unknown node $nodeId")
        when (NodeState.valueOf(node.state)) {
            NodeState.PENDING -> {
                store.nodes.update(node.copy(state = NodeState.CANCELLED.name, detail = DETAIL_CANCELLED_BEFORE_START, endedAt = clock()))
                schedule(node.podId)
            }
            NodeState.RUNNING -> {
                cancelRequested += nodeId
                byNode[nodeId]?.let { asyncKill(it.sessionId, force) }
            }
            else -> Unit
        }
        store.nodes.get(nodeId)!!
    }

    private fun asyncKill(sessionId: String, force: Boolean) {
        killer.execute { runCatching { sessions.kill(sessionId, force, by = "pod cancel") } }
    }

    /** Nodes of CONVERGED pods awaiting review, oldest first. */
    fun reviewQueue(): List<ReviewItem> = onWorker {
        store.nodes.reviewQueue().mapNotNull { node ->
            val pod = store.pods.get(node.podId) ?: return@mapNotNull null
            val attempt = store.nodes.attempts(node.id).lastOrNull() ?: return@mapNotNull null
            val run = store.runs.get(attempt.runId) ?: return@mapNotNull null
            ReviewItem(
                podId = pod.id, podGoal = pod.goal, nodeId = node.id, nodeName = node.name,
                runId = run.id, sessionId = run.sessionId, workingDir = run.workingDir,
                worktreeBranch = sessions.get(run.sessionId)?.worktreeBranch,
            )
        }
    }

    /**
     * Records a review decision. [decision] must be APPROVED or REJECTED (else IllegalArgumentException);
     * the node must currently be review-PENDING (else IllegalStateException). Touches no git state.
     */
    fun review(nodeId: String, decision: ReviewState): NodeRecord = onWorker {
        require(decision == ReviewState.APPROVED || decision == ReviewState.REJECTED) {
            "decision must be APPROVED or REJECTED, was $decision"
        }
        val node = store.nodes.get(nodeId) ?: throw IllegalArgumentException("unknown node $nodeId")
        check(node.reviewState == ReviewState.PENDING.name) {
            "node $nodeId is not awaiting review (reviewState=${node.reviewState})"
        }
        store.nodes.update(node.copy(reviewState = decision.name))
        store.nodes.get(nodeId)!!
    }

    fun status(podId: String): PodStatus? = onWorker {
        val pod = store.pods.get(podId) ?: return@onWorker null
        val nodes = store.nodes.byPod(podId)
        val runs = nodes.associate { n ->
            n.id to store.nodes.attempts(n.id).mapNotNull { store.runs.get(it.runId) }
        }
        PodStatus(pod, nodes, runs)
    }

    /** Polls until the pod has ended_at; throws IllegalStateException on timeout (message names the state). */
    fun awaitTerminal(podId: String, timeoutMs: Long): PodRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: PodRecord?
        while (true) {
            last = onWorker { store.pods.get(podId) }
            if (last == null) throw IllegalArgumentException("unknown pod $podId")
            if (last.endedAt != null) return last
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(20)
        }
        throw IllegalStateException("pod $podId not terminal after ${timeoutMs}ms (state ${last?.state})")
    }

    /**
     * Daemon-startup recovery for pods. Call AFTER store.recoverAfterCrash(now) and BEFORE any submit.
     * Every open pod becomes INTERRUPTED. Idempotent. Returns the recovered pod ids.
     */
    fun recover(now: Long): List<String> = onWorker {
        check(bySession.isEmpty()) { "recover() must run before any pod is submitted" }
        val open = store.pods.open()
        for (pod in open) {
            store.transaction {
                for (n in store.nodes.byPod(pod.id)) {
                    when (NodeState.valueOf(n.state)) {
                        NodeState.RUNNING ->
                            store.nodes.update(n.copy(state = NodeState.FAILED.name, detail = DETAIL_RESTARTED_MID_RUN, endedAt = now))
                        NodeState.PENDING ->
                            store.nodes.update(n.copy(state = NodeState.SKIPPED.name, detail = DETAIL_RESTARTED_BEFORE_START, endedAt = now))
                        else -> Unit
                    }
                }
                store.pods.finish(pod.id, PodState.INTERRUPTED.name, now)
            }
        }
        open.map { it.id }
    }

    /** Stops the orchestrator threads (awaits up to 5 s). Does NOT kill sessions: SessionManager.shutdown owns that. */
    override fun close() {
        closed = true
        worker.shutdown()
        killer.shutdown()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ---- everything below runs on the worker thread ----

    private fun handle(event: SessionEvent) {
        when (event) {
            is SessionEvent.Status -> {
                val a = bySession[event.session.id] ?: return
                store.events.append(a.runId, RunEvent.Status(StatusPayload(event.session.state)), clock())
                if (RunOutcomes.isAttemptOver(event.session.state)) endAttempt(a, event.session)
            }
            is SessionEvent.Stdout -> {
                val a = bySession[event.sessionId] ?: return
                a.stdoutLines++
                for (l in EventNormalizer.normalise(event.line)) {
                    val e = when (l) {
                        is EventNormalizer.Line.Text -> RunEvent.TextDelta(l.delta)
                        is EventNormalizer.Line.Reasoning -> RunEvent.Reasoning(l.text)
                        is EventNormalizer.Line.Status -> RunEvent.Status(StatusPayload(SessionState.BUSY, l.label))
                        is EventNormalizer.Line.Tool -> RunEvent.Status(StatusPayload(SessionState.BUSY, tool = l.name, activity = l.activity, write = l.write))
                        is EventNormalizer.Line.Failure -> RunEvent.Error(l.message)
                        // BUG-PERM-1: a denial is a beat of the run, not its failure.
                        is EventNormalizer.Line.Denial -> RunEvent.Status(StatusPayload(SessionState.BUSY, "${l.harness} denied tool ${l.tool}"))
                        // 1.95.0: a tool's end is the run activity's business, not a run event.
                        EventNormalizer.Line.ToolEnd, EventNormalizer.Line.CompactionStart, EventNormalizer.Line.CompactionEnd -> continue
                    }
                    store.events.append(a.runId, e, clock())
                }
            }
            is SessionEvent.Stderr -> {
                val a = bySession[event.sessionId] ?: return
                a.stderrLines++
            }
            is SessionEvent.Exit -> Unit
            is SessionEvent.Activity -> Unit
            is SessionEvent.ProcessExit -> Unit
            // The run's prompt is already on its RunRecord; the turn adds nothing to the run log.
            is SessionEvent.Turn -> Unit
        }
    }

    private fun schedule(podId: String) {
        val pod = store.pods.get(podId) ?: return
        if (PodState.valueOf(pod.state) != PodState.RUNNING) {
            maybeFinish(podId)
            return
        }
        var nodes = store.nodes.byPod(podId)
        val deps = store.nodes.dependencies(podId).groupBy({ it.nodeId }, { it.dependsOn })

        // Skip propagation to a fixed point.
        var changed = true
        while (changed) {
            changed = false
            val byId = nodes.associateBy { it.id }
            for (n in nodes) {
                if (n.state != NodeState.PENDING.name) continue
                val bad = deps[n.id].orEmpty().map { byId.getValue(it) }.sortedBy { it.specIndex }
                    .firstOrNull { it.state in SKIP_CAUSES } ?: continue
                store.nodes.update(
                    n.copy(state = NodeState.SKIPPED.name, detail = "upstream '${bad.name}' ${bad.state}", endedAt = clock()),
                )
                nodes = store.nodes.byPod(podId)
                changed = true
                break
            }
        }

        val byId = nodes.associateBy { it.id }
        val ready = nodes.filter { n ->
            n.state == NodeState.PENDING.name &&
                deps[n.id].orEmpty().all { byId.getValue(it).state == NodeState.SUCCEEDED.name }
        }
        val running = nodes.count { it.state == NodeState.RUNNING.name }
        val toStart = ready.take(maxOf(0, pod.maxConcurrency - running))
        var startFailed = false
        for (n in toStart) if (!startAttempt(pod, n)) startFailed = true
        if (startFailed) {
            schedule(podId)
            return
        }
        if (running == 0 && toStart.isEmpty() && nodes.any { it.state == NodeState.PENDING.name }) {
            val detail = "scheduler stalled on pod $podId"
            runCatching { schedulerStallSink?.invoke(podId, detail) }
                .onFailure { System.err.println("[agentHq] scheduler stall attention failed: ${it.message}") }
            throw IllegalStateException(detail)
        }
        maybeFinish(podId)
    }

    /** Returns false when the attempt could not be started (the node was failed). */
    private fun startAttempt(pod: PodRecord, node: NodeRecord): Boolean {
        val spec = launches[pod.id]
        val attempt = node.attempts + 1
        // BUG-G2b: the first attempt is a session's first turn, composed like `prompt new` for the pod's project. A
        // retry keeps RetryFeedback over the ORIGINAL node prompt (the composed context is in the session already).
        val prompt = if (attempt == 1) firstTurn.compose(spec?.projectId, node.prompt) else {
            val prevRun = store.runs.get(store.nodes.attempts(node.id).last().runId)!!
            RetryFeedback.compose(node.prompt, attempt, node.maxAttempts, prevRun, store.events.forRun(prevRun.id))
        }
        // BUG-PODMODEL: the node's own provider/model (null = the daemon default), one path for every harness. Node names
        // are unique within a pod (PodValidator), and the spec lives in memory exactly as long as the pod can start work.
        val nodeSpec = spec?.nodes?.firstOrNull { it.name == node.name }
        val running = node.copy(state = NodeState.RUNNING.name, attempts = attempt, detail = null)
        store.nodes.update(running)
        val session = try {
            sessions.startNew(
                prompt, workingDir = spec?.workingDir, repo = spec?.repo, branchHint = node.name, baseRef = spec?.baseRef,
                providerId = nodeSpec?.provider, model = nodeSpec?.model,
                // BUG-PODMODEL-2: the node runs as the agent its spec names (null = no profile, as before).
                agentId = nodeSpec?.agentId,
                unattended = true, projectId = spec?.projectId,
            )
        } catch (e: Exception) {
            store.nodes.update(
                running.copy(state = NodeState.FAILED.name, detail = "could not start: ${e.message}", endedAt = clock()),
            )
            return false
        }
        runCatching { sessionStartedSink?.invoke(pod.id, session.id, spec?.parentSessionId) }
            .onFailure { System.err.println("[agentHq] session-started sink failed: ${it.message}") }
        val runId = idFactory()
        store.transaction {
            store.runs.upsert(
                RunRecord(
                    id = runId, sessionId = session.id, provider = session.provider,
                    workingDir = session.workingDir, prompt = prompt, state = session.state,
                    startedAt = clock(), podId = pod.id,
                ),
            )
            store.nodes.recordAttempt(NodeAttempt(runId, node.id, attempt))
        }
        val live = LiveAttempt(pod.id, node.id, runId, session.id, attempt, clock())
        bySession[session.id] = live
        byNode[node.id] = live
        return true
    }

    private fun endAttempt(a: LiveAttempt, record: SessionRecord) {
        bySession.remove(a.sessionId)
        byNode.remove(a.nodeId)
        val outcome = RunOutcomes.of(record)
        val now = clock()
        val cancelRequestedForNode = a.nodeId in cancelRequested
        store.transaction {
            if (outcome == FinishedPayload.Outcome.FAILED && record.lastExitCode == null && record.lastError != null) {
                store.events.append(a.runId, RunEvent.Error(record.lastError!!), now)
            }
            store.events.append(
                a.runId,
                RunEvent.Finished(FinishedPayload(record.lastExitCode, outcome, record.lastError ?: "")),
                now,
            )
            val run = store.runs.get(a.runId)!!
            store.runs.upsert(
                run.copy(
                    state = record.state, endedAt = now, exitCode = record.lastExitCode,
                    stdoutLines = a.stdoutLines, stderrLines = a.stderrLines,
                    providerSessionId = record.providerSessionId, error = record.lastError,
                ),
            )
            val node = store.nodes.get(a.nodeId)!!
            val podState = PodState.valueOf(store.pods.get(a.podId)!!.state)
            val next = when (outcome) {
                FinishedPayload.Outcome.SUCCEEDED -> node.copy(state = NodeState.SUCCEEDED.name, detail = null, endedAt = now)
                FinishedPayload.Outcome.KILLED_GRACEFUL ->
                    node.copy(state = NodeState.CANCELLED.name, detail = "killed (graceful)", endedAt = now)
                FinishedPayload.Outcome.KILLED_FORCED ->
                    node.copy(state = NodeState.CANCELLED.name, detail = "killed (forced)", endedAt = now)
                FinishedPayload.Outcome.ORPHANED ->
                    node.copy(state = NodeState.FAILED.name, detail = "orphaned", endedAt = now)
                FinishedPayload.Outcome.FAILED -> when {
                    cancelRequestedForNode ->
                        node.copy(state = NodeState.CANCELLED.name, detail = "cancelled by request", endedAt = now)
                    podState != PodState.RUNNING ->
                        node.copy(state = NodeState.FAILED.name, detail = "not retried: pod cancelling", endedAt = now)
                    RunOutcomes.isRetryable(outcome) && node.attempts < node.maxAttempts ->
                        node.copy(state = NodeState.PENDING.name, detail = null, endedAt = null)
                    else -> node.copy(
                        state = NodeState.FAILED.name,
                        detail = "attempts exhausted (${node.attempts}/${node.maxAttempts})",
                        endedAt = now,
                    )
                }
            }
            store.nodes.update(next)
        }
        cancelRequested.remove(a.nodeId)
        schedule(a.podId)
    }

    private fun maybeFinish(podId: String) {
        val pod = store.pods.get(podId) ?: return
        if (pod.endedAt != null) return
        val nodes = store.nodes.byPod(podId)
        if (nodes.any { it.state == NodeState.PENDING.name || it.state == NodeState.RUNNING.name }) return
        val final = when {
            PodState.valueOf(pod.state) == PodState.CANCELLING -> PodState.CANCELLED
            nodes.all { it.state == NodeState.SUCCEEDED.name } -> PodState.CONVERGED
            else -> PodState.FAILED
        }
        store.transaction {
            store.pods.finish(podId, final.name, clock())
            if (final == PodState.CONVERGED) {
                nodes.forEach { store.nodes.update(it.copy(reviewState = ReviewState.PENDING.name)) }
            }
        }
        launches.remove(podId)
    }

    companion object {
        const val DETAIL_ORPHANED = "orphaned"
        const val DETAIL_POD_CANCELLED_BEFORE_START = "pod cancelled before start"
        const val DETAIL_CANCELLED_BEFORE_START = "cancelled before start"
        const val DETAIL_RESTARTED_MID_RUN = "daemon restarted mid-run"
        const val DETAIL_RESTARTED_BEFORE_START = "daemon restarted before start"

        private val SKIP_CAUSES = setOf(NodeState.FAILED.name, NodeState.CANCELLED.name, NodeState.SKIPPED.name)
    }
}
