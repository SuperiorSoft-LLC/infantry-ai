// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runners.RunnerFrameFeed
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-RESTART-FRAMES-1: a session's stream sequence survives a restart of the process that numbers it. A runner container
 * restart used to number an existing session's next turn from the few lines of its kept chat, far below the cursor its
 * orchestrator persisted, so every new frame was dropped as already taken and the app never showed the reply.
 */
class SessionFrameSequencerRestartTest {
    private val dir: File = Files.createTempDirectory("seq-restart").toFile()

    private fun store() = SessionHistoryStore(dir, { 200 })

    /** One "process": a store over [dir] and a sequencer on it, seeded from disk like the daemon does at start. */
    private fun boot(known: Set<String>): Pair<SessionHistoryStore, SessionFrameSequencer> {
        val history = store()
        val sequencer = SessionFrameSequencer(history)
        for ((id, loaded) in history.loadAll(known)) sequencer.seed(id, loaded)
        return history to sequencer
    }

    /** A turn as a runner streams it: many live-state frames that the kept chat leaves out, a few it keeps. */
    private fun turn(seq: SessionFrameSequencer, id: String, prompt: String, statuses: Int = 20) {
        seq.publish(emptyList(), listOf(Envelope(Envelope.TURN, prompt, id)))
        repeat(statuses) { seq.publish(emptyList(), listOf(Envelope(Envelope.STATUS, """{"state":"BUSY","runActivity":{"state":"THINKING"}}""", id))) }
        seq.publish(emptyList(), listOf(Envelope(Envelope.TEXT_DELTA, "po", id)))
        seq.publish(emptyList(), listOf(Envelope(Envelope.TEXT_DELTA, "ng", id)))
        seq.publish(emptyList(), listOf(Envelope(Envelope.FINISHED, """{"outcome":"SUCCEEDED"}""", id)))
    }

    @Test
    fun anExistingSessionsNextTurn_reachesTheOrchestratorCursor_afterARestart() {
        val (history1, seq1) = boot(setOf("s1"))
        repeat(4) { turn(seq1, "s1", "q$it") }
        val taken = seq1.replayAfter("s1", 0)!!.frames.last().sequence!!
        assertEquals(96L, taken)
        history1.flush()

        // The runner restarts: its kept chat holds a few items, far fewer than the sequences the orchestrator took.
        val (_, seq2) = boot(setOf("s1"))
        val seeded = seq2.replayAfter("s1", 0)!!.frames
        assertTrue(seeded.size < taken, "the kept chat is smaller than the stream (${seeded.size} items)")
        assertEquals(taken, seeded.last().sequence, "the kept chat ends at the session's last sequence, not at its item count")

        turn(seq2, "s1", "ping after runner restart")
        val feed = RunnerFrameFeed(sessions = { listOf("s1") }, framesAfter = { id, after -> seq2.replayAfter(id, after)?.frames.orEmpty() })
        val pulled = feed.pull(mapOf("s1" to taken), waitMs = 0, max = 0).frames
        assertEquals(Envelope.TURN, pulled.first().type)
        assertEquals("ping after runner restart", pulled.first().data)
        assertEquals("pong", pulled.filter { it.type == Envelope.TEXT_DELTA }.joinToString("") { it.data })
        assertEquals(taken + 1, pulled.first().sequence, "the new turn continues the numbering")
    }

    @Test
    fun aSessionWithNoKeptChat_stillContinuesItsNumbering() {
        val (history1, seq1) = boot(setOf("s2"))
        repeat(30) { seq1.publish(emptyList(), listOf(Envelope(Envelope.STATUS, """{"state":"BUSY"}""", "s2"))) }
        history1.flush()

        val (_, seq2) = boot(setOf("s2"))
        seq2.publish(emptyList(), listOf(Envelope(Envelope.TURN, "again", "s2")))
        assertEquals(31L, seq2.replayAfter("s2", 30)!!.frames.single().sequence)
    }

    @Test
    fun aClientReplayingFromZero_isToldOnlyOfItemsReallyDropped() {
        val (history1, seq1) = boot(setOf("s3"))
        turn(seq1, "s3", "hello")
        history1.flush()

        val (_, seq2) = boot(setOf("s3"))
        val batch = seq2.replayAfter("s3", 0)!!
        val gap = batch.gap!!
        assertEquals(0L, gap.oldestAvailableSequence - 1 - gap.requestedAfterSequence, "nothing was dropped: the gap only moves the cursor")
        assertEquals(0L, gap.evictedCount)
        assertEquals(listOf(Envelope.TURN, Envelope.TEXT_DELTA, Envelope.FINISHED), batch.frames.map { it.type })
        assertNull(seq2.replayAfter("s3", batch.frames.last().sequence!!)!!.gap, "a current cursor draws no gap")
    }

    @Test
    fun aDeletedSession_losesItsMark() {
        val (history1, seq1) = boot(setOf("s4"))
        turn(seq1, "s4", "x")
        history1.flush()
        assertTrue(File(dir, "s4" + SessionHistoryStore.SEQ_SUFFIX).isFile)
        seq1.forget("s4")
        history1.flush()
        assertTrue(!File(dir, "s4" + SessionHistoryStore.SEQ_SUFFIX).exists())
        assertEquals(0L, store().highWater("s4"))
    }
}
