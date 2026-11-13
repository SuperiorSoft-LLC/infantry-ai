// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.FramesBatch
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.protocol.Envelope
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUNNER-UNIFY-3: the pushed stream. The runner's feed answers a held pull the moment a frame is published, only for the sessions the
 * orchestrator names and started there; the orchestrator's subscription applies each frame once, and only while `runners.pushFrames`
 * is on and the runner can push (otherwise the poll path serves it).
 */
class RunnerFrameStreamTest {
    private fun frame(type: String, sid: String, seq: Long, data: String = "") = Envelope(type, data, sid, sequence = seq)

    @Test
    fun `the feed answers frames after the resume point, only for named sessions it holds, bounded`() {
        val kept = mapOf(
            "a" to listOf(frame(Envelope.TURN, "a", 1), frame(Envelope.STATUS, "a", 2), frame(Envelope.TEXT_DELTA, "a", 3)),
            "b" to listOf(frame(Envelope.TEXT_DELTA, "b", 1)),
            "x" to listOf(frame(Envelope.TEXT_DELTA, "x", 1)),
        )
        val feed = RunnerFrameFeed(sessions = { setOf("a", "b", "x") }, framesAfter = { id, after -> kept[id].orEmpty().filter { it.sequence!! > after } })
        // "x" is not named (the orchestrator forgot it), "c" is named but not this orchestrator's: neither is sent.
        val all = feed.pull(mapOf("a" to 1L, "b" to 0L, "c" to 0L), waitMs = 0, max = 0)
        assertEquals(listOf("a" to 2L, "a" to 3L, "b" to 1L), all.frames.map { it.sessionId to it.sequence })
        assertEquals(Envelope.STATUS, all.frames.first().type, "status (run activity) crosses")
        assertFalse(all.more)
        val cut = feed.pull(mapOf("a" to 0L, "b" to 0L), waitMs = 0, max = 2)
        assertEquals(2, cut.frames.size)
        assertTrue(cut.more)
    }

    @Test
    fun `a held pull is answered the moment a frame is published, and empty after its wait`() {
        val kept = CopyOnWriteArrayList<Envelope>()
        val feed = RunnerFrameFeed(sessions = { setOf("a") }, framesAfter = { _, after -> kept.filter { it.sequence!! > after } })
        assertTrue(feed.pull(mapOf("a" to 0L), waitMs = 50, max = 0).frames.isEmpty())
        thread { Thread.sleep(150); kept += frame(Envelope.TEXT_DELTA, "a", 1, "pong"); feed.published() }
        val started = System.nanoTime()
        val answer = feed.pull(mapOf("a" to 0L), waitMs = 20_000, max = 0)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("pong"), answer.frames.map { it.data })
        assertTrue(tookMs < 10_000, "answered on publish, not at the end of the wait (took $tookMs ms)")
    }

    private class FakeLink(var reply: OrchestratorReply<FramesBatch>?) : RunnerLink {
        val asked = mutableListOf<Map<String, Long>>()
        override fun ticketIdOf(sessionId: String): String? = null
        override fun pullFrames(resumeFrom: Map<String, Long>, waitMs: Long, max: Int): OrchestratorReply<FramesBatch>? {
            asked += resumeFrom
            return reply
        }
    }

    private class Harness(link: FakeLink, var on: Boolean = true) {
        val taken = HashMap<String, Long>()
        val applied = mutableListOf<Envelope>()
        val stream = RunnerFrameStream(
            enabled = { on },
            sessionsOn = { listOf("s1") },
            link = { link },
            takenBefore = { taken[it] ?: 0L },
            apply = { _, _, fresh -> applied += fresh },
            onTaken = { sid, seq -> taken[sid] = seq },
            waitMs = { 0L }, maxFrames = { 0 }, idleMs = { 1L },
        )
    }

    @Test
    fun `pushed frames are applied once each, from the resume point, and the runner counts as streaming`() {
        val link = FakeLink(OrchestratorReply.Ok(FramesBatch(listOf(frame(Envelope.TURN, "s1", 1), frame(Envelope.STATUS, "s1", 2), frame(Envelope.TEXT_DELTA, "other", 1)))))
        val h = Harness(link)
        assertTrue(h.stream.round("r1"))
        assertTrue(h.stream.streaming("r1"))
        assertTrue(h.stream.streamingSession("s1"))
        // The same answer again (a retried call): nothing goes out twice.
        assertTrue(h.stream.round("r1"))
        assertEquals(listOf(1L, 2L), h.applied.map { it.sequence })
        assertEquals(setOf("s1"), h.applied.map { it.sessionId }.toSet(), "a session this orchestrator did not name is dropped")
        assertEquals(listOf(mapOf("s1" to 0L), mapOf("s1" to 2L)), link.asked)
    }

    @Test
    fun `flag off, an older runner or an unreachable one is not streaming, so the poll path serves it`() {
        val link = FakeLink(OrchestratorReply.Ok(FramesBatch(listOf(frame(Envelope.TURN, "s1", 1)))))
        val h = Harness(link, on = false)
        assertFalse(h.stream.round("r1"))
        assertFalse(h.stream.streaming("r1"))
        assertTrue(link.asked.isEmpty(), "flag off: no link call at all")

        h.on = true
        link.reply = null // an older runner: not offered
        assertFalse(h.stream.round("r1"))
        assertFalse(h.stream.streaming("r1"))

        link.reply = OrchestratorReply.Unavailable("down")
        assertFalse(h.stream.round("r1"))
        assertFalse(h.stream.streaming("r1"))
        assertTrue(h.applied.isEmpty())
    }
}
