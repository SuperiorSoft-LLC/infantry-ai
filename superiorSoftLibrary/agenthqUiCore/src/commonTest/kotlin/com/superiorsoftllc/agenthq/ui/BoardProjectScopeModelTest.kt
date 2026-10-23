package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-BOARD-PROJ: the board is scoped to the selected project. `column(status)` shows only the tickets
 * whose projectId equals the active project, exactly like workspaces (`visibleWorkspaces`) are scoped.
 * The legacy default (no project selected) is the whole board, so a ticket with a null projectId still
 * appears there and nowhere else.
 */
class BoardProjectScopeModelTest {
    private fun ticket(id: String, projectId: String?) =
        Ticket(id = id, title = "T $id", projectId = projectId, status = TicketColumns.TODO, position = 0)

    private fun state(activeProjectId: String?) = AppState(
        projects = listOf(ProjectView("A", "Alpha"), ProjectView("B", "Beta")),
        activeProjectId = activeProjectId,
        tickets = listOf(ticket("a", "A"), ticket("b", "B"), ticket("legacy", null)),
    )

    @Test fun `a selected project shows only its own tickets`() {
        assertEquals(listOf("a"), state("A").column(TicketColumns.TODO).map { it.id }, "project A's ticket only under A")
        assertEquals(listOf("b"), state("B").column(TicketColumns.TODO).map { it.id }, "project B's ticket only under B")
    }

    @Test fun `switching projects swaps the board`() {
        val onA = state("A")
        val onB = onA.copy(activeProjectId = "B")
        assertEquals(listOf("a"), onA.column(TicketColumns.TODO).map { it.id })
        assertEquals(listOf("b"), onB.column(TicketColumns.TODO).map { it.id })
        assertEquals(listOf("a"), onB.copy(activeProjectId = "A").column(TicketColumns.TODO).map { it.id }, "back to A swaps back")
    }

    @Test fun `with no project the whole board shows, including legacy tickets`() {
        assertEquals(
            listOf("a", "b", "legacy"),
            state(null).column(TicketColumns.TODO).map { it.id },
            "no project selected = today's global backlog, legacy null-project ticket included",
        )
    }

    @Test fun `legacy tickets with a null project id never leak into a project view`() {
        assertEquals(listOf("a"), state("A").column(TicketColumns.TODO).map { it.id }, "legacy ticket stays out of A")
        assertEquals(listOf("b"), state("B").column(TicketColumns.TODO).map { it.id }, "legacy ticket stays out of B")
    }
}