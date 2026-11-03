// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 9.3 (contract 1.6.0), over the REAL daemon and a real WebSocket: starting a session from a
 * ticket, role capacity queues, prerequisites, worktrees, and `ticket_runs` surface.
 *
 * A start is a `ticket_move` to `in_progress` (or `ticket_start_next`); the daemon answers with a
 * `ticket` frame carrying `start`, and pushes the same ticket to every client. The prompt a start
 * composes reaches the provider -- here the [GatedRunner] -- so the test reads what an agent would
 * actually receive.
 *
 * Each start uses [FakeTicketContext], so its delivered prompt and source list remain deterministic while
 * the context composer is implemented independently.
 */
class TicketStartDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(
        repo: File? = null,
        gated: GatedRunner? = null,
        context: FakeTicketContext = FakeTicketContext(),
        block: (DaemonFixture, GatedRunner?) -> Unit,
    ) {
        val fixture = DaemonFixture.launch(repo = repo, runner = gated, context = context)
        try {
            fixture.client.await(Envelope.READY)
            block(fixture, gated)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private val backend = """{"id":"backend","name":"Backend","role":"backend"}"""

    @Test
    fun contractVersionIsPinned_1_32_0() = withDaemon { fixture, _ ->
        val ready = fixture.client.of(Envelope.READY).single().data
        assertTrue(ready.contains("contract=${EventVocabulary.VERSION}"), ready)
        assertEquals(EventVocabulary.VERSION, fixture.health()["contract"]!!.jsonPrimitive.content)
    }

    // --- a. assignment --------------------------------------------------------------------------

    @Test
    fun aTicketWithoutAnAgentStaysUnassignedNeverTheFirstProfilesRole() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it"}""")
        // BUG-ROLE-3: no role given means unassigned, never the first profile's role.
        assertNull(ticket.agentId, "a ticket with no agentId stays unassigned")
        assertNull(ticket.sessionId)
        assertNull(ticket.queuedFor)
    }

    @Test
    fun executorPinsRoundTripAndTheEffectiveExecutorNamesItsSources() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        val pinned = fixture.client.saveTicket(
            """{"title":"Pinned","agentId":"backend","provider":"stub","useWorktree":false,""" +
                """"worktreeBranch":"feat-x","includeLinkedTasksContext":false}""",
        )
        assertEquals("backend", pinned.agentId)
        assertEquals("stub", pinned.provider)
        assertEquals(false, pinned.useWorktree)
        assertEquals("feat-x", pinned.worktreeBranch)
        assertEquals(false, pinned.includeLinkedTasksContext)

        // The derived view says what will run and where each value came from.
        assertEquals("ticket", pinned.effectiveExecutor!!.provider!!.source)
        assertEquals("stub", pinned.effectiveExecutor!!.provider!!.value)
        assertEquals("backend", pinned.effectiveExecutor!!.role)
    }

    @Test
    fun aRoleTicketWithNoPinsReportsTheRoleSourceAndNullValues() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        val ticket = fixture.client.saveTicket("""{"title":"Whatever","agentId":"role:backend"}""")
        val executor = ticket.effectiveExecutor!!
        assertEquals("role:backend", ticket.agentId)
        assertNull(executor.provider!!.value, "a role:X ticket names no concrete provider")
        assertEquals("role", executor.provider!!.source)
    }

    @Test
    fun aRoleTicketPreviewShowsTheBoundRolesRealValuesWithSourceRole() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        fixture.send(Envelope(Envelope.ROLE_BINDING_SET, """{"roleId":"backend","provider":"stub","model":"m-1"}"""))
        fixture.client.await(Envelope.ROLE_BINDING)
        val ticket = fixture.client.saveTicket("""{"title":"Bound","agentId":"role:backend"}""")
        val executor = ticket.effectiveExecutor!!
        assertEquals("stub", executor.provider!!.value, "bound provider")
        assertEquals("role", executor.provider!!.source)
        assertEquals("m-1", executor.model!!.value, "bound model")
        assertEquals("role", executor.model!!.source)
    }

    @Test
    fun withNoProfileATicketIsUnassignedAndAnExplicitEmptyAgentIdToo() = withDaemon { fixture, _ ->
        val first = fixture.client.saveTicket("""{"title":"No profiles yet"}""")
        assertNull(first.agentId, "no profile -> unassigned, never a role-less session")

        fixture.client.saveProfile(backend)
        val second = fixture.client.saveTicket("""{"title":"Deliberately unassigned","agentId":""}""")
        assertNull(second.agentId, "an explicit empty agentId leaves it unassigned")
    }

    @Test
    fun anUnknownProfileOrAnUnknownKeyIsRefusedAndNothingIsStored() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        val unknownProfile = fixture.client.answerTo(
            Envelope.TICKET_SAVE,
            """{"title":"T","agentId":"nobody"}""",
        )
        assertEquals(Envelope.ERROR, unknownProfile.type)
        assertTrue(unknownProfile.data.contains("nobody"), "the refusal names the profile: ${unknownProfile.data}")

        val unknownKey = fixture.client.answerTo(Envelope.TICKET_SAVE, """{"title":"T","colour":"red"}""")
        assertEquals(Envelope.ERROR, unknownKey.type)
        assertTrue(unknownKey.data.contains("colour"), "the refusal names the key: ${unknownKey.data}")

        assertEquals(0, fixture.client.board().size, "no ticket was stored")
    }

    @Test
    fun anUnknownProviderOrInvalidModelIsRefusedWithoutSubstitution() = withDaemon { fixture, _ ->
        fixture.client.saveProfile(backend)
        val unknownProvider = fixture.client.answerTo(
            Envelope.TICKET_SAVE,
            """{"title":"Bad provider","agentId":"backend","provider":"missing"}""",
        )
        assertEquals(Envelope.ERROR, unknownProvider.type)
        assertTrue(unknownProvider.data.contains("missing"), unknownProvider.data)

        val invalidModel = fixture.client.answerTo(
            Envelope.TICKET_SAVE,
            """{"title":"Bad model","agentId":"backend","model":"-flag"}""",
        )
        assertEquals(Envelope.ERROR, invalidModel.type)
        assertTrue(invalidModel.data.contains("-flag"), invalidModel.data)
        assertTrue(fixture.client.board().isEmpty(), "neither invalid ticket was stored")
    }

    // --- b. start on a named profile ------------------------------------------------------------

    @Test
    fun movingATicketToInProgressStartsASessionAndAttributesIt() {
        val context = FakeTicketContext()
        withDaemon(gated = GatedRunner(), context = context) { fixture, gated ->
        val one = fixture.client
        val two = fixture.connectClient()
        one.saveProfile(backend)
        val ticket = one.saveTicket("""{"title":"Implement it","agentId":"backend"}""")

        val started = one.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        val start = started.start
        assertNotNull(start, "a start answers with a `start` outcome")
        assertTrue(start.ok, "the start succeeded: $start")
        assertEquals("started", start.reason)
        assertEquals(TicketStatus.IN_PROGRESS, started.status)
        val sessionId = assertNotNull(started.sessionId, "the ticket records the session it started")

        // The session frame says whose it is and what context it carried.
        val sessionFrame = one.awaitAfter(0, "a session for the ticket") { envelope ->
            envelope.type == Envelope.SESSION &&
                json.decodeFromString(SessionRecord.serializer(), envelope.data).id == sessionId
        }
        val session = json.decodeFromString(SessionRecord.serializer(), sessionFrame.data)
        assertEquals(ticket.id, session.ticketId)
        assertEquals("backend", session.agentId)
        assertEquals("backend", session.role)
        assertEquals(context.sources, session.contextSources)
        assertEquals(context.prompt, gated!!.prompts.single())

        // The same ticket was pushed to the other client.
        val onTwo = two.awaitAfter(0, "the ticket push on the second client") { envelope ->
            envelope.type == Envelope.TICKET &&
                envelope.ticketOrNull()?.let { it.id == ticket.id && it.status == TicketStatus.IN_PROGRESS } == true
        }
        assertEquals(sessionId, onTwo.ticketOrNull()!!.sessionId)
        }
    }

    private fun withQueueDaemon(
        repo: File? = null,
        block: (DaemonFixture, GatedRunner) -> Unit,
    ) {
        val gated = GatedRunner()
        withDaemon(repo = repo, gated = gated, context = FakeTicketContext("HANG")) { fixture, _ ->
            block(fixture, gated)
        }
    }

    private fun profileJson(id: String, role: String) = """{"id":"$id","name":"$id","role":"$role"}"""

    /** The context fake emits HANG for every start in these tests, keeping the lane occupied. */
    private fun DaemonFixture.startHanging(title: String, agentId: String): String {
        val ticket = client.saveTicket("""{"title":"$title","agentId":"$agentId"}""")
        val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertTrue(started.start!!.ok, "the hanging start must succeed: ${started.start}")
        return ticket.id
    }

    // --- queue, refusals, worktree and prerequisites --------------------------------------------

    @Test
    fun aRoleTicketPicksTheFirstFreeProfileThenQueuesWhenAllAreBusy() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("p1", "backend"))
        fixture.client.saveProfile(profileJson("p2", "backend"))

        val a = fixture.client.saveTicket("""{"title":"A","agentId":"role:backend"}""")
        val startedA = fixture.client.moveTicket(a.id, TicketStatus.IN_PROGRESS)
        assertEquals("p1", fixture.daemon.manager.get(startedA.sessionId!!)!!.agentId, "role picks the first profile")

        val b = fixture.client.saveTicket("""{"title":"B","agentId":"role:backend"}""")
        val startedB = fixture.client.moveTicket(b.id, TicketStatus.IN_PROGRESS)
        assertEquals("p2", fixture.daemon.manager.get(startedB.sessionId!!)!!.agentId, "then the next free profile")
        assertEquals(2, fixture.daemon.manager.list().size)

        val c = fixture.client.saveTicket("""{"title":"C","agentId":"role:backend"}""")
        val queued = fixture.client.moveTicket(c.id, TicketStatus.IN_PROGRESS)
        assertEquals("queued", queued.start!!.reason, "no profile free -> queued, never refused or over-spawned")
        assertEquals(TicketStatus.TODO, queued.status, "a queued ticket stays todo")
        assertEquals("role:backend", queued.queuedFor)
        assertNotNull(queued.queuedAt)
        assertEquals(2, fixture.daemon.manager.list().size, "queuing spawns nothing")
    }

    @Test
    fun aNamedProfileAtCapacityQueuesOnThatProfileAndDrainsWhenTheRunEnds() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))
        val a = fixture.startHanging("A", "backend")
        val aSession = fixture.client.board().single { it.id == a }.sessionId!!

        val b = fixture.client.saveTicket("""{"title":"B","agentId":"backend"}""")
        val queued = fixture.client.moveTicket(b.id, TicketStatus.IN_PROGRESS)
        assertEquals("queued", queued.start!!.reason)
        assertEquals("backend", queued.queuedFor)
        assertEquals(1, fixture.daemon.manager.list().size, "still one session")

        fixture.send(Envelope.KILL, "$aSession force")
        val drained = fixture.client.awaitAfter(0, "the queued ticket starting after the kill") { envelope ->
            envelope.type == Envelope.TICKET &&
                envelope.ticketOrNull()?.let { it.id == b.id && it.sessionId != null } == true
        }
        assertEquals(TicketStatus.IN_PROGRESS, drained.ticketOrNull()!!.status)
        assertEquals(2, fixture.daemon.manager.list().size, "the drain spawned the queued session")
    }

    /**
     * INSTALL-PROFILE-1: a clean install has no agent profiles. A `role:<role>` ticket whose role is in the catalog
     * but held by no profile creates one default profile for that role (no provider or model pinned, so it runs on
     * the role binding or the daemon's default provider and model) and starts on it, never "no agent profile has role".
     */
    @Test
    fun aFirstRunRoleTicketWithNoProfileCreatesADefaultProfileAndStarts() = withQueueDaemon { fixture, _ ->
        fixture.send(Envelope(Envelope.AGENT_LIST))
        assertEquals("[]", fixture.client.await(Envelope.AGENTS).data, "the profile store starts empty")

        val ticket = fixture.client.saveTicket("""{"title":"First run","agentId":"role:fullstack"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start!!.reason, "the start must succeed: ${started.start}")
        val session = fixture.daemon.manager.get(started.sessionId!!)!!
        assertEquals("fullstack", session.role)
        val agentId = assertNotNull(session.agentId, "the session runs on the created profile")

        val mark = fixture.client.mark()
        fixture.send(Envelope(Envelope.AGENT_LIST))
        val listed = fixture.client.awaitAfter(mark, "agent list") { it.type == Envelope.AGENTS }.data
        assertTrue(listed.contains("\"id\":\"$agentId\"") && listed.contains("\"role\":\"fullstack\""), listed)

        // A second start of the same role reuses that profile; it never creates another.
        val second = fixture.client.saveTicket("""{"title":"Second","agentId":"role:fullstack"}""")
        assertEquals("queued", fixture.client.moveTicket(second.id, TicketStatus.IN_PROGRESS).start!!.reason)
    }

    @Test
    fun aStartThatCanNeverSucceedIsRefusedAndNeverQueued() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))

        // A ticket can outlive its profile: removing the profile after assignment makes the start impossible.
        val noProfile = fixture.client.saveTicket("""{"title":"X","agentId":"backend"}""")
        fixture.client.answerTo(Envelope.AGENT_DELETE, "backend")
        assertEquals("start_refused", fixture.client.moveTicket(noProfile.id, TicketStatus.IN_PROGRESS).start!!.reason)

        val unassigned = fixture.client.saveTicket("""{"title":"X","agentId":""}""")
        assertEquals("start_refused", fixture.client.moveTicket(unassigned.id, TicketStatus.IN_PROGRESS).start!!.reason)
        assertEquals(0, fixture.daemon.manager.list().size, "none of these spawned a session")
    }

    @Test
    fun useWorktreeTrueWithoutARepoIsRefusedClosedAndNotQueued() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))
        val ticket = fixture.client.saveTicket("""{"title":"X","agentId":"backend","useWorktree":true}""")
        val refused = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("start_refused", refused.start!!.reason)
        assertTrue(refused.start!!.detail!!.contains("repo"), "the detail names the missing repo: ${refused.start!!.detail}")
        assertEquals(TicketStatus.TODO, refused.status)
        assertNull(refused.queuedFor, "a fail-closed start is never retried through the queue")
        assertEquals(0, fixture.daemon.manager.list().size)
    }

    @Test
    fun nullUseWorktreeFollowsTheDaemon() {
        val repo = newGitRepo()
        withQueueDaemon(repo = repo) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket("""{"title":"task","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertNotNull(fixture.daemon.manager.get(started.sessionId!!)!!.worktreePath, "a repo -> its own worktree")
        }
        withQueueDaemon(repo = null) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket("""{"title":"task","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertNull(fixture.daemon.manager.get(started.sessionId!!)!!.worktreePath, "no repo -> the shared checkout")
        }
    }

    private fun ticketBranchesIn(repo: File): List<String> =
        testGit().run(repo, listOf("branch", "--list", "ticket/*")).out().lines()
            .map { it.trim().removePrefix("* ").removePrefix("+ ") }.filter { it.isNotBlank() }

    @Test
    fun startingATicketCutsOneTicketBranchAndRecordsItsBase() {
        val repo = newGitRepo()
        val base = testGit().run(repo, listOf("rev-parse", "HEAD")).out()
        withQueueDaemon(repo = repo) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket("""{"title":"Fix the thing","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("started", started.start!!.reason, "the start must succeed: ${started.start}")
            val branch = assertNotNull(started.branch, "the ticket records its branch")
            assertTrue(branch.startsWith("ticket/"), branch)
            assertEquals(base, started.base, "the ticket records the commit its branch was cut from")
            assertEquals(branch, fixture.daemon.manager.get(started.sessionId!!)!!.worktreeBranch, "the session runs on that branch")
            assertEquals(listOf(branch), ticketBranchesIn(repo), "exactly one ticket branch")
        }
    }

    /**
     * BUG-REGRESS-1: uncommitted changes in the repo's checkout never refuse a start. The branch is cut from the base
     * ref's commit, the edit stays in the checkout, and the start's answer says what the branch was cut from.
     */
    @Test
    fun aDirtyBaseCheckoutStillStartsTheTicketOnItsOwnBranchAndSaysWhatItWasCutFrom() {
        val repo = newGitRepo()
        val base = testGit().run(repo, listOf("rev-parse", "HEAD")).out()
        File(repo, "README.md").writeText("half-written\n")
        withQueueDaemon(repo = repo) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket("""{"title":"Fix the thing","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("started", started.start!!.reason, "a dirty checkout never refuses a start: ${started.start}")
            assertEquals("cut from HEAD ${base.take(8)}", started.start!!.detail, "the answer says what the branch was cut from")
            assertEquals(TicketStatus.IN_PROGRESS, started.status)
            val branch = assertNotNull(started.branch, "the ticket records its branch")
            assertEquals(base, started.base, "cut from the base ref's commit")
            assertEquals(listOf(branch), ticketBranchesIn(repo), "exactly one ticket branch")
            val worktree = File(fixture.daemon.manager.get(started.sessionId!!)!!.worktreePath!!)
            assertEquals("hello", File(worktree, "README.md").readText().trim(), "only committed work is on the ticket branch")
            assertEquals("half-written", File(repo, "README.md").readText().trim(), "the edit stays in the checkout")
        }
    }

    @Test
    fun useWorktreeTrueUsesTheBranchHintAndFalseIsRefusedOverAGitCheckout() {
        val repo = newGitRepo()
        withQueueDaemon(repo = repo) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket(
                """{"title":"task","agentId":"backend","useWorktree":true,"worktreeBranch":"feat-x"}""",
            )
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            val branch = fixture.daemon.manager.get(started.sessionId!!)!!.worktreeBranch!!
            assertTrue(branch.startsWith("ticket/"), "the ticket branch is named from the ticket, not the pin: $branch")
        }
        withQueueDaemon(repo = repo) { fixture, _ ->
            fixture.client.saveProfile(profileJson("backend", "backend"))
            val ticket = fixture.client.saveTicket("""{"title":"task","agentId":"backend","useWorktree":false}""")
            // BUG-WT-1: useWorktree false would run the worker in the repo's own checkout, on its checked-out
            // branch; the start is refused and no session exists.
            val refused = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("start_refused", refused.start!!.reason)
            assertTrue(refused.start!!.detail!!.contains("git repo"), "the reason names the git repo: ${refused.start}")
            assertEquals(0, fixture.daemon.manager.list().size)
        }
    }

    @Test
    fun aHeldMoveAnswersStartHeldWithTheOpenIdsAndNeverStarts() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))
        val blocker = fixture.client.saveTicket("""{"title":"Prerequisite"}""")
        val heldTicket = fixture.client.saveTicket("""{"title":"Blocked","agentId":"backend"}""")
        fixture.client.answerTo(
            Envelope.TICKET_LINK,
            """{"ticketId":"${heldTicket.id}","relatedId":"${blocker.id}","type":"blocked_by"}""",
        )
        val held = fixture.client.moveTicket(heldTicket.id, TicketStatus.IN_PROGRESS)
        assertEquals("held", held.start!!.reason)
        assertTrue(held.start!!.detail!!.contains(blocker.id), "the detail lists the open prerequisite: ${held.start!!.detail}")
        assertEquals(TicketStatus.PENDING, held.status)
        assertEquals(0, fixture.daemon.manager.list().size)
    }

    @Test
    fun startNextSkipsHeldTickets() = withQueueDaemon { fixture, _ ->
        val blocker = fixture.client.saveTicket("""{"title":"Blocker"}""")
        // The prerequisite is deliberately unassigned; tickets created after this profile use its role default.
        fixture.client.saveProfile(profileJson("backend", "backend"))
        val heldTop = fixture.client.saveTicket("""{"title":"Blocked","agentId":"backend"}""")
        fixture.client.answerTo(
            Envelope.TICKET_LINK,
            """{"ticketId":"${heldTop.id}","relatedId":"${blocker.id}","type":"blocked_by"}""",
        )
        val free = fixture.client.saveTicket("""{"title":"Free","agentId":"backend"}""")
        fixture.client.answerTo(Envelope.TICKET_REORDER, """{"status":"todo","ids":["${heldTop.id}","${free.id}"]}""")
        val from = fixture.client.mark()
        fixture.send(Envelope.TICKET_START_NEXT, "")
        val answer = fixture.client.awaitAfter(from, "start_next hands out the first ready ticket") { envelope ->
            envelope.type == Envelope.ERROR ||
                (envelope.type == Envelope.TICKET && envelope.ticketOrNull()?.start != null)
        }
        val started = answer.requireTicket()
        assertEquals(
            free.id,
            started.id,
            "the held ticket on top is skipped; held=${heldTop.id}, blocker=${blocker.id}, board=${fixture.client.board()}",
        )
        assertEquals("started", started.start!!.reason)
    }

    @Test
    fun aQueuedTicketThatGainsAnOpenPrerequisiteGoesPendingAndIsNotDrained() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))
        val a = fixture.startHanging("A", "backend")
        val aSession = fixture.client.board().single { it.id == a }.sessionId!!
        val b = fixture.client.saveTicket("""{"title":"B","agentId":"backend"}""")
        assertEquals("queued", fixture.client.moveTicket(b.id, TicketStatus.IN_PROGRESS).start!!.reason)
        val blocker = fixture.client.saveTicket("""{"title":"Late blocker"}""")
        fixture.client.answerTo(
            Envelope.TICKET_LINK,
            """{"ticketId":"${b.id}","relatedId":"${blocker.id}","type":"blocked_by"}""",
        )
        val held = fixture.client.board().single { it.id == b.id }
        assertEquals(TicketStatus.PENDING, held.status, "an open prerequisite holds the queued ticket")
        assertNull(held.queuedFor, "the queue mark is cleared when it can no longer run")
        fixture.send(Envelope.KILL, "$aSession force")
        fixture.await(what = "nothing drains to the now-held ticket") {
            fixture.daemon.manager.list().size == 1
        }
    }

    @Test
    fun startNextTakesTheTopStartableTicketAcrossFreeLanes() = withQueueDaemon { fixture, _ ->
        fixture.client.saveProfile(profileJson("backend", "backend"))
        fixture.client.saveProfile(profileJson("frontend", "frontend"))
        fixture.startHanging("busy", "backend")
        val free = fixture.client.saveTicket("""{"title":"free","agentId":"frontend"}""")
        val busyTodo = fixture.client.saveTicket("""{"title":"busy todo","agentId":"backend"}""")
        fixture.client.answerTo(Envelope.TICKET_REORDER, """{"status":"todo","ids":["${busyTodo.id}","${free.id}"]}""")
        val started = fixture.client.answerTo(Envelope.TICKET_START_NEXT).requireTicket()
        assertEquals(free.id, started.id, "a busy lane never blocks a free one")
        assertEquals("started", started.start!!.reason)
    }

    @Test
    fun startNextNeverQueuesAndErrorsWhenNothingIsStartable() = withQueueDaemon { fixture, _ ->
        val empty = fixture.client.answerTo(Envelope.TICKET_START_NEXT)
        assertEquals(Envelope.ERROR, empty.type, "empty board")
        assertTrue(empty.data.endsWith("nothing startable"), "no candidate, nothing more to say: ${empty.data}")
        fixture.client.saveProfile(profileJson("backend", "backend"))
        fixture.startHanging("busy", "backend")
        val onlyBusy = fixture.client.saveTicket("""{"title":"only","agentId":"backend"}""")
        val error = fixture.client.answerTo(Envelope.TICKET_START_NEXT)
        assertEquals(Envelope.ERROR, error.type, "start_next never queues: $error")
        // BUG-REGRESS-1: the error names each candidate and the board's own reason, never a bare "nothing startable".
        assertTrue(error.data.contains("nothing startable: ${onlyBusy.id} \"only\" did not start: "), error.data)
        assertTrue(error.data.contains("is at capacity"), "the candidate's real refusal is passed through: ${error.data}")
        val still = fixture.client.board().single { it.id == onlyBusy.id }
        assertEquals(TicketStatus.TODO, still.status)
        assertNull(still.queuedFor, "start_next must not queue")
    }

    @Test
    fun aRestartClearsQueueMarksAndStartsNothing() {
        val stateDir = java.nio.file.Files.createTempDirectory("infantry-queue-restart").toFile()
        var queuedId = ""
        val context = FakeTicketContext("HANG")
        val first = DaemonFixture.launch(stateDir = stateDir, runner = GatedRunner(), context = context)
        try {
            first.client.await(Envelope.READY)
            first.client.saveProfile(profileJson("backend", "backend"))
            val a = first.client.saveTicket("""{"title":"A","agentId":"backend"}""")
            first.client.moveTicket(a.id, TicketStatus.IN_PROGRESS)
            val b = first.client.saveTicket("""{"title":"B","agentId":"backend"}""")
            assertEquals("queued", first.client.moveTicket(b.id, TicketStatus.IN_PROGRESS).start!!.reason)
            queuedId = b.id
        } finally {
            first.stop()
        }

        val second = DaemonFixture.launch(stateDir = stateDir, runner = GatedRunner(), context = context)
        try {
            second.client.await(Envelope.READY)
            val cleared = second.client.board().single { it.id == queuedId }
            assertEquals(TicketStatus.TODO, cleared.status)
            assertNull(cleared.queuedFor, "a restart clears every queue mark")
            assertNull(cleared.queuedAt)
            assertEquals(1, second.daemon.manager.list().size, "nothing starts on boot")
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    // --- j. ticket_runs and lastRun -------------------------------------------------------------

    @Test
    fun aForcedKillIsRecordedAsATerminalTicketRunAndPublished() {
        val script = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"stub-kill"}""",
            ) + List(40) { index -> """{"type":"text","text":"stub output $index"}""" },
        )
        val runner = ScriptedRunner(script, delayMs = 50)
        val fixture = DaemonFixture.launch(script = script, runner = runner)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile(backend)
            val ticket = fixture.client.saveTicket("""{"title":"Force-stop me","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            val sessionId = assertNotNull(started.sessionId, "the ticket starts one session")

            fixture.client.awaitWhere("the stub run has begun") {
                it.type == Envelope.STDOUT && it.sessionId == sessionId
            }
            // Wait for the board's own answer, not for session state. The session goes EXITED under the session
            // manager's monitor, but the ticket outcome (ensureRunRow + finishTicketRun) is applied LATER, on the
            // board's `infantry-ticket-session-events` executor, which then pushes one `ticket` frame carrying
            // lastRun. Waiting on EXITED let a TICKET_GET win the board monitor before that queued work ran, so
            // lastRun read back null. The mark is taken BEFORE the kill so a fast push cannot be missed (the
            // TriggerDaemonTest pattern).
            val beforeKill = fixture.client.mark()
            fixture.send(Envelope.KILL, "$sessionId force")
            fixture.client.awaitAfter(beforeKill, "the board publishes the force-killed run on its ticket") {
                it.type == Envelope.TICKET && it.ticketOrNull()?.let { t -> t.id == ticket.id && t.lastRun != null } == true
            }

            val from = fixture.client.mark()
            fixture.send(Envelope.TICKET_GET, ticket.id)
            val after = fixture.client.awaitAfter(from, "ticket_get returns the killed ticket") {
                it.type == Envelope.TICKET && it.ticketOrNull()?.id == ticket.id
            }.requireTicket()
            assertNotNull(
                after.lastRun,
                "ticket.lastRun must be non-null after forced kill; actual=${after.lastRun}",
            )
            assertEquals("KILLED_FORCED", after.lastRun!!.outcome)
            assertEquals(TicketStatus.IN_PROGRESS, after.status, "a kill is not ticket completion")

            val rows = ticketRuns(fixture.dbPath(), ticket.id)
            assertEquals(1, rows.size, "forced kill must leave exactly one ticket_runs row: $rows")
            assertEquals(sessionId, rows.single()["sessionId"])
            assertEquals("KILLED_FORCED", rows.single()["outcome"])
            assertNotNull(rows.single()["endedAt"], "the killed run is terminal: $rows")
            assertEquals(1, runner.processes.size, "the killed session is never restarted")
            assertEquals(1, fixture.daemon.manager.list().count { it.id == sessionId })

            val finished = fixture.client.awaitWhere("one KILLED_FORCED event for this session") {
                it.type == Envelope.FINISHED && it.sessionId == sessionId
            }
            val payload = json.decodeFromString(FinishedPayload.serializer(), finished.data)
            assertEquals(FinishedPayload.Outcome.KILLED_FORCED, payload.outcome)
            assertEquals(
                1,
                fixture.client.of(Envelope.FINISHED).count { it.sessionId == sessionId },
                "the stream publishes exactly one terminal event for the killed run",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aGracefulKillIsRecordedAsATerminalTicketRunAndPublished() =
        withDaemon(gated = GatedRunner(), context = FakeTicketContext("GRACEFUL HANG")) { fixture, _ ->
            fixture.client.saveProfile(backend)
            val ticket = fixture.client.saveTicket("""{"title":"Gracefully stop me","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            val sessionId = assertNotNull(started.sessionId, "the ticket starts one session")

            fixture.client.awaitWhere("the graceful stub run has begun") {
                it.type == Envelope.STDOUT && it.sessionId == sessionId
            }
            // Same signal as the forced-kill test above: the board's own pushed frame, not session state.
            val beforeKill = fixture.client.mark()
            fixture.send(Envelope.KILL, sessionId)
            fixture.client.awaitAfter(beforeKill, "the board publishes the killed run on its ticket") {
                it.type == Envelope.TICKET && it.ticketOrNull()?.let { t -> t.id == ticket.id && t.lastRun != null } == true
            }

            val from = fixture.client.mark()
            fixture.send(Envelope.TICKET_GET, ticket.id)
            val after = fixture.client.awaitAfter(from, "ticket_get returns the killed ticket") {
                it.type == Envelope.TICKET && it.ticketOrNull()?.id == ticket.id
            }.requireTicket()
            assertNotNull(after.lastRun, "ticket.lastRun must be non-null after graceful kill")
            assertEquals("KILLED_GRACEFUL", after.lastRun!!.outcome)
            assertEquals(TicketStatus.IN_PROGRESS, after.status, "a kill is not ticket completion")

            val rows = ticketRuns(fixture.dbPath(), ticket.id)
            assertEquals(1, rows.size, "graceful kill must leave exactly one ticket_runs row: $rows")
            assertEquals("KILLED_GRACEFUL", rows.single()["outcome"])
            assertNotNull(rows.single()["endedAt"], "the graceful kill is terminal: $rows")

            val finished = fixture.client.awaitWhere("one KILLED_GRACEFUL event for this session") {
                it.type == Envelope.FINISHED && it.sessionId == sessionId
            }
            val payload = json.decodeFromString(FinishedPayload.serializer(), finished.data)
            assertEquals(FinishedPayload.Outcome.KILLED_GRACEFUL, payload.outcome)
            assertEquals(1, fixture.client.of(Envelope.FINISHED).count { it.sessionId == sessionId })
        }

    @Test
    fun aFinishedRunIsRecordedInTicketRunsAndOnLastRun() {
        withDaemon(gated = GatedRunner(holdMs = 0)) { fixture, _ ->
            fixture.client.saveProfile(backend)
            val ticket = fixture.client.saveTicket("""{"title":"Finish me","agentId":"backend"}""")
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertTrue(started.start!!.ok, "the start succeeded: ${started.start}")

            fixture.client.awaitAfter(0, "the run-end ticket push") { envelope ->
                envelope.type == Envelope.TICKET &&
                    envelope.ticketOrNull()?.let { it.id == ticket.id && it.lastRun != null } == true
            }

            val rows = ticketRuns(fixture.dbPath(), ticket.id)
            assertEquals(1, rows.size, "one run row: $rows")
            assertEquals("SUCCEEDED", rows.single()["outcome"])
            assertNotNull(rows.single()["endedAt"], "the run has an end time")

            val after = fixture.client.board().single { it.id == ticket.id }
            // tickets.exitBehavior defaults to leave: a clean exit never moves the ticket.
            assertEquals(TicketStatus.IN_PROGRESS, after.status, "a SUCCEEDED run leaves its ticket in_progress")
            assertEquals("SUCCEEDED", after.lastRun!!.outcome)
        }
    }

    @Test
    fun aFinishedRunMovesItsTicketToDoneWhenExitBehaviorIsDone() {
        withDaemon(gated = GatedRunner(holdMs = 0)) { fixture, _ ->
            fixture.client.answerTo(Envelope.SETTINGS_SET, """{"key":"tickets.exitBehavior","value":"done"}""")
            fixture.client.saveProfile(backend)
            val ticket = fixture.client.saveTicket("""{"title":"Finish me","agentId":"backend"}""")
            fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)

            fixture.client.awaitAfter(0, "the run-end ticket push") { envelope ->
                envelope.type == Envelope.TICKET &&
                    envelope.ticketOrNull()?.let { it.id == ticket.id && it.lastRun != null } == true
            }
            val after = fixture.client.board().single { it.id == ticket.id }
            assertEquals(TicketStatus.DONE, after.status, "done mode moves a cleanly finished ticket to done")
            assertEquals(com.superiorsoftllc.infantry.backlog.TicketBoard.AUTO_DONE_DETAIL, after.lastRun!!.detail)
        }
    }

    @Test
    fun aFailedRunNeverMovesItsTicketToDoneEvenInDoneMode() {
        withDaemon(gated = GatedRunner(holdMs = 0), context = FakeTicketContext("FAIL_ALWAYS")) { fixture, _ ->
            fixture.client.answerTo(Envelope.SETTINGS_SET, """{"key":"tickets.exitBehavior","value":"done"}""")
            fixture.client.saveProfile(backend)
            val ticket = fixture.client.saveTicket("""{"title":"FAIL_ALWAYS task","agentId":"backend"}""")
            fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)

            fixture.client.awaitAfter(0, "the failed run-end push") { envelope ->
                envelope.type == Envelope.TICKET &&
                    envelope.ticketOrNull()?.let { it.id == ticket.id && it.lastRun != null } == true
            }
            val after = fixture.client.board().single { it.id == ticket.id }
            assertEquals("FAILED", after.lastRun!!.outcome)
            assertEquals(TicketStatus.IN_PROGRESS, after.status)
        }
    }

    @Test
    fun aFailedRunIsRecordedNotRestarted() {
        withDaemon(gated = GatedRunner(holdMs = 0), context = FakeTicketContext("FAIL_ALWAYS")) { fixture, _ ->
            fixture.client.saveProfile(backend)
            // GatedRunner fails the fixed prompt for this context fixture.
            val ticket = fixture.client.saveTicket("""{"title":"FAIL_ALWAYS task","agentId":"backend"}""")
            fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)

            fixture.client.awaitAfter(0, "the failed run-end push") { envelope ->
                envelope.type == Envelope.TICKET &&
                    envelope.ticketOrNull()?.let { it.id == ticket.id && it.lastRun != null } == true
            }
            val after = fixture.client.board().single { it.id == ticket.id }
            assertEquals("FAILED", after.lastRun!!.outcome)
            assertEquals(1, fixture.daemon.manager.list().size, "a failed run is never restarted")
        }
    }
}
