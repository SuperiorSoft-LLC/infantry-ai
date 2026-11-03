// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.sessions

import com.superiorsoftllc.infantry.daemon.api.progress.ProgressText
import java.util.concurrent.TimeUnit

/**
 * RUNTIME-LOAD-5: how a session's activity reads, in one place, so `infantry board`, the session tools and the board tool print
 * the same words (moved unchanged from core's `BoardSummary`; core delegates here).
 */
object SessionText {
    /** The live-state words, shared with the background-work card: busy / idle / running background process. */
    fun liveState(session: SessionView): String = when {
        session.backgroundWork != null -> "working (background: ${session.backgroundWork.take(40)})"
        session.state == "BUSY" || session.state == "STARTING" -> "busy"
        session.state == "WAITING_INPUT" -> "waiting for input"
        session.state == "IDLE" -> "idle"
        else -> session.state.lowercase()
    }

    /** "3m ago" style age of [at] against [now]; "never" when [at] is null. */
    fun ago(at: Long?, now: Long): String {
        if (at == null) return "never"
        val s = TimeUnit.MILLISECONDS.toSeconds((now - at).coerceAtLeast(0))
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    /** Session activity in one phrase: "idle; last action 'Bash: ls' (last output 2m ago)". */
    fun activity(session: SessionView, now: Long): String =
        liveState(session) + (session.lastAction?.let { "; last action '$it'" } ?: "") +
            " (last output ${ago(session.lastOutputAt, now)}, last event ${ago(session.lastEventAt, now)})" +
            // Contract 1.117.0: the agent's own progress report, so the PM can follow a gate without the transcript.
            (session.progress?.let { "; progress " + ProgressText.line(it, now) } ?: "")

    /** What a session tool prints as its status: the run phase `compacting` wins over the state. */
    fun status(session: SessionView): String = if (session.runPhase == "compacting") "compacting" else session.state
}
