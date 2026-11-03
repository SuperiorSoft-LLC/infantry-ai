// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.protocol.SystemKind
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TICKET-HANDOVER: switching a TICKET worker's harness without losing its task.
 *
 * The bug it fixes: [SessionManager.reharness] left the session never-run on the new harness, and its next turn
 * (usually a mail) was composed as a generic first turn (persona + identity) plus the replayed conversation
 * (HARNESS-CARRY-1). The ticket brief was the session's FIRST turn, so the replay summarised it to one line once the
 * conversation outgrew the replay budget, and lost it outright after a daemon restart (the conversation log is in
 * memory). The worker came back on the new harness not knowing its task.
 *
 * The switch of a ticketed session now runs in four steps, off the request thread:
 *  1. the session is HELD ([SessionManager.hold]): no mail or prompt can start a turn on it until the switch ends;
 *  2. the old agent gets one last turn on its old harness, [HANDOFF_PROMPT], asking for a handoff note. The switch
 *     waits for that turn, at most [timeoutMs]; a turn still running then is interrupted (a stuck agent cannot block
 *     the switch). No handoff turn when the session cannot resume on its old harness (it never ran, or that harness
 *     is no longer configured);
 *  3. the session moves to the new harness ([SessionManager.reharness]): same id, same ticket binding;
 *  4. the new harness's first run starts AT ONCE with the ticket's full start brief (the composer a fresh ticket start
 *     uses, [brief]) followed by the handoff note under [RESUMING_HEADING]. With no note (timeout, crash, an empty
 *     reply) it says so under [NO_HANDOFF_HEADING], and the conversation so far is replayed as before (HARNESS-CARRY-1).
 *
 * Where the note lives: it is the visible text of the handoff turn's reply, read from the Infantry transcript
 * ([SessionManager.lastReply]) and written straight into the new run's first prompt. Nothing is written to the
 * worktree (a file there could be committed by the worker); the note stays readable in the session's transcript.
 *
 * Unticketed sessions are not handed over: they keep HARNESS-CARRY-1 as it was ([applies]).
 */
