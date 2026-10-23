package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.TICKET_TAG_TITLE_MAX
import com.superiorsoftllc.agenthq.ui.model.ticketTagLabel
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentTicketTagUiTest {
    private fun agent(id: String, ticketId: String?) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", ticketId = ticketId),
        role = "frontend",
    )

    private val withTicket = agent("aaaa11112222", "ticket-120d0289")
    private val without = agent("bbbb11112222", null)
    private val state = AppState(
        agents = listOf(withTicket, without).associateBy { it.id },
        tickets = listOf(Ticket(id = "ticket-120d0289", title = "Agent cards show which ticket each worker is tied to", status = "in_progress")),
    )

    @Test
    fun rowAndHeaderShowTheTagOnlyWhenTheSessionHasATicket() {
        runDesktopComposeUiTest(900, 400) {
            setContent {
                Column {
                    AgentRow(withTicket, state, selected = false, onClick = {}, onRemove = {})
                    AgentRow(without, state, selected = false, onClick = {}, onRemove = {})
                    CenterHeader(withTicket, spawnMode = false, state = state)
                }
            }
            onNodeWithTag("agent-row-ticket-${withTicket.id}").assertIsDisplayed()
            onNodeWithTag("agent-row-ticket-${without.id}").assertDoesNotExist()
            onNodeWithTag(AGENT_HEADER_TICKET_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun clickingTheTagOpensItsTicket() {
        var opened: String? = null
        runDesktopComposeUiTest(900, 400) {
            setContent { AgentRow(withTicket, state, selected = false, onClick = {}, onRemove = {}, onOpenTicket = { opened = it }) }
            onNodeWithTag("agent-row-ticket-${withTicket.id}").performClick()
            assertEquals("ticket-120d0289", opened)
        }
    }

    @Test
    fun longTitlesAreTruncatedAndUnknownTitlesShowTheIdAlone() {
        val label = ticketTagLabel("ticket-120d0289", "x".repeat(100))
        assertTrue(label.endsWith("…"))
        assertEquals("120d0289 ".length + TICKET_TAG_TITLE_MAX, label.length)
        assertEquals("120d0289 short", ticketTagLabel("ticket-120d0289", "short"))
        assertEquals("120d0289", ticketTagLabel("ticket-120d0289", null))
    }
}
