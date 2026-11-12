// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.protocol.Envelope
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-OUTPUT-1: puts a remote session's output on the orchestrator's own session stream, so the app's chat, a later replay
 * (selecting the session) and `get_session` read it exactly as they read a local session's.
 *
 * Before, the orchestrator kept nothing for a session on a runner: its replay handler sent the runner's transcript lines as
 * unsequenced `stdout` frames, which every client drops (raw harness output is not chat), and nothing was streamed live, so the
 * chat read "Nothing on screen for this session yet" while the runner held the answer.
 *
 * [pull] reads the runner's transcript ([RunnerTranscript.lines]) from the last line it took and publishes each new line as the
 * chat frame it stands for: `[turn] ...` -> `turn`, `error: ...` -> `error`, a JSON line (a harness's raw stream) -> `stdout`
 * (kept, not drawn, as for a local session), a daemon notice -> `stderr`, any other line -> `text_delta`. The runner joins a
 * running answer's text into lines, so its last line can still grow: that line is read again on the next pull and only what it
 * gained goes out, as a `text_delta` that continues the same message. So the chat fills while the turn runs. Harness-neutral.
 *
 * [read] reads lines from a runner (session, fromLine) -> lines, or null when no runner holds the session; [publish] puts frames
 * on the orchestrator's session stream (sequenced, kept, sent to every client). [takenBefore] / [onTaken] keep the count of lines
 * taken across a restart, so nothing is published twice.
 */
class RemoteTranscriptPump(
    private val read: (sessionId: String, fromLine: Int) -> OrchestratorReply<List<String>>?,
    private val publish: (frames: List<Envelope>) -> Unit,
    private val takenBefore: (sessionId: String) -> Int = { 0 },
    private val onTaken: (sessionId: String, lines: Int) -> Unit = { _, _ -> },
) {
    /** What was taken of one session: [lines] transcript lines, the last of them [last] (null = none known), and its kind. */
    private class Taken(val lines: Int, val last: String?, val lastIsText: Boolean)

    private val taken = ConcurrentHashMap<String, Taken>()
    /** session -> its lock: two pulls of one session (an event and a replay) never publish the same lines twice. */
    private val locks = ConcurrentHashMap<String, Any>()

    /** Read and publish what is new for [sessionId]; the answer says why nothing came (null = read fine). */
    fun pull(sessionId: String): String? = synchronized(locks.computeIfAbsent(sessionId) { Any() }) {
        val t = taken.getOrPut(sessionId) { Taken(takenBefore(sessionId), null, false) }
        // The last line taken is read again: a running answer may have grown it.
        val again = t.last != null && t.lines > 0
        val from = if (again) t.lines - 1 else t.lines
        val lines = when (val reply = read(sessionId, from)) {
            null -> return "no runner holds session '$sessionId'"
            is OrchestratorReply.Unavailable -> return reply.reason
            is OrchestratorReply.Ok -> reply.value
        }
        if (lines.isEmpty()) return null
        val out = ArrayList<Envelope>()
        val fresh = if (again) {
            val first = lines.first()
            val last = t.last!!
            if (t.lastIsText && first.length > last.length && first.startsWith(last)) out += Envelope(Envelope.TEXT_DELTA, first.substring(last.length), sessionId)
            lines.drop(1)
        } else lines
        out += frames(sessionId, fresh, continuesText = t.lastIsText)
        if (out.isNotEmpty()) publish(out)
        val total = from + lines.size
        val lastLine = lines.last()
        taken[sessionId] = Taken(total, lastLine, if (fresh.isEmpty()) t.lastIsText else kindOf(lastLine) == Envelope.TEXT_DELTA)
        if (total != t.lines) onTaken(sessionId, total)
        null
    }

    /** [sessionId] was deleted or moved: start over if it comes back. */
    fun forget(sessionId: String) {
        taken.remove(sessionId)
        locks.remove(sessionId)
    }

    companion object {
        private const val TURN = "[turn] "
        private const val ERROR = "error: "

        private fun kindOf(line: String): String = when {
            line.startsWith(TURN) -> Envelope.TURN
            line.startsWith(ERROR) -> Envelope.ERROR
            line.trimStart().startsWith("{") -> Envelope.STDOUT
            line.startsWith(RunnerTranscript.NOTICE) -> Envelope.STDERR
            else -> Envelope.TEXT_DELTA
        }

        /**
         * The chat frames [lines] stand for, in order; consecutive answer lines are one `text_delta` (one message). With
         * [continuesText] the first answer line continues a message already published, so it starts on a new line of it.
         */
        fun frames(sessionId: String, lines: List<String>, continuesText: Boolean = false): List<Envelope> {
            val out = ArrayList<Envelope>()
            val text = StringBuilder()
            var continuing = continuesText
            fun flush() {
                if (text.isNotEmpty()) { out += Envelope(Envelope.TEXT_DELTA, text.toString(), sessionId); text.setLength(0) }
            }
            for (line in lines) when (val kind = kindOf(line)) {
                Envelope.TEXT_DELTA -> { if (text.isNotEmpty() || continuing) text.append('\n'); text.append(line); continuing = false }
                else -> {
                    flush()
                    continuing = false
                    out += Envelope(kind, if (kind == Envelope.TURN) line.removePrefix(TURN) else if (kind == Envelope.ERROR) line.removePrefix(ERROR) else line, sessionId)
                }
            }
            flush()
            return out
        }
    }
}
