// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.Notice
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.crossProjectNotices
import com.superiorsoftllc.infantry.ui.model.localNotices
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.withNotice
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Notices about another project are toasts; the chat's notice strip only sees the current project's. */
class CrossProjectToastTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val here = AppState(activeProjectId = "p1", projects = listOf(ProjectView("p1", "Here"), ProjectView("p2", "There")))

    @Test fun `routing rule`() {
        assertTrue(Notice(1, "x", projectId = "p2").isCrossProject("p1"))
        assertFalse(Notice(1, "x", projectId = "p1").isCrossProject("p1"))
        assertFalse(Notice(1, "x").isCrossProject("p1"))
        assertFalse(Notice(1, "x", projectId = "p2").isCrossProject(null))
    }

    @Test fun `notices split into local and cross-project`() {
        val s = here.withNotice(1, "a", null).withNotice(2, "b", null, projectId = "p2").withNotice(3, "c", null, projectId = "p1")
        assertEquals(listOf("a", "c"), s.localNotices().map { it.text })
        assertEquals(listOf("b"), s.crossProjectNotices().map { it.text })
    }

    @Test fun `a ticket created in another project is a cross-project notice`() {
        fun ticket(id: String, project: String) = Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), Ticket(id, "T$id", projectId = project, status = "todo")))
        val s = reduce(reduce(here, ticket("t1", "p2"), 5), ticket("t2", "p1"), 6)
        assertEquals(listOf("p2"), s.crossProjectNotices().map { it.projectId })
        assertTrue(s.localNotices().isEmpty())
    }
}
