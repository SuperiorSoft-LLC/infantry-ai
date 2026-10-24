package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.BASE_BRANCH_DEFAULT_LABEL
import com.superiorsoftllc.agenthq.ui.model.BaseBranchChoice
import com.superiorsoftllc.agenthq.ui.model.baseBranchChoices
import com.superiorsoftllc.agenthq.ui.model.isCurrentBase
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketStart
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BASE-1b: the base-branch picker's rows, its wire request, and what the window says after a start. */
class BaseBranchModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `a blank query offers Default first then every branch, marking the checked-out one`() {
        val rows = baseBranchChoices(listOf("main", "develop"), current = "main", query = " ")
        assertEquals(listOf(null, "main", "develop"), rows.map { it.branch })
        assertEquals(BASE_BRANCH_DEFAULT_LABEL, rows.first().label)
        assertEquals(listOf(false, true, false), rows.map { it.checkedOut })
    }

    @Test fun `typing keeps the case-insensitive substring matches, Default only when its label matches`() {
        val names = listOf("main", "develop", "feature/DEV-12") + (1..250).map { "topic/$it" }
        assertEquals(listOf("develop", "feature/DEV-12"), baseBranchChoices(names, "main", "dev").map { it.branch })
        assertEquals(listOf(null), baseBranchChoices(names, "main", "DEFAULT").map { it.branch })
        // topic/2, topic/20..29 and topic/200..250: 1 + 10 + 51 rows.
        assertEquals(62, baseBranchChoices(names, "main", "TOPIC/2").size)
        assertTrue(baseBranchChoices(emptyList(), null, "x").isEmpty())
        assertEquals(listOf(null), baseBranchChoices(emptyList(), null, "").map { it.branch })
    }

    @Test fun `the current setting is the stored branch, or Default when none is stored`() {
        assertTrue(isCurrentBase(BaseBranchChoice(null), ""))
        assertTrue(isCurrentBase(BaseBranchChoice("develop"), "develop"))
        assertTrue(!isCurrentBase(BaseBranchChoice(null), "develop"))
        assertTrue(!isCurrentBase(BaseBranchChoice("main"), ""))
    }

    @Test fun `project_base sets with branch and clears with clear`() {
        assertEquals(Envelope.PROJECT_BASE, WireRequests.projectBase("p1", "develop").type)
        assertEquals("""{"projectId":"p1","branch":"develop"}""", WireRequests.projectBase("p1", "develop").data)
        assertEquals("""{"projectId":"p1","clear":true}""", WireRequests.projectBase("p1", null).data)
    }

    @Test fun `a project frame carries the base branch into the project view`() {
        val s = reduce(AppState(), Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "A", baseBranch = "develop")))), 1)
        assertEquals("develop", s.projects.single().baseBranch)
        val cleared = reduce(s, Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), Project("p1", "A"))), 2)
        assertEquals("", cleared.projects.single().baseBranch)
    }

    @Test fun `a started ticket says what it was cut from, once, as information`() {
        val t = Ticket("t1", "Ship it", status = "in_progress", sessionId = "s1", start = TicketStart(true, "started", "cut from develop 1a2b3c4d"))
        val frame = Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), t))
        val s = reduce(reduce(AppState(), frame, 1), frame, 2)
        assertEquals(1, s.notices.size, "the answer and the push say it once: ${s.notices}")
        assertEquals("started \"Ship it\": cut from develop 1a2b3c4d", s.notices.single().text)
        assertTrue(s.notices.single().info)
        // A start with nothing to say (no cut) adds nothing.
        val plain = Ticket("t2", "Plain", status = "in_progress", start = TicketStart(true, "started"))
        assertTrue(reduce(AppState(), Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), plain)), 1).notices.isEmpty())
    }
}
