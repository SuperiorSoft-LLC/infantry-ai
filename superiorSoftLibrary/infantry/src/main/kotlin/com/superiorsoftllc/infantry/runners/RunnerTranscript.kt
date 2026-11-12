// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * RUNNER-REMOTE-IO-1: the lines a runner serves for a session's on-demand transcript read. Harness-neutral: it reads only the
 * session stream's frame types. The raw process lines (STDOUT, STDERR) come first-class; the turns the session was asked (TURN) and
 * its errors (ERROR) are added, so a session that printed nothing still shows what it ran. When a harness printed no raw line, its
 * normalised answer text (TEXT_DELTA, joined until the next other frame) stands in, so no harness leaves the read empty.
 * SPAWN-PARAMS-1: the daemon's own notices (STDERR lines marked [NOTICE], "session started" on every run) are not the harness
 * printing: they no longer hide the answer text of a harness that prints no raw line (opencode's "pong" read empty).
 */
object RunnerTranscript {
    fun lines(frames: List<Envelope>): List<String> {
        val raw = frames.any { it.type == Envelope.STDOUT || (it.type == Envelope.STDERR && !it.data.startsWith(NOTICE)) }
        val out = ArrayList<String>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) { text.toString().trimEnd('\n').lines().forEach(out::add); text.setLength(0) }
        }
        for (f in frames) when (f.type) {
            Envelope.STDOUT, Envelope.STDERR -> { flush(); out += f.data }
            Envelope.TURN -> { flush(); f.data.lines().forEachIndexed { i, l -> out += if (i == 0) "[turn] $l" else l } }
            Envelope.ERROR -> { flush(); out += "error: ${f.data}" }
            Envelope.TEXT_DELTA -> if (!raw) text.append(f.data) else Unit
            else -> flush()
        }
        flush()
        return out
    }

    /** The marker the daemon puts on its own stderr notices (SessionManager), never a harness's line. */
    const val NOTICE = "[infantry]"
}
