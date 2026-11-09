// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.daemon.api.orchestrator.AgentIdentity
import com.superiorsoftllc.infantry.daemon.api.orchestrator.ForwardedFrame
import com.superiorsoftllc.infantry.daemon.api.orchestrator.FrameSide
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-POOL-7 (docs/RUNNER-POOL.md 2.3 b), the RUNNER side: a worker of a session its remote orchestrator started talks to THIS
 * daemon, which has no board. A frame whose frame table entry is tagged [FrameSide.ORCHESTRATOR] is held here ([forwardFrame]) until
 * the orchestrator, which dials this runner, takes it with `runner_link_forward_pull` ([pull]) and hands its answer back with
 * `runner_link_forward_answer` ([answer]). Nothing listens on the orchestrator and the worker holds no orchestrator credential.
 *
 * [owns] says which sessions the remote orchestrator started (only those are forwarded: a daemon that is its own orchestrator never
 * reaches the side tag). At most [pendingMax] frames wait at once; one waits at most [timeoutMs], then the agent's call fails as
 * unavailable.
 */
class FrameForwarder(
    private val owns: (sessionId: String) -> Boolean,
    private val timeoutMs: () -> Long,
    private val pendingMax: () -> Int,
) {
    private class Pending(val wire: F.ForwardFrameWire, val answer: CompletableFuture<F.ForwardAnswerRequest>) {
        @Volatile var taken = false
    }

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val pending = LinkedHashMap<String, Pending>()

    /** Whether a frame of [sessionId] goes to the remote orchestrator (then, and only then, its side is asked). */
    fun forwards(sessionId: String?): Boolean = !sessionId.isNullOrBlank() && owns(sessionId)

    /**
     * Hold [frame] of agent session [sessionId] for the orchestrator and wait for its answer (blocking). Ok(null) = the frame has no
     * answer; an `error` answer is the orchestrator's refusal; Unavailable = it did not take or answer it in time.
     */
    fun forwardFrame(sessionId: String, frame: ForwardedFrame): OrchestratorReply<ForwardedFrame?> {
        val id = UUID.randomUUID().toString()
        val p = Pending(F.ForwardFrameWire(id, sessionId, frame.type, frame.data, frame.requestId), CompletableFuture())
        lock.withLock {
            if (pending.size >= pendingMax().coerceAtLeast(1)) {
                return OrchestratorReply.Unavailable("this runner already holds ${pending.size} frames for its orchestrator (limits.runners.forwardPendingMax)")
            }
            pending[id] = p
            arrived.signalAll()
        }
        return try {
            val a = p.answer.get(timeoutMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)
            val unavailable = a.unavailable
            val type = a.type
            when {
                unavailable != null -> OrchestratorReply.Unavailable(unavailable)
                a.none || type == null -> OrchestratorReply.Ok(null)
                else -> OrchestratorReply.Ok(ForwardedFrame(type, a.data, a.sessionId, frame.requestId))
            }
        } catch (e: TimeoutException) {
            OrchestratorReply.Unavailable("the orchestrator did not answer ${frame.type} in ${timeoutMs() / MS_PER_SECOND}s (limits.runners.forwardTimeoutSec)")
        } finally {
            lock.withLock { pending.remove(id) }
        }
    }

    /** The orchestrator's pull: every frame not yet taken, waiting up to [waitMs] for the first one. */
    fun pull(waitMs: Long): List<F.ForwardFrameWire> = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceAtLeast(0))
        while (pending.values.none { !it.taken } && left > 0) left = arrived.awaitNanos(left)
        pending.values.filter { !it.taken }.onEach { it.taken = true }.map { it.wire }
    }

    /** The orchestrator's answer to frame [a].id; false when no frame waits under that id (timed out, or never held). */
    fun answer(a: F.ForwardAnswerRequest): Boolean {
        val p = lock.withLock { pending[a.id] } ?: return false
        return p.answer.complete(a)
    }

    /** Frames held now (for a test or the log). */
    fun held(): Int = lock.withLock { pending.size }

    companion object {
        private const val MS_PER_SECOND = 1000L

        /**
         * RUNNER-DONE-1: the agent session a client frame speaks for, which decides whether it is forwarded. First the session the
         * frame names, then the one this connection drives ([active]), then a tool call's `client.sessionId`, then the session the
         * client declared when it connected ([declared], the [SESSION_HEADER] the CLI and the MCP server send from
         * `INFANTRY_SESSION_ID`). A worker's `ticket move` looks its ticket up with a `ticket_list` that names no session, and its MCP
         * tools send `plugin_tool_call` with the session only inside `client`: without the last two, neither was forwarded and the
         * runner answered them itself ("runner mode"), so a remote worker could never move its ticket to done.
         * Only who is asking comes from here; the orchestrator still checks the session is one it placed on THIS runner.
         */
        fun callerSession(type: String, data: String, sessionId: String?, active: String?, declared: String?): String? =
            sessionId?.takeIf { it.isNotBlank() }
                ?: active?.takeIf { it.isNotBlank() }
                ?: toolClientSession(type, data)
                ?: declared?.trim()?.takeIf { it.isNotEmpty() }

        /** The header a client names its own agent session in (the same one the watch hooks read). */
        const val SESSION_HEADER = com.superiorsoftllc.infantry.daemon.WatchHooks.SESSION_HEADER

        /** A `plugin_tool_call`'s `client.sessionId`; null for any other frame or when it names none. */
        internal fun toolClientSession(type: String, data: String): String? {
            if (type != ForwardedFrameGate.PLUGIN_TOOL_CALL) return null
            val call = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            val client = call["client"] as? JsonObject ?: return null
            return (client["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * RUNNER-POOL-7, the ORCHESTRATOR side's check of one forwarded frame before it is answered as agent [AgentIdentity.sessionId]:
 *
 * * the identity must be a session runner [AgentIdentity.runner] owns ([runnerOf], the orchestrator's session index; the runner name
 *   is the link's, never the frame's), so a runner cannot speak as a local session, another runner's session or the owner;
 * * the frame may name no other session (its `sessionId`, and a `plugin_tool_call`'s `client.sessionId`);
 * * it must be an [FrameSide.ORCHESTRATOR] frame ([sideOf]): a runner does not get the orchestrator to spawn, set settings or run commands;
 * * then [FrameGate] as a NON-owner connection (owner-only frames are refused; [pluginOwnerOnly] for a plugin's).
 *
 * A passing frame is answered by [dispatch] with the agent's session on it; [dispatch] runs the orchestrator's own connection gates
 * (policy, plugin gates, sandbox) as a non-owner caller. A refusal is an `error` frame.
 */
class ForwardedFrameGate(
    private val runnerOf: (sessionId: String) -> String,
    private val sideOf: (ForwardedFrame) -> String?,
    private val pluginOwnerOnly: (type: String) -> Boolean,
    private val dispatch: (ForwardedFrame) -> ForwardedFrame?,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    fun answer(agent: AgentIdentity, frame: ForwardedFrame): ForwardedFrame? {
        refusal(agent, frame)?.let { why ->
            log("[infantry] runner link: forwarded ${shown(frame.type)} from runner ${shown(agent.runner)} refused: $why")
            return ForwardedFrame(ERROR, "${shown(frame.type)} refused: $why", agent.sessionId, frame.requestId)
        }
        return dispatch(frame.copy(sessionId = agent.sessionId))
    }

    fun refusal(agent: AgentIdentity, frame: ForwardedFrame): String? {
        val owner = runnerOf(agent.sessionId)
        return when {
            agent.runner.isBlank() || agent.runner == RunnerPool.LOCAL || owner != agent.runner ->
                "session ${shown(agent.sessionId)} is not a session runner ${shown(agent.runner)} runs for this orchestrator"
            !frame.sessionId.isNullOrBlank() && frame.sessionId != agent.sessionId ->
                "the frame names session ${shown(frame.sessionId)}, not its sender ${shown(agent.sessionId)}"
            clientSession(frame)?.let { it != agent.sessionId } == true ->
                "the tool call names client session ${shown(clientSession(frame))}, not its sender ${shown(agent.sessionId)}"
            FrameSide.of(sideOf(frame)) != FrameSide.ORCHESTRATOR -> "not an orchestrator-side frame"
            else -> FrameGate.refusal(frame.type, owner = false, pluginOwnerOnly = pluginOwnerOnly(frame.type))
        }
    }

    private fun clientSession(frame: ForwardedFrame): String? = FrameForwarder.toolClientSession(frame.type, frame.data)

    private fun shown(s: String?) = s.orEmpty().filter { it in ' '..'~' }.take(SHOWN_CHARS)

    companion object {
        const val ERROR = "error"
        const val PLUGIN_TOOL_CALL = "plugin_tool_call"
        private const val SHOWN_CHARS = 64
    }
}

/**
 * RUNNER-POOL-7, the ORCHESTRATOR side's pump: for every paired runner ([sync]) one thread pulls the frames its workers forwarded
 * (`runner_link_forward_pull`, waiting up to [waitMs] on the runner) WHILE this orchestrator has a session there ([active]; none =
 * no link traffic), answers each with [execute] on its own thread, and hands the answer back (`runner_link_forward_answer`). A runner
 * that cannot be reached is retried after [idleMs].
 */
class ForwardPump(
    private val links: () -> RunnerLinks,
    private val active: (runner: String) -> Boolean,
    private val execute: (AgentIdentity, ForwardedFrame) -> ForwardedFrame?,
    private val waitMs: () -> Long,
    private val idleMs: () -> Long,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private val loops = ConcurrentHashMap<String, Thread>()
    private val workers: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "infantry-forward-frame").apply { isDaemon = true } }
    @Volatile private var closed = false

    /** Keep one pull loop per runner in [runners]; a runner no longer paired has its loop stopped. */
    @Synchronized
    fun sync(runners: Collection<String>) {
        if (closed) return
        val want = runners.filter { it != RunnerPool.LOCAL }.toSet()
        loops.keys.filter { it !in want }.forEach { loops.remove(it)?.interrupt() }
        for (r in want) {
            if (loops[r]?.isAlive == true) continue
            loops[r] = Thread({ loop(r) }, "infantry-forward-pull-$r").apply { isDaemon = true; start() }
        }
    }

    private fun loop(runner: String) {
        val me = Thread.currentThread()
        while (!closed && loops[runner] === me) {
            try {
                if (!active(runner)) {
                    Thread.sleep(idleMs().coerceAtLeast(1))
                    continue
                }
                val answer = links().call(runner, F.FORWARD_PULL, F.json.encodeToString(F.ForwardPullRequest.serializer(), F.ForwardPullRequest(waitMs())))
                if (answer.type != F.REPLY) {
                    log("[infantry] runner link: forward pull from '$runner' refused: ${answer.data.take(LOGGED_CHARS)}")
                    Thread.sleep(idleMs().coerceAtLeast(1))
                    continue
                }
                val frames = runCatching { F.json.decodeFromString(F.ForwardPullAnswer.serializer(), answer.data).frames }.getOrDefault(emptyList())
                for (f in frames) workers.execute { answerOne(runner, f) }
            } catch (e: InterruptedException) {
                return
            } catch (e: LinkFailure) {
                runCatching { Thread.sleep(idleMs().coerceAtLeast(1)) }.onFailure { return }
            } catch (e: Exception) {
                log("[infantry] runner link: forward pull from '$runner' failed: ${e.message}")
                runCatching { Thread.sleep(idleMs().coerceAtLeast(1)) }.onFailure { return }
            }
        }
    }

    private fun answerOne(runner: String, f: F.ForwardFrameWire) {
        val reply = try {
            val out = execute(AgentIdentity(runner, f.sessionId), ForwardedFrame(f.type, f.data, f.sessionId, f.requestId))
            if (out == null) F.ForwardAnswerRequest(f.id, none = true) else F.ForwardAnswerRequest(f.id, out.type, out.data, out.sessionId)
        } catch (e: Exception) {
            F.ForwardAnswerRequest(f.id, unavailable = "the orchestrator failed answering ${f.type}: ${e.message ?: e::class.simpleName}")
        }
        try {
            links().call(runner, F.FORWARD_ANSWER, F.json.encodeToString(F.ForwardAnswerRequest.serializer(), reply))
        } catch (e: LinkFailure) {
            log("[infantry] runner link: the answer to a forwarded ${f.type} did not reach '$runner': ${e.message}")
        }
    }

    override fun close() {
        closed = true
        loops.values.forEach { it.interrupt() }
        loops.clear()
        workers.shutdownNow()
    }

    private companion object {
        const val LOGGED_CHARS = 200
    }
}
