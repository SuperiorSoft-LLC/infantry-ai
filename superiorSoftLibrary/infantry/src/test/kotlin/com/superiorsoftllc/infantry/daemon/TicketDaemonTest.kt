// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.5.0 (story 9.2), over the REAL daemon and a real WebSocket: a ticket created with
 * `ticket_save` comes back whole, is readable by id with `ticket_get`, and the board lists it with
 * `ticket_list`.
 *
 * The board is driven through the daemon, never around it: the fixture assembles the real Ktor server,
 * the real board and the real SQLite store, so every assertion is about what a client would actually
 * receive on the wire.
 */
class TicketDaemonTest {

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

    /** The first `ticket` frame from [client] whose decoded ticket satisfies [match]. */
    private fun WsTestClient.awaitTicket(description: String, match: (Ticket) -> Boolean): Ticket {
        val frame = awaitWhere(description) { envelope ->
            envelope.type == Envelope.TICKET && runCatching {
                json.decodeFromString(Ticket.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    /** The first `tickets` frame from [client] whose board satisfies [match]. */
    private fun WsTestClient.awaitBoard(description: String, match: (List<Ticket>) -> Boolean): List<Ticket> {
        val frame = awaitWhere(description) { envelope ->
            envelope.type == Envelope.TICKETS && runCatching {
                json.decodeFromString(ListSerializer(Ticket.serializer()), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    @Test
    fun aSavedTicketComesBackWithEveryField() = withDaemon { fixture ->
        fixture.send(Envelope.TICKET_SAVE, """{"title":"Add login","ticketType":"enhancement"}""")
        val frame = fixture.client.await(Envelope.TICKET)
        val saved = json.decodeFromString(Ticket.serializer(), frame.data)

        assertEquals("Add login", saved.title)
        assertEquals("enhancement", saved.ticketType)
        assertEquals("todo", saved.status)
        assertEquals(0, saved.position)
        assertEquals(false, saved.wontFix)
        assertTrue(saved.links.isEmpty(), "no links yet: ${saved.links}")
        assertTrue(saved.heldBy.isEmpty(), "nothing holds it: ${saved.heldBy}")
        assertNull(saved.holdReason)
        assertTrue(saved.id.startsWith("ticket-"), "an id the daemon chose: ${saved.id}")

        // The daemon writes the defaults and the nulls rather than leaving a key out for a client to guess.
        assertEquals(
            setOf(
                "id", "title", "description", "ticketType", "status", "position",
                "wontFix", "links", "heldBy", "holdReason", "createdAt", "updatedAt",
                // Contract 1.6.0 (story 9.3): executor pins, the queue, the session, and the derived views.
                "agentId", "provider", "model", "useWorktree", "worktreeBranch", "includeLinkedTasksContext",
                "queuedFor", "queuedAt", "sessionId",
                "executorOverrides", "effectiveExecutor", "effectiveContext", "lastRun", "start",
                // MVP Sprint-Zero (slice 0 freeze): the project a ticket belongs to (slice A implements).
                "projectId",
                // CLI-T3 patch (675e7c4c): the epic a ticket is filed under (null when none).
                "epicId",
                // BRANCH-PER-TASK 1/3 (contract 1.32.0): the branch a start cut for the ticket, and the commit it was cut from.
                "branch", "base",
                // P10-K11 (contract 1.55.0): the skills the ticket attaches, merge or replace, and its skills switch.
                "skillIds", "skillsMode", "includeSkillsContext",
                // IMG-3 (contract 1.85.0): the image attachments a ticket carries (empty when none).
                "images",
                // TICKET-WEIGHT (contract 1.104.0): the weight, null when unweighted.
                "weight",
                // CONTEXT-LIMIT-1 (contract 1.146.0): the worker's context limit, and the effective one {value, source, support, how}.
                "contextLimit", "effectiveContextLimit",
                // EFFORT-1 (contract 1.148.0): the worker's reasoning effort, and the effective one {value, source, support, how}.
                "effort", "effectiveEffort",
                // AGENT-TASKS-3: the agent queue that holds a todo ticket, its place, and the agent's name (null when none).
                "queuedAgent", "queueRank", "queuedAgentName",
                // AGENT-TASKS-2: the derived has-work view (null when the ticket has no work ahead of its base).
                "hasWork",
                // RUNNER-POOL-3 (de415ebd): the runner a ticket is pinned to (null = the pool picks).
                "runner",
            ),
            json.parseToJsonElement(frame.data).jsonObject.keys,
        )

        // ticket_get answers with the very same ticket (the id is the raw payload, not JSON).
        fixture.send(Envelope.TICKET_GET, saved.id)
        val fetched = json.decodeFromString(
            Ticket.serializer(),
            fixture.client.await(Envelope.TICKET, occurrence = 1).data,
        )
        assertEquals(saved, fetched)

        // ticket_list answers with the board that holds exactly this ticket.
        fixture.send(Envelope.TICKET_LIST, "")
        val listed = json.decodeFromString(
            ListSerializer(Ticket.serializer()),
            fixture.client.await(Envelope.TICKETS).data,
        )
        assertEquals(listOf(saved), listed)
    }

    /**
     * A refused request is answered with an `error` frame (`<frame> refused: <reason>`) and leaves the
     * board exactly as it was: the daemon refuses loudly rather than half-applying a command it cannot
     * honour, and a refusal to change one thing never quietly changes another.
     */
    /**
     * Contract 1.31.0 (CLI-T3 patch): `epicId` rides `ticket_save` and comes back on `Ticket`. A create
     * names the epic it is filed under; an edit without `epicId` keeps it; M2-17 (contract 1.60.0): an edit
     * with an empty `epicId` clears it. A ticket filed under no epic answers null. The epic id is opaque here.
     */
    @Test
    fun anEpicIdIsEchoedOnCreateKeptWhenAbsentAndClearedWhenEmpty() = withDaemon { fixture ->
        fixture.send(Envelope.TICKET_SAVE, """{"title":"Filed","epicId":"epic-login"}""")
        val created = json.decodeFromString(Ticket.serializer(), fixture.client.await(Envelope.TICKET).data)
        assertEquals("epic-login", created.epicId, "the create must carry its epic back: $created")

        fixture.send(Envelope.TICKET_SAVE, """{"id":"${created.id}","title":"Renamed"}""")
        val edited = fixture.client.awaitTicket("the edit of ${created.id}") { it.id == created.id && it.title == "Renamed" }
        assertEquals("epic-login", edited.epicId, "an edit without epicId keeps the epic: $edited")

        fixture.send(Envelope.TICKET_SAVE, """{"id":"${created.id}","epicId":""}""")
        val cleared = fixture.client.awaitTicket("the epic clear of ${created.id}") { it.id == created.id && it.epicId == null }
        assertEquals("Renamed", cleared.title, "clearing the epic leaves the title: $cleared")

        fixture.send(Envelope.TICKET_SAVE, """{"title":"Loose"}""")
        val loose = fixture.client.awaitTicket("the loose ticket") { it.title == "Loose" }
        assertNull(loose.epicId, "a ticket filed under no epic carries null: $loose")
    }

    @Test
    fun refusedTicketRequestsAreErrorsAndChangeNothing() = withDaemon { fixture ->
        fixture.send(Envelope.TICKET_SAVE, """{"title":"A"}""")
        val a = json.decodeFromString(Ticket.serializer(), fixture.client.await(Envelope.TICKET, occurrence = 0).data)
        fixture.send(Envelope.TICKET_SAVE, """{"title":"B"}""")
        val b = json.decodeFromString(Ticket.serializer(), fixture.client.await(Envelope.TICKET, occurrence = 1).data)

        // Moving to in_progress now starts a session (story 9.3). With no profile the ticket is
        // unassigned, so the start is REFUSED on the `ticket` frame and the board is unchanged.
        val moved = fixture.client.answerTo(Envelope.TICKET_MOVE, """{"id":"${a.id}","status":"in_progress"}""")
        val movedTicket = moved.ticketOrNull()
        assertTrue(
            movedTicket?.start?.reason == "start_refused",
            "an unassigned start answers a ticket carrying start_refused, got ${moved.type}: ${moved.data.take(120)}",
        )
        assertEquals("todo", movedTicket!!.status)

        // A command the daemon does not fully understand is refused, never half-applied.
        fixture.send(Envelope.TICKET_SAVE, """{"bogus":1}""")
        fixture.client.awaitWhere("ticket_save refusal") {
            it.type == Envelope.ERROR && it.data.contains("ticket_save refused: malformed ticket_save payload")
        }

        // An unknown id is refused, not answered with an empty ticket.
        fixture.send(Envelope.TICKET_GET, "zz")
        fixture.client.awaitWhere("ticket_get refusal") {
            it.type == Envelope.ERROR && it.data.contains("ticket_get refused: no ticket 'zz'")
        }

        // A legal link succeeds: A is blocked_by B, so A becomes pending.
        fixture.send(Envelope.TICKET_LINK, """{"ticketId":"${a.id}","relatedId":"${b.id}","type":"blocked_by"}""")
        val linked = fixture.client.awaitWhere("A linked to B, so pending") { frame ->
            frame.type == Envelope.TICKET && runCatching {
                json.decodeFromString(Ticket.serializer(), frame.data)
            }.getOrNull()?.let { it.id == a.id && it.status == "pending" } == true
        }
        assertEquals("pending", json.decodeFromString(Ticket.serializer(), linked.data).status)

        // The reverse link would close a cycle: B blocked_by A while A is already blocked_by B.
        fixture.send(Envelope.TICKET_LINK, """{"ticketId":"${b.id}","relatedId":"${a.id}","type":"blocked_by"}""")
        fixture.client.awaitWhere("cycle refusal") {
            it.type == Envelope.ERROR && it.data.contains("would close a cycle")
        }

        // A ticket cannot be linked to itself.
        fixture.send(Envelope.TICKET_LINK, """{"ticketId":"${a.id}","relatedId":"${a.id}","type":"relates_to"}""")
        fixture.client.awaitWhere("self-link refusal") {
            it.type == Envelope.ERROR && it.data.contains("cannot be linked to itself")
        }

        // Nothing a refused request tried changed the board: still two tickets, A pending and B todo.
        fixture.send(Envelope.TICKET_LIST, "")
        val listed = json.decodeFromString(
            ListSerializer(Ticket.serializer()),
            fixture.client.await(Envelope.TICKETS).data,
        )
        assertEquals(2, listed.size, "two tickets: ${listed.map { it.id }}")
        assertEquals("pending", listed.first { it.id == a.id }.status)
        assertEquals("todo", listed.first { it.id == b.id }.status)
    }

    /**
     * Contract 1.5.0 (story 9.2): every committed board change reaches EVERY connected client, not only
     * the one that asked. A create, a link, a move, a reorder and a delete are each pushed to `one` and
     * `two` alike -- the requester gets the same push (for it, that IS the answer), and the daemon never
     * keeps one client's view of the board private.
     */
    @Test
    fun boardChangesArePushedToEveryClient() = withDaemon { fixture ->
        val one = fixture.client
        val two = fixture.connectClient()

        // 1. A save is pushed to every client: both got the very same ticket, id and all.
        val saved = mutableListOf<Ticket>()
        for (title in listOf("A", "B", "C")) {
            one.send(Envelope.TICKET_SAVE, """{"title":"$title"}""")
            val forOne = one.awaitTicket("$title saved, pushed to one") { it.title == title && it.status == "todo" }
            val forTwo = two.awaitTicket("$title saved, pushed to two") { it.id == forOne.id }
            assertEquals(forOne, forTwo, "one and two received the same $title")
            saved += forOne
        }
        val (a, b, c) = saved

        // 2. A link is pushed to every client: A is held by B, so the daemon moves it to pending.
        one.send(Envelope.TICKET_LINK, """{"ticketId":"${a.id}","relatedId":"${b.id}","type":"blocked_by"}""")
        val aPending = one.awaitTicket("A held by B, on one") { it.id == a.id && it.status == "pending" }
        assertEquals(listOf(b.id), aPending.heldBy)
        two.awaitTicket("A held by B, on two") { it.id == a.id && it.status == "pending" && it.heldBy == listOf(b.id) }

        // 3. Moving B to done is pushed to every client: A is released back to todo. A's blocked_by link
        //    is what tells this frame apart from the save, which was already todo with no links.
        one.send(Envelope.TICKET_MOVE, """{"id":"${b.id}","status":"done"}""")
        val aReleased = one.awaitTicket("A released, on one") {
            it.id == a.id && it.status == "todo" && it.links.any { link -> link.type == "blocked_by" && link.ticketId == b.id }
        }
        assertTrue(aReleased.heldBy.isEmpty(), "nothing holds A any more: ${aReleased.heldBy}")
        two.awaitTicket("A released, on two") {
            it.id == a.id && it.status == "todo" && it.heldBy.isEmpty() &&
                it.links.any { link -> link.type == "blocked_by" && link.ticketId == b.id }
        }

        // 4. A reorder is a whole `tickets` push: A is on top of todo and C sits below it.
        one.send(Envelope.TICKET_REORDER, """{"status":"todo","ids":["${a.id}"]}""")
        val reordered = one.awaitBoard("reorder pushed to one") { board ->
            board.firstOrNull { it.id == a.id }?.position == 0 && board.firstOrNull { it.id == c.id }?.position == 1
        }
        assertEquals(0, reordered.first { it.id == a.id }.position)
        assertEquals(1, reordered.first { it.id == c.id }.position)
        two.awaitBoard("reorder pushed to two") { board ->
            board.firstOrNull { it.id == a.id }?.position == 0 && board.firstOrNull { it.id == c.id }?.position == 1
        }

        // 5. A delete is a whole `tickets` push, without the ticket that went.
        one.send(Envelope.TICKET_DELETE, c.id)
        val afterDelete = one.awaitBoard("delete pushed to one") { board -> board.none { it.id == c.id } }
        assertTrue(afterDelete.none { it.id == c.id }, "C is gone: ${afterDelete.map { it.id }}")
        two.awaitBoard("delete pushed to two") { board -> board.none { it.id == c.id } }
    }

    /**
     * Contract 1.5.0 (story 9.2): a read is answered to the client that asked and to no one else. `two`
     * asking for the board does not put a `tickets` frame on `one`; `one` asking for a ticket gets its
     * own answer back.
     */
    @Test
    fun aReadIsAnsweredOnlyToItsSender() = withDaemon { fixture ->
        val one = fixture.client
        val two = fixture.connectClient()

        one.send(Envelope.TICKET_SAVE, """{"title":"A"}""")
        val a = one.awaitTicket("A saved, pushed to one") { it.title == "A" }
        two.awaitTicket("A saved, pushed to two") { it.id == a.id }

        // A board read answers its sender: `two` gets the board, `one` is not pushed it.
        two.send(Envelope.TICKET_LIST, "")
        val listed = two.awaitBoard("ticket_list answered to two") { board -> board.any { it.id == a.id } }
        assertEquals(listOf(a), listed)

        // A ticket read is a round trip: the second `ticket` frame on `one` is the answer to its get,
        // so by the assertion below anything the daemon owed `one` has already arrived.
        one.send(Envelope.TICKET_GET, a.id)
        val fetched = json.decodeFromString(
            Ticket.serializer(),
            one.await(Envelope.TICKET, occurrence = 1).data,
        )
        assertEquals(a, fetched)

        // The board read stayed with its sender: `one` never saw a `tickets` frame.
        assertTrue(
            one.of(Envelope.TICKETS).isEmpty(),
            "the board read went to two, not one: ${one.of(Envelope.TICKETS)}",
        )
    }
}
