package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.harness.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.ContextLimitSupport
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketContextLimit
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.ContextLimits
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): the ticket's `contextLimit` (persistence, keep / clear / refuse), its effective
 * view, and what a start and a spawn hand the harness: the limit when the harness applies one, nothing (but recorded on the
 * session) when it does not, and no change at all for a session that sets none.
 */
class ContextLimitTest {
    private val dir: File = Files.createTempDirectory("agenthq-ctxlimit").toFile()
    private val dbPath = File(dir, "agenthq.db").absolutePath
    private val opened = mutableListOf<SqlAgentHqStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openStore(): SqlAgentHqStore = SqlAgentHqStore.open(dbPath).also { opened += it }

    private fun plainBoard(store: SqlAgentHqStore = openStore()): TicketBoard {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        return TicketBoard(store, idFactory = { ids.next() })
    }

    // --- the value ------------------------------------------------------------------------------------------

    @Test
    fun theParserReadsSizes_offClears_andATypoIsRefused() {
        assertEquals(200_000, ContextLimits.parse("200k").value)
        assertEquals(1_000_000, ContextLimits.parse("1M").value)
        assertEquals(1_500_000, ContextLimits.parse("1.5m").value)
        assertEquals(150_000, ContextLimits.parse("150_000").value)
        for (off in listOf("", " ", "0", "off", "none", null)) assertTrue(ContextLimits.parse(off).cleared, "'$off' clears")
        for (bad in listOf("200", "lots", "-5k", "11m")) assertTrue(ContextLimits.parse(bad).error != null, "'$bad' is refused")
        assertEquals("200k", ContextLimits.label(200_000))
        assertEquals("1m", ContextLimits.label(1_000_000))
        assertEquals("1.5m", ContextLimits.label(1_500_000))
    }

    // --- the ticket -----------------------------------------------------------------------------------------

    @Test
    fun aTicketHasNoLimitByDefault_andALimitSurvivesAReopen() {
        val first = openStore()
        val board = plainBoard(first)
        val plain = board.save(TicketSave(title = "No limit")).ticket
        val big = board.save(TicketSave(title = "Big", contextLimit = 500_000)).ticket
        assertNull(plain.contextLimit)
        assertEquals("default", plain.effectiveContextLimit!!.source)
        assertEquals(500_000, big.contextLimit)
        assertEquals("ticket", big.effectiveContextLimit!!.source)
        first.close()

        val reread = plainBoard(openStore())
        assertNull(reread.get(plain.id).contextLimit)
        assertEquals(500_000, reread.get(big.id).contextLimit, "the limit is durable")
    }

    @Test
    fun anEditKeepsTheLimitWhenAbsent_changesItWhenGiven_andClearsItOnZero() {
        val board = plainBoard()
        val id = board.save(TicketSave(title = "T", contextLimit = 200_000)).ticket.id
        assertEquals(200_000, board.save(TicketSave(id = id, title = "T2")).ticket.contextLimit, "absent keeps")
        assertEquals(1_000_000, board.save(TicketSave(id = id, contextLimit = 1_000_000)).ticket.contextLimit, "given changes")
        assertNull(board.save(TicketSave(id = id, contextLimit = TicketContextLimit.CLEAR)).ticket.contextLimit, "0 clears")
    }

    @Test
    fun aLimitOutOfRangeIsRefusedAndSavesNothing() {
        val board = plainBoard()
        for (bad in listOf(-1, 200, 20_000_000)) {
            assertFailsWith<IllegalArgumentException>("limit $bad") { board.save(TicketSave(title = "Bad", contextLimit = bad)) }
        }
        assertTrue(board.list().none { it.title == "Bad" })
    }

    // --- the start ------------------------------------------------------------------------------------------

    private val seen = mutableListOf<RunRequest>()

    /** A harness that applies a limit, or (with [support] UNSUPPORTED) one that does not. */
    private fun provider(support: ContextLimitSupport?) = object : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest): ProcessSpec = FakeAgentProvider().spec(request).also { seen += request }
        override fun contextLimit(): ContextLimitCapability =
            support?.let { ContextLimitCapability(it, "test harness") } ?: ContextLimitCapability.UNSUPPORTED
    }

    private class Started(val ticket: Ticket, val request: RunRequest, val session: SessionRecord)

    private fun start(save: TicketSave, support: ContextLimitSupport?): Started {
        val store = openStore()
        val manager = SessionManager(provider(support), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager)
        val ticket = board.save(save.copy(agentId = worker.id)).ticket
        val answer = board.move(TicketMove(ticket.id, IN_PROGRESS)).ticket
        assertEquals("started", answer.start?.reason, "start detail: ${answer.start?.detail}")
        val view = board.get(ticket.id)
        board.close()
        return Started(view.copy(start = answer.start), seen.single(), manager.list().single())
    }

    @Test
    fun aLimitOnTheTicketReachesASupportingHarness_andTheStartAndTheSessionSaySo() {
        val started = start(TicketSave(title = "Big", contextLimit = 200_000), ContextLimitSupport.SUPPORTED)

        assertEquals(200_000, started.request.contextLimit, "the harness is handed the limit")
        assertEquals(200_000, started.session.contextLimit)
        assertEquals("supported", started.session.contextLimitSupport)
        val effective = started.ticket.effectiveContextLimit!!
        assertEquals(200_000, effective.value)
        assertEquals("ticket", effective.source)
        assertEquals("supported", effective.support)
        assertEquals("test harness", effective.how)
        val detail = started.ticket.start!!.detail.orEmpty()
        assertTrue(detail.contains("context limit 200k: supported") && !detail.contains("not applied"), "start detail: '$detail'")
    }

    @Test
    fun aHarnessThatCannotApplyALimitIsNotHandedOne_butTheSessionRecordsIt_andTheStartSaysNotApplied() {
        val started = start(TicketSave(title = "Big", contextLimit = 200_000), null)

        assertNull(started.request.contextLimit, "an unsupported harness never sees the limit")
        assertEquals(200_000, started.session.contextLimit, "recorded for the usage comparison")
        assertEquals("unsupported", started.session.contextLimitSupport)
        assertEquals("unsupported", started.ticket.effectiveContextLimit!!.support)
        assertTrue(started.ticket.start!!.detail.orEmpty().contains("(recorded, not applied by fake)"), "start detail: '${started.ticket.start!!.detail}'")
    }

    @Test
    fun aTicketWithNoLimitKeepsTheCurrentBehaviour() {
        val started = start(TicketSave(title = "Plain"), ContextLimitSupport.SUPPORTED)

        assertNull(started.request.contextLimit)
        assertNull(started.session.contextLimit)
        assertNull(started.session.contextLimitSupport)
        assertEquals("default", started.ticket.effectiveContextLimit!!.source)
        assertTrue(!started.ticket.start!!.detail.orEmpty().contains("context limit"))
    }

    // --- the spawn ------------------------------------------------------------------------------------------

    @Test
    fun aSpawnCarriesTheLimit_andAnOutOfRangeOneIsRefusedWithNothingCreated() {
        val manager = SessionManager(provider(ContextLimitSupport.BEST_EFFORT), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val s = manager.startNew("go", contextLimit = 1_000_000)
        assertEquals(1_000_000, s.contextLimit)
        assertEquals("best_effort", s.contextLimitSupport)
        assertEquals(1_000_000, seen.single().contextLimit)
        assertFailsWith<IllegalArgumentException> { manager.startNew("go", contextLimit = 5) }
        assertEquals(1, manager.list().size)
    }
}
