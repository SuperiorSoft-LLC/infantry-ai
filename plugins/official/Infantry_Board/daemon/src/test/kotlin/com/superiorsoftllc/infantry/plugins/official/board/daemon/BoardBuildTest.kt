package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
import com.superiorsoftllc.infantry.daemon.api.board.NoBoardHost
import com.superiorsoftllc.infantry.backlog.BoardFromRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DP-5h-3a2: the plugin builds the board, story shims and epic handler from a fake runtime (API-level inputs only). */
class BoardBuildTest {
    private class FakeRuntime(val epicRows: MutableList<String> = mutableListOf()) : BoardRuntime {
        override val host: BoardHost = NoBoardHost
        override val epics = object : BoardEpicInputs {
            override fun loadAll() = epicRows.toList()
            override fun upsert(epicJson: String) { epicRows += epicJson }
        }
    }

    @Test
    fun buildsTheHandlersFromTheRuntimeInputs() {
        val runtime = FakeRuntime()
        val board = BoardFromRuntime.build(runtime)
        val h = BoardBuild.handlers(runtime, board)
        assertNotNull(h.stories)
        assertNotNull(h.storyBoard)
        // an epic's story ids are derived from the board's list, so the epic handler reaches the board built over the runtime's
        // host: NoBoardHost has no storage, and that is exactly the answer (the handler is wired to THIS board).
        val answer = h.epics.answer(Envelope(Envelope.EPIC_LIST, "p1"))
        assertNotNull(answer)
        assertTrue(answer.data.contains("no board storage"), answer.data)
    }
}
