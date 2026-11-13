// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.protocol.Envelope
import java.util.concurrent.ConcurrentHashMap
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-UNIFY-3 (docs/RUNNER-UNIFY.md 5.2), the RUNNER side of the pushed stream: answers `runner_link_frames_pull` with the stream
 * frames of the sessions the paired orchestrator started here ([sessions]), after the sequence the orchestrator names for each.
 * When nothing is new the call is HELD: [published] (called by the stream sequencer for every frame it numbers) wakes it, so a frame
 * leaves the runner the moment it exists. Every sequenced frame type crosses, `status` (with its run activity) and `session` included.
 *
 * [framesAfter] is the runner's own replay buffer read: (session, after) -> the frames with a higher sequence, in order.
 */
class RunnerFrameFeed(
    private val sessions: () -> Collection<String>,
    private val framesAfter: (sessionId: String, afterSequence: Long) -> List<Envelope>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Object()
    private var version = 0L

    /** A frame was published on this runner's stream: a held pull looks again. Cheap; called for every frame. */
    fun published() {
        synchronized(lock) {
            version++
            lock.notifyAll()
        }
    }

    /**
     * The frames of the sessions [resumeFrom] names (and the orchestrator started here) after the sequence it names for each, at most
     * [max] (0 = no bound), waiting up to [waitMs] for the first one. A session it does not name is not sent: a session the
     * orchestrator forgot must never answer every call at once. An interrupted wait answers what there is (nothing).
     */
    fun pull(resumeFrom: Map<String, Long>, waitMs: Long, max: Int): F.FramesPullAnswer {
        val deadline = clock() + waitMs.coerceAtLeast(0)
        while (true) {
            val seen = synchronized(lock) { version }
            val answer = collect(resumeFrom, max)
            if (answer.frames.isNotEmpty()) return answer
            val left = deadline - clock()
            if (left <= 0) return answer
            try {
                synchronized(lock) { if (version == seen) lock.wait(left) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return answer
            }
        }
    }

    private fun collect(resumeFrom: Map<String, Long>, max: Int): F.FramesPullAnswer {
        val out = ArrayList<Envelope>()
        val mine = sessions().toSet()
        for (id in resumeFrom.keys.filter { it in mine }.sorted()) {
            val after = resumeFrom.getValue(id)
            val fresh = framesAfter(id, after).filter { it.sessionId == id && (it.sequence ?: 0L) > after }
            for (f in fresh) {
                if (max > 0 && out.size >= max) return F.FramesPullAnswer(out, more = true)
                out += f
            }
        }
        return F.FramesPullAnswer(out)
    }
}

/**
 * RUNNER-UNIFY-3 (docs/RUNNER-UNIFY.md 5.2), the ORCHESTRATOR side: the subscription to each paired runner's pushed stream, the
 * ONE way a remote session's frames reach this daemon (RUNNER-UNIFY-6 deleted the poll path). For every runner ([sync]) one thread
 * keeps a `pullFrames` call open while this orchestrator has a session there ([sessionsOn]); each answer is applied at once and the
 * next call goes out at once, so a runner's frame reaches the app in one link round trip.
 *
 * Resume: each call names, per session, the last runner sequence taken ([takenBefore], persisted in the placement index), so a
 * reconnect or a restart continues where it stopped and frames already taken are dropped ([apply] gets only fresh ones, in sequence
 * order). A runner that cannot be reached rests [idleMs] and is asked again: nothing is lost, the frames wait in its own stream.
 * A runner that does not offer the pull (older than contract 1.10.0) is reported once; the runner image is rebuilt with the
 * orchestrator (pre-stable: no old-runner fallback).
 */
class RunnerFrameStream(
    private val sessionsOn: (runner: String) -> Collection<String>,
    private val link: (runner: String) -> RunnerLink?,
    private val takenBefore: (sessionId: String) -> Long,
    /** Fresh frames of one session, in sequence order (each still carries the runner's sequence). */
    private val apply: (runner: String, sessionId: String, frames: List<Envelope>) -> Unit,
    /** The highest runner sequence now taken of a session (after [apply]); what [takenBefore] answers next. */
    private val onTaken: (sessionId: String, taken: Long) -> Unit,
    private val waitMs: () -> Long,
    private val maxFrames: () -> Int,
    /** How long a loop rests when idle, refused or unreachable before it looks again. */
    private val idleMs: () -> Long,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private val loops = ConcurrentHashMap<String, Thread>()
    private val live = ConcurrentHashMap.newKeySet<String>()
    private val toldOld = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Any>()
    /** session -> the runner the last round asked for it. */
    private val sessionRunner = ConcurrentHashMap<String, String>()
    @Volatile private var closed = false

    /** Whether runner [runner]'s frames are pushed right now (its last pull answered). */
    fun streaming(runner: String): Boolean = runner in live

    /** Whether session [sessionId]'s frames are pushed right now (its runner's last pull answered and named it). */
    fun streamingSession(sessionId: String): Boolean = sessionRunner[sessionId]?.let { it in live } == true

    /** Keep one subscription loop per paired remote runner in [runners]; a runner no longer paired has its loop stopped. */
    @Synchronized
    fun sync(runners: Collection<String>) {
        if (closed) return
        val want = runners.toSet()
        loops.keys.filter { it !in want }.forEach { r -> loops.remove(r)?.interrupt(); live.remove(r) }
        for (r in want) {
            if (loops[r]?.isAlive == true) continue
            loops[r] = Thread({ loop(r) }, "infantry-frame-stream-$r").apply { isDaemon = true; start() }
        }
    }

    /** One round for [runner]: true = it answered (call again at once), false = rest first. Visible for tests. */
    fun round(runner: String): Boolean {
        val ids = sessionsOn(runner).toList()
        if (ids.isEmpty()) { live.remove(runner); return false }
        val resumeFrom = ids.associateWith { takenBefore(it) }
        for (id in ids) sessionRunner[id] = runner
        when (val reply = link(runner)?.pullFrames(resumeFrom, waitMs(), maxFrames())) {
            null -> {
                live.remove(runner)
                if (toldOld.add(runner)) log("[infantry] runner '$runner' does not push session frames (an older runner): its sessions' output cannot reach this daemon; rebuild the runner image")
                return false
            }
            is OrchestratorReply.Unavailable -> { live.remove(runner); return false }
            is OrchestratorReply.Ok -> {
                toldOld.remove(runner)
                live += runner
                for ((id, frames) in reply.value.frames.groupBy { it.sessionId }) if (id != null && id in resumeFrom) accept(runner, id, frames)
                return true
            }
        }
    }

    /** Apply what of [frames] is newer than what was taken of [sessionId]; one session at a time, so nothing goes out twice. */
    fun accept(runner: String, sessionId: String, frames: List<Envelope>) = synchronized(locks.computeIfAbsent(sessionId) { Any() }) {
        val after = takenBefore(sessionId)
        val fresh = frames.filter { (it.sequence ?: 0L) > after }.sortedBy { it.sequence ?: 0L }
        if (fresh.isNotEmpty()) {
            apply(runner, sessionId, fresh)
            onTaken(sessionId, fresh.last().sequence ?: after)
        }
    }

    /** [sessionId] was deleted: its lock goes. */
    fun forget(sessionId: String) {
        locks.remove(sessionId)
        sessionRunner.remove(sessionId)
    }

    private fun loop(runner: String) {
        val me = Thread.currentThread()
        while (!closed && loops[runner] === me) {
            try {
                if (!round(runner)) Thread.sleep(idleMs().coerceAtLeast(1))
            } catch (e: InterruptedException) {
                live.remove(runner)
                return
            } catch (e: Exception) {
                live.remove(runner)
                log("[infantry] runner '$runner': frame stream failed: ${e.message}")
                runCatching { Thread.sleep(idleMs().coerceAtLeast(1)) }.onFailure { return }
            }
        }
        live.remove(runner)
    }

    companion object {
        /** The stream frame types a runner session's chat is made of; with `status` they are re-published on this daemon's stream. */
        val RELAYED = setOf(
            Envelope.TURN, Envelope.TEXT_DELTA, Envelope.REASONING, Envelope.ERROR, Envelope.STDERR, Envelope.STDOUT,
            Envelope.EXIT, Envelope.FINISHED, Envelope.USAGE,
        )
    }

    override fun close() {
        closed = true
        loops.values.forEach { it.interrupt() }
        loops.clear()
        live.clear()
    }
}
