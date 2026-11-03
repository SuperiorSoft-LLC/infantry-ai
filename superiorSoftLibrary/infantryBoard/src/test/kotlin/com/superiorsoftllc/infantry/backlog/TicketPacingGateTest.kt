package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.context.ComposedContext
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.usage.PacingVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** USAGE-PACE (contract 1.121.0): a start past the plan allowance is refused for the daemon / an agent, warned for the owner. (DP-5f-6: over the board-side fake host.) */
class TicketPacingGateTest {
    private val host = InMemoryBoardHost()
    private val asked = mutableListOf<String?>()
    private var verdict: PacingVerdict = PacingVerdict.Allow()
    private lateinit var board: TicketBoard

    private fun ticket(): String {
        val worker = host.agents.save(BoardAgent(name = "Backend", role = "backend"))
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        board = TicketBoard(
            host, idFactory = { ids.next() }, context = prompting, profilesAvailable = true,
            pacing = { provider, _ -> asked += provider; verdict },
        )
        return board.save(TicketSave(title = "Work", agentId = worker.id)).ticket.id
    }

    @Test
    fun underTheAllowanceItStarts() {
        val id = ticket()
        assertEquals("started", board.startNext().ticket.start?.reason)
        assertEquals(id, board.get(id).id)
        assertEquals(listOf<String?>(null), asked, "the gate is asked with the run's provider (null = the daemon default)")
    }

    @Test
    fun overTheAllowanceTheDaemonsStartNextIsRefusedWithTheReason() {
        val id = ticket()
        verdict = PacingVerdict.Over("usage pacing: claude at 85% (5h), allowance 1 (rule), running 1")
        val e = assertFailsWith<IllegalArgumentException> { board.startNext() }
        assertTrue(e.message.orEmpty().contains("usage pacing: claude at 85%"), "the refusal tells why: ${e.message}")
        assertTrue(board.get(id).status != IN_PROGRESS, "nothing started")
    }

    @Test
    fun overTheAllowanceTheOwnersMoveStartsWithAWarning() {
        val id = ticket()
        verdict = PacingVerdict.Over("usage pacing: claude at 85% (5h), allowance 1 (rule), running 1")
        val start = board.move(TicketMove(id, IN_PROGRESS)).ticket.start!!
        assertEquals("started", start.reason)
        assertTrue(start.detail.orEmpty().contains("usage pacing"), "warned: ${start.detail}")
    }
}
