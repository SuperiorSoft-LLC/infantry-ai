// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Start assertions whose expected prompt text and context-source values are owned by the context slice. */
class TicketContextStartTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun theStartedPromptCarriesInstructionsTheTicketCoreAndLinkLinesButNeverBodies() {
        val gated = GatedRunner()
        val fixture = DaemonFixture.launch(runner = gated)
        try {
            fixture.client.await(Envelope.READY)
            val instructions = "You are the backend agent."
            fixture.client.saveProfile(
                """{"id":"backend","name":"Backend","role":"backend","instructions":"$instructions"}""",
            )

            val linkedBody = "LINKED-BODY-MUST-NOT-APPEAR"
            val linked = fixture.client.saveTicket("""{"title":"Related work","description":"$linkedBody"}""")
            val epicBody = "E".repeat(20_000)
            val epic = fixture.client.saveTicket("""{"title":"Epic","description":"$epicBody"}""")

            val title = "Implement the endpoint"
            val description = "Add it and test it"
            val ticket = fixture.client.saveTicket(
                """{"title":"$title","description":"$description","agentId":"backend","projectId":"proj-k0"}""",
            )
            fixture.client.answerTo(
                Envelope.TICKET_LINK,
                """{"ticketId":"${ticket.id}","relatedId":"${linked.id}","type":"relates_to"}""",
            )
            fixture.client.answerTo(
                Envelope.TICKET_LINK,
                """{"ticketId":"${ticket.id}","relatedId":"${epic.id}","type":"child_of"}""",
            )

            fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)

            val prompt = gated.prompts.singleOrNull() ?: error("no prompt reached the provider; got ${gated.prompts}")
            val infantryPrefix = "You are running inside Infantry AI. Ticket: ${ticket.id}. Project: proj-k0. Infantry AI tickets, agents, mail and project knowledge live in the Infantry AI daemon and are reached with the `infantry` CLI, never through files. See superiorSoftLibrary/infantry/docs/AGENT-QUICKREF.md."
            assertTrue(prompt.startsWith(infantryPrefix), "infantry section goes first: ${prompt.take(120)}")
            assertTrue(prompt.contains(instructions), "the persona is included: ${prompt.take(120)}")
            assertTrue(prompt.contains(title), "the title is verbatim")
            assertTrue(prompt.contains(description), "the description is verbatim")
            assertTrue(prompt.contains(linked.title), "a linked ticket is a line: ${prompt.take(400)}")
            assertTrue(!prompt.contains(linkedBody), "a linked ticket's body is never composed")
            assertTrue(!prompt.contains(epicBody), "an epic parent's body is never composed")

            val sources = fixture.daemon.manager.list().single().contextSources.associateBy { it.source }
            // BUG-E2E-2: the section ends with the line telling the agent to move its own ticket.
            val infantrySection = infantryPrefix + "\n" + com.superiorsoftllc.infantry.context.TicketContextComposer.ticketMoveLine(ticket.id) + "\n" + com.superiorsoftllc.infantry.context.TicketContextComposer.MCP_LINE + "\n" + com.superiorsoftllc.infantry.context.TicketContextComposer.RELATED_TICKETS_LINE
            assertTrue(prompt.startsWith(infantrySection), "the move instruction closes the infantry section: ${prompt.take(600)}")
            assertEquals(infantrySection.length, sources.getValue("infantry").chars)
            // b7a55d4: the instance's instructions are the persona plus the shipped KNOWLEDGE.md.
            assertEquals(com.superiorsoftllc.infantry.agents.SharedKnowledge.withKnowledge(instructions)!!.length, sources.getValue("instructions").chars)
            assertEquals((title + "\n\n" + description).length, sources.getValue("ticket").chars)
            assertTrue(sources.getValue("linkedTickets").chars > 0, "the link lines are counted too")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /** P10-K0: a ticket filed under no project still tells its agent `Project: none`. */
    @Test
    fun aTicketWithNoProjectStillSaysNone() {
        val gated = GatedRunner()
        val fixture = DaemonFixture.launch(runner = gated)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
            val ticket = fixture.client.saveTicket("""{"title":"No project","agentId":"backend"}""")

            fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)

            val prompt = gated.prompts.singleOrNull() ?: error("no prompt reached the provider; got ${gated.prompts}")
            assertTrue(
                prompt.startsWith("You are running inside Infantry AI. Ticket: ${ticket.id}. Project: none. "),
                "a projectless ticket says none: ${prompt.take(120)}",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
