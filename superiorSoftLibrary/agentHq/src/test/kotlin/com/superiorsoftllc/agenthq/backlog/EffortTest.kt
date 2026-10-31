package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.harness.EffortCapability
import com.superiorsoftllc.agenthq.harness.EffortSupport
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.EffortLevels
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.usage.RunUsageRecord
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EFFORT-1 (docs/EFFORT.md): the level list as data, the ticket's `effort` (persistence, keep / clear / refuse), its effective
 * view, and what a start and a spawn hand the harness: the level when the harness applies one, nothing (but recorded on the
 * session) when it does not, and no change at all for a session that sets none. Also the run_usage record.
 */
class EffortTest {
    private val dir: File = Files.createTempDirectory("agenthq-effort").toFile()
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

    // --- the data -------------------------------------------------------------------------------------------

    @Test
    fun theLevelsAreOrderedData_offClears_andATypoIsRefused() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), EffortLevels.ids)
        assertEquals("Extra high", EffortLevels.ALL.single { it.id == "xhigh" }.label)
        assertEquals("high", EffortLevels.parse(" HIGH ").value)
        for (off in listOf("", " ", "off", "none", "default", null)) assertTrue(EffortLevels.parse(off).cleared, "'$off' clears")
        for (bad in listOf("extreme", "10", "hi")) assertTrue(EffortLevels.parse(bad).error != null, "'$bad' is refused")
        assertNull(EffortLevels.problem(null))
        assertTrue(EffortLevels.problem("loud") != null)
    }

    // --- the ticket -----------------------------------------------------------------------------------------

    @Test
    fun aTicketHasNoEffortByDefault_andALevelSurvivesAReopen() {
        val first = openStore()
        val board = plainBoard(first)
        val plain = board.save(TicketSave(title = "No effort")).ticket
        val hard = board.save(TicketSave(title = "Hard", effort = "high")).ticket
        assertNull(plain.effort)
        assertEquals("default", plain.effectiveEffort!!.source)
        assertNull(plain.effectiveEffort!!.value)
        assertEquals("high", hard.effort)
        assertEquals("ticket", hard.effectiveEffort!!.source)
        assertEquals("high", hard.effectiveEffort!!.value)
        first.close()

        val reread = plainBoard(openStore())
        assertNull(reread.get(plain.id).effort)
        assertEquals("high", reread.get(hard.id).effort, "the level is durable")
    }

    @Test
    fun anEditKeepsTheEffortWhenAbsent_changesItWhenGiven_andClearsItOnBlank() {
        val board = plainBoard()
        val id = board.save(TicketSave(title = "T", effort = "low")).ticket.id
        assertEquals("low", board.save(TicketSave(id = id, title = "T2")).ticket.effort, "absent keeps")
        assertEquals("max", board.save(TicketSave(id = id, effort = "MAX")).ticket.effort, "given changes (any case)")
        assertNull(board.save(TicketSave(id = id, effort = "")).ticket.effort, "blank clears")
        assertEquals("high", board.save(TicketSave(id = id, effort = "high")).ticket.effort)
        assertNull(board.save(TicketSave(id = id, effort = "off")).ticket.effort, "off clears")
    }

    @Test
    fun anUnknownLevelIsRefusedAndSavesNothing() {
        val board = plainBoard()
        assertFailsWith<IllegalArgumentException> { board.save(TicketSave(title = "Bad", effort = "extreme")) }
        assertTrue(board.list().none { it.title == "Bad" })
    }

    // --- the start ------------------------------------------------------------------------------------------

    private val seen = mutableListOf<RunRequest>()

    /** A harness that applies an effort, or (with [support] null) one that does not. */
    private fun provider(support: EffortSupport?) = object : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest): ProcessSpec = FakeAgentProvider().spec(request).also { seen += request }
        override fun effort(): EffortCapability = support?.let { EffortCapability(it, "test harness") } ?: EffortCapability.UNSUPPORTED
    }

    private class Started(val ticket: Ticket, val request: RunRequest, val session: SessionRecord)

    private fun start(save: TicketSave, support: EffortSupport?, profileEffort: String? = null): Started {
        val store = openStore()
        val manager = SessionManager(provider(support), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend", effort = profileEffort))
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
    fun anEffortOnTheTicketReachesASupportingHarness_andTheStartAndTheSessionSaySo() {
        val started = start(TicketSave(title = "Hard", effort = "xhigh"), EffortSupport.SUPPORTED)

        assertEquals("xhigh", started.request.effort, "the harness is handed the level")
        assertEquals("xhigh", started.session.effort)
        assertEquals("supported", started.session.effortSupport)
        val effective = started.ticket.effectiveEffort!!
        assertEquals("xhigh", effective.value)
        assertEquals("ticket", effective.source)
        assertEquals("supported", effective.support)
        assertEquals("test harness", effective.how)
        val detail = started.ticket.start!!.detail.orEmpty()
        assertTrue(detail.contains("effort xhigh: supported") && !detail.contains("not applied"), "start detail: '$detail'")
    }

    @Test
    fun aHarnessThatCannotApplyAnEffortIsNotHandedOne_butTheSessionRecordsIt_andTheStartSaysNotApplied() {
        val started = start(TicketSave(title = "Hard", effort = "high"), null)

        assertNull(started.request.effort, "an unsupported harness never sees the level")
        assertEquals("high", started.session.effort, "recorded for the usage comparison")
        assertEquals("unsupported", started.session.effortSupport)
        assertEquals("unsupported", started.ticket.effectiveEffort!!.support)
        assertTrue(started.ticket.start!!.detail.orEmpty().contains("(recorded, not applied by fake)"), "start detail: '${started.ticket.start!!.detail}'")
    }

    @Test
    fun aTicketWithNoEffortKeepsTheCurrentBehaviour() {
        val started = start(TicketSave(title = "Plain"), EffortSupport.SUPPORTED)

        assertNull(started.request.effort)
        assertNull(started.session.effort)
        assertNull(started.session.effortSupport)
        assertEquals("default", started.ticket.effectiveEffort!!.source)
        assertTrue(!started.ticket.start!!.detail.orEmpty().contains("effort"))
    }

    @Test
    fun theAgentsEffortIsTheSourceWhenTheTicketPinsNone_andTheTicketsOwnBeatsIt() {
        val agentOnly = start(TicketSave(title = "A"), EffortSupport.SUPPORTED, profileEffort = "medium")
        assertEquals("medium", agentOnly.request.effort)
        assertEquals("agent", agentOnly.ticket.effectiveEffort!!.source)
        seen.clear()

        val both = start(TicketSave(title = "B", effort = "max"), EffortSupport.SUPPORTED, profileEffort = "medium")
        assertEquals("max", both.request.effort, "the ticket's pin beats the agent's")
        assertEquals("ticket", both.ticket.effectiveEffort!!.source)
    }

    // --- the spawn ------------------------------------------------------------------------------------------

    @Test
    fun aSpawnCarriesTheEffort_andAnUnknownOneIsRefusedWithNothingCreated() {
        val manager = SessionManager(provider(EffortSupport.BEST_EFFORT), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val s = manager.startNew("go", effort = "low")
        assertEquals("low", s.effort)
        assertEquals("best_effort", s.effortSupport)
        assertEquals("low", seen.single().effort)
        assertFailsWith<IllegalArgumentException> { manager.startNew("go", effort = "bad level") }
        assertEquals(1, manager.list().size)
    }

    // --- the usage record -----------------------------------------------------------------------------------

    @Test
    fun aRunUsageRowRecordsTheEffortNextToTheContextLimit() {
        val store = openStore()
        store.usage.insert(RunUsageRecord("r1", "s1", "claude", "opus", 1_000, null, contextLimit = 200_000, effort = "high"))
        store.usage.insert(RunUsageRecord("r2", "s1", "claude", "opus", 2_000, null))
        val rows = store.usage.since(0).associateBy { it.runId }
        assertEquals("high", rows.getValue("r1").effort)
        assertEquals(200_000, rows.getValue("r1").contextLimit)
        assertNull(rows.getValue("r2").effort, "no effort recorded = harness default")
    }
}