class SessionHandover(
    private val manager: SessionManager,
    /** The ticket start brief for a ticketed session, null when it has none ([com.superiorsoftllc.infantry.backlog.TicketBoard.brief]). */
    private val brief: (SessionRecord) -> String?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val pollMs: Long = 100,
) : AutoCloseable {
    /** Where one switch is headed. A null model or effort = that harness's default. */
    data class Target(val provider: String, val model: String?, val effort: String?)

    /** What a switch did: whether a handoff note came back, and why not when it did not. */
    data class Outcome(val note: String?, val noNoteReason: String?)

    private val worker: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "infantry-handover").apply { isDaemon = true }
    }

    /** True when switching [session] to another harness goes through a handover: it works on a ticket. */
    fun applies(session: SessionRecord): Boolean = !session.ticketId.isNullOrBlank()

    /**
     * Start the handover of [sessionId] and return at once. [target] is read when the session actually moves, so a
     * later change of the agent's executor is the one that lands. Refused (IllegalArgumentException, nothing started)
     * when a switch of this session is already in progress.
     */
    fun begin(sessionId: String, target: () -> Target) {
        require(manager.hold(sessionId)) { "this session is already switching; wait for that switch to finish" }
        try {
            worker.execute {
                try {
                    run(sessionId, target)
                } catch (e: Exception) {
                    System.err.println("[infantry] handover of $sessionId failed: ${e.message}")
                } finally {
                    manager.release(sessionId)
                }
            }
        } catch (e: Exception) {
            manager.release(sessionId)
            throw e
        }
    }

    /** The whole handover, on the calling thread. The caller holds [sessionId] ([begin]). */
    internal fun run(sessionId: String, target: () -> Target): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        val before = manager.get(sessionId) ?: throw IllegalArgumentException("unknown session '$sessionId'")
        // A turn already running (the swap raced one) finishes first, inside the same budget.
        settle(sessionId, deadline)
        var note: String? = null
        var reason: String? = null
        val canHandOff = before.providerSessionId != null && manager.providerFor(before.provider) != null
        if (!canHandOff) {
            reason = "the previous session could not be asked for one (it never ran, or its harness is gone)"
        } else {
            val asked = runCatching { manager.resumeHeld(sessionId, HANDOFF_PROMPT, MessageOrigin.System(SystemKind.HANDOVER)) }
            if (asked.isFailure) {
                reason = "the previous session could not be asked for one (${asked.exceptionOrNull()?.message})"
            } else if (!settle(sessionId, deadline)) {
                reason = "the previous agent did not finish its handoff within ${timeoutMs / 1000}s"
            } else {
                note = manager.lastReply(sessionId)
                if (note == null) {
                    reason = if (manager.get(sessionId)?.state == SessionState.FAILED) "the handoff turn failed" else "the handoff reply was empty"
                }
            }
        }
        val to = target()
        manager.reharness(sessionId, to.provider, to.model, to.effort, carry = note == null)
        val moved = manager.get(sessionId) ?: return Outcome(note, reason)
        val ticketBrief = brief(moved)
        if (ticketBrief == null) {
            // No ticket to brief from (deleted meanwhile): leave the session at rest, as an untracked switch does.
            return Outcome(note, reason)
        }
        manager.resumeHeld(sessionId, resumePrompt(ticketBrief, note, reason), MessageOrigin.System(SystemKind.HANDOVER))
        return Outcome(note, reason)
    }

    /**
     * Wait until [sessionId] has no turn in flight. Past [deadline] the turn is interrupted and given a short grace to
     * come to rest. True = it finished on its own.
     */
    private fun settle(sessionId: String, deadline: Long): Boolean {
        while (manager.inFlight(sessionId)) {
            if (System.currentTimeMillis() >= deadline) {
                runCatching { manager.interrupt(sessionId) }
                val grace = System.currentTimeMillis() + INTERRUPT_GRACE_MS
                while (manager.inFlight(sessionId) && System.currentTimeMillis() < grace) Thread.sleep(pollMs)
                return false
            }
            Thread.sleep(pollMs)
        }
        return true
    }

    override fun close() {
        worker.shutdownNow()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    companion object {
        /** How long the old agent gets to write its handoff note. */
        const val DEFAULT_TIMEOUT_MS: Long = 180_000

        private const val INTERRUPT_GRACE_MS: Long = 5_000

        /** The old agent's last turn. */
        const val HANDOFF_PROMPT: String =
            "HANDOFF: you are being switched to another harness/model. This is your last turn: do not start new work. " +
                "Reply with a handoff note for the agent that takes over this ticket, in this order: " +
                "1) what is done; 2) what is in progress; 3) the files you touched; " +
                "4) the branch and its last commit sha (commit or stash nothing new now, just report); " +
                "5) what to do next; 6) any traps or gotchas. Keep it short and factual."

        /** Heads the handoff note in the new harness's first prompt. */
        const val RESUMING_HEADING: String =
            "YOU ARE RESUMING THIS TICKET. Another agent worked on it before you; here is the handoff note it left on where it stopped:"

        /** Heads the new harness's first prompt when no note came back. */
        const val NO_HANDOFF_HEADING: String = "YOU ARE RESUMING THIS TICKET. Another agent worked on it before you and left no handoff note"

        /** The new harness's first prompt: the full ticket [brief], then where the previous agent stopped. */
        fun resumePrompt(brief: String, note: String?, noNoteReason: String?): String {
            val resuming = if (note != null) {
                "$RESUMING_HEADING\n\n${note.trim()}\n\nCheck the branch (git status, git log) against this note before you continue."
            } else {
                "$NO_HANDOFF_HEADING (${noNoteReason ?: "no reason recorded"}). " +
                    "Check the branch (git status, git log) to see what is already done before you continue."
            }
            return "$brief\n\n$resuming"
        }
    }
}
