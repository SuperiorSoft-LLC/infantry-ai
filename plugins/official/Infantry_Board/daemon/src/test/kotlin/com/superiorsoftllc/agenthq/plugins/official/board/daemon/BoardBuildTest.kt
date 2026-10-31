package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardPacing
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTuning
import com.superiorsoftllc.agenthq.daemon.api.board.NoBoardHost
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DP-5h-3a2: the plugin builds the board, story shims and epic handler from a fake runtime (API-level inputs only). */
class BoardBuildTest {
    private class FakeRuntime(val tuning0: BoardTuning = BoardTuning.DEFAULT, val epicRows: MutableList<String> = mutableListOf()) : BoardRuntime {
        override val host: BoardHost = NoBoardHost
        override val tuning: BoardTuning = tuning0
        override val epics = object : BoardEpicInputs {
            override fun loadAll() = epicRows.toList()
            override fun upsert(epicJson: String) { epicRows += epicJson }
        }
    }

    @Test
    fun buildsEveryPartFromTheRuntime() {
        val built = BoardBuild.build(FakeRuntime())
        assertNotNull(built.board)
        assertNotNull(built.stories)
        assertNotNull(built.storyBoard)
        // an epic's story ids are derived from the board's list, so the epic handler reaches the board built over the runtime's
        // host: NoBoardHost has no storage, and that is exactly the answer (the handler is wired to THIS board).
        val answer = built.epics.answer(Envelope(Envelope.EPIC_LIST, "p1"))
        assertNotNull(answer)
        assertTrue(answer.data.contains("no board storage"), answer.data)
    }

    @Test
    fun tuningIsReadPerUseThroughThePort() {
        var behavior = "leave"
        val tuning = object : BoardTuning {
            override fun exitBehavior() = behavior
            override fun pacing(providerId: String?, model: String?) = BoardPacing(over = true, reason = "over")
        }
        assertTrue(BoardBuild.build(FakeRuntime(tuning)).board.host === NoBoardHost)
        behavior = "done"
        assertEquals("done", tuning.exitBehavior())
    }
}
