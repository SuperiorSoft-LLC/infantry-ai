package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** USAGE-PACE (contract 1.121.0): a start past the plan allowance is refused for the daemon / an agent, warned for the owner. */
class TicketPacingGateTest {
    private val dir: File = Files.createTempDirectory("agenthq-pacing").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val asked = mutableListOf<String?>()
    private var verdict: PacingVerdict = PacingVerdict.Allow()
    private lateinit var board: TicketBoard

    @AfterTest
    fun cleanUp() {
        runCatching { board.close() }
        runCatching { store.close() }
        dir.deleteRecursively()
    }

    private fun ticket(): String {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        board = TicketBoard(
            store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager,
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
