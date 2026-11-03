package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
import com.superiorsoftllc.infantry.protocol.TicketSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardFromRuntimeTest {
    private class Fake(override val host: BoardHost = InMemoryBoardHost(), val settings: MutableMap<String, String> = mutableMapOf()) : BoardRuntime {
        val pushed = mutableListOf<Frame>()
        override fun setting(key: String) = settings[key]
        override fun push(frames: List<Frame>) { pushed += frames }
    }

    @Test fun aBoardBuiltFromTheRuntimeSavesAndPushesTheTicketFrame() {
        val rt = Fake()
        val board = BoardFromRuntime.build(rt)
        val saved = board.save(TicketSave(title = "From the plugin"))
        assertEquals("From the plugin", saved.ticket.title)
        assertTrue(rt.pushed.any { it.type == "ticket" && it.data.contains("From the plugin") }, rt.pushed.toString())
    }

    @Test fun unreadableStoredSettingsFallBackToTheDefaults() {
        val rt = Fake(settings = mutableMapOf("tickets.weightExecutors" to "not json", "tickets.exitBehavior" to "\"bogus\""))
        val board = BoardFromRuntime.build(rt)
        // Building and using the board must not throw on a bad stored value.
        assertEquals("x", board.save(TicketSave(title = "x")).ticket.title)
    }
}
