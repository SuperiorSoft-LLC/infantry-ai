// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pi turn streams its thinking as mid-word `thinking_delta`s (fixture resources/pi/thinking-stream.jsonl, the shape pi
 * prints). The real daemon must put each thinking block on the wire, and in a replay, as ONE `reasoning` frame; before the
 * fix every delta was its own frame, which the chat joined with newlines, breaking sentences mid-word.
 */
class PiReasoningDaemonTest {
    private fun fixtureLines(name: String) =
        javaClass.getResource("/pi/$name")!!.readText().lines().filter { it.isNotBlank() }

    private val expected = listOf("The user wants the file listed, so I should run ls.\n\nThen reply.", "Second block, never closed.")

    @Test
    fun streamedPiThinkingIsOneReasoningFramePerBlockLiveAndInReplay() {
        val fixture = DaemonFixture.launch(StubScript(stdoutLines = fixtureLines("thinking-stream.jsonl")))
        try {
            fixture.send(Envelope(Envelope.PROMPT, "list it"))
            val finished = fixture.client.await(Envelope.FINISHED)
            val sessionId = finished.sessionId!!

            assertEquals(expected, fixture.client.of(Envelope.REASONING).map { it.data }, "live frames")
            // The text between the blocks keeps its place: block 1, text, block 2.
            val order = fixture.client.frames.filter { it.type == Envelope.REASONING || it.type == Envelope.TEXT_DELTA }.map { it.type }
            assertEquals(listOf(Envelope.REASONING, Envelope.TEXT_DELTA, Envelope.REASONING), order)

            val reader = fixture.connectClient()
            reader.send(Envelope("replay_request", """{"lastSequence":0}""", sessionId))
            reader.awaitWhere("the replayed finish") { it.type == Envelope.FINISHED && it.sessionId == sessionId }
            assertEquals(expected, reader.frames.filter { it.type == Envelope.REASONING }.sortedBy { it.sequence }.map { it.data }, "replayed frames")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun claudeThinkingBlocksStayOneFrameEach() {
        val fixture = DaemonFixture.launch(
            StubScript(
                stdoutLines = listOf(
                    """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"first thought"}]}}""",
                    """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"second thought"}]}}""",
                ),
            ),
        )
        try {
            fixture.send(Envelope(Envelope.PROMPT, "go"))
            fixture.client.await(Envelope.FINISHED)
            assertEquals(listOf("first thought", "second thought"), fixture.client.of(Envelope.REASONING).map { it.data })
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
