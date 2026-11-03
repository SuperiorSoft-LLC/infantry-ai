// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-BOARD-PROJ: the board answers a `ticket_list` request carrying a project id with ONLY that
 * project's tickets, and keeps the empty answer (today's whole board) for a request with none.
 * A ticket saved with a project id round-trips it.
 */
class BoardProjectScopeDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /** Create a project with [name] and return the id the daemon minted. */
    private fun DaemonFixture.createProject(name: String): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name"}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT &&
                json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        val id = json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
        check(id.isNotBlank()) { "daemon minted a blank project id" }
        return id
    }

    /** Wait for the `ticket` frame whose decoded ticket satisfies [match]. */
    private fun WsTestClient.awaitTicket(description: String, match: (Ticket) -> Boolean): Ticket {
        val frame = awaitWhere(description) { envelope ->
            envelope.type == Envelope.TICKET && runCatching {
                json.decodeFromString(Ticket.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    /** Wait for the `tickets` frame whose decoded board satisfies [match]. */
    private fun WsTestClient.awaitBoard(description: String, match: (List<Ticket>) -> Boolean): List<Ticket> {
        val frame = awaitWhere(description) { envelope ->
            envelope.type == Envelope.TICKETS && runCatching {
                json.decodeFromString(ListSerializer(Ticket.serializer()), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    /** Save a ticket with a title and project location; returns the stored ticket. */
    private fun DaemonFixture.saveTicket(title: String, projectId: String?): Ticket {
        val body = if (projectId == null) """{"title":"$title"}""" else """{"title":"$title","projectId":"$projectId"}"""
        send(Envelope.TICKET_SAVE, body)
        return client.awaitTicket("$title saved") { it.title == title }
    }

    /** Ask `ticket_list` with [projectId] (empty string = no filter) and decode the answer board. */
    private fun DaemonFixture.listTickets(projectId: String = ""): List<Ticket> {
        val already = client.of(Envelope.TICKETS).size
        send(Envelope.TICKET_LIST, projectId)
        val frame = client.await(Envelope.TICKETS, occurrence = already, timeoutMs = 4_000)
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    @Test
    fun `a ticket_list carrying a project id returns only that project's tickets`() = withDaemon { fixture ->
        val alpha = fixture.createProject("Alpha")
        val beta = fixture.createProject("Beta")

        val alphaTicket = fixture.saveTicket("alpha ticket", alpha)
        val betaTicket = fixture.saveTicket("beta ticket", beta)

        assertEquals(alpha, alphaTicket.projectId, "the save carries the project it was given")
        assertEquals(beta, betaTicket.projectId)

        val alphaBoard = fixture.listTickets(alpha)
        assertEquals(listOf("alpha ticket"), alphaBoard.map { it.title }, "Alpha project's board is Alpha's ticket only")

        val betaBoard = fixture.listTickets(beta)
        assertEquals(listOf("beta ticket"), betaBoard.map { it.title }, "Beta project's board is Beta's ticket only")
    }

    @Test
    fun `empty ticket_list data stays the whole board, so a legacy client is unchanged`() = withDaemon { fixture ->
        val alpha = fixture.createProject("Alpha")
        val legacy = fixture.saveTicket("legacy ticket", null)
        fixture.saveTicket("alpha ticket", alpha)

        fixture.send(Envelope.TICKET_LIST, "")
        val all = fixture.client.awaitBoard("empty answer is the whole board") { it.size == 2 }
        assertEquals(
            setOf("legacy ticket", "alpha ticket"),
            all.map { it.title }.toSet(),
            "empty data keeps the all-tickets answer",
        )
        assertEquals(null, legacy.projectId, "a ticket saved without a project keeps none")
    }
}
