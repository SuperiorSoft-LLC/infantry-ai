// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals

/** RUNNER-REMOTE-IO-1: the runner's on-demand transcript carries harness output, whichever frames the harness produced. */
class RunnerTranscriptTest {
    private fun f(type: String, data: String) = Envelope(type, data, "s1")

    @Test
    fun `raw lines come with the turn and the error`() {
        val lines = RunnerTranscript.lines(listOf(f(Envelope.TURN, "do it"), f(Envelope.STDOUT, "echo: do it"), f(Envelope.TEXT_DELTA, "echo: do it\n"), f(Envelope.ERROR, "boom")))
        assertEquals(listOf("[turn] do it", "echo: do it", "error: boom"), lines)
    }

    @Test
    fun `a harness with no raw line is read from its answer text`() {
        val lines = RunnerTranscript.lines(listOf(f(Envelope.TURN, "hi"), f(Envelope.TEXT_DELTA, "hel"), f(Envelope.TEXT_DELTA, "lo\nworld\n"), f(Envelope.FINISHED, "{}")))
        assertEquals(listOf("[turn] hi", "hello", "world"), lines)
    }

    @Test
    fun `the daemon's own notices do not hide the answer text`() {
        val lines = RunnerTranscript.lines(listOf(f(Envelope.TURN, "ping"), f(Envelope.STDERR, "[infantry] * session started (opencode)"), f(Envelope.TEXT_DELTA, "pong"), f(Envelope.FINISHED, "{}")))
        assertEquals(listOf("[turn] ping", "[infantry] * session started (opencode)", "pong"), lines)
    }

    @Test
    fun `nothing kept reads empty`() = assertEquals(emptyList(), RunnerTranscript.lines(emptyList()))
}
