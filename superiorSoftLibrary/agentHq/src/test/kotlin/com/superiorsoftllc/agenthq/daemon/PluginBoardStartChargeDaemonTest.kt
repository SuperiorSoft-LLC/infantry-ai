package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DP-5h-3e-c: the plugin-built board (the fixture loads the Infantry_Board daemon half through the plugin host, as production
 * does) charges a session-originated `ticket_move` to in_progress to the spawn guard, with the refusal texts the core path had.
 */
class PluginBoardStartChargeDaemonTest {
    private val backend = """{"id":"backend","name":"Backend","role":"backend"}"""

    private fun withDaemon(cap: Int = SpawnGuard.DEFAULT_MAX_PER_POD, depth: Int = SpawnGuard.DEFAULT_MAX_DEPTH, block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(context = FakeTicketContext(), podSpawnCap = cap, podSpawnDepth = depth)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile(backend)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun DaemonFixture.moveFrom(session: String, ticketId: String): Envelope {
        val from = client.mark()
        client.send(Envelope(Envelope.TICKET_MOVE, """{"id":"$ticketId","status":"in_progress"}""", session))
        return client.awaitAfter(from, "the ticket_move answer") {
            (it.type == Envelope.ERROR && it.sessionId == null) || (it.type == Envelope.TICKET && it.ticketOrNull()?.id == ticketId)
        }
    }

    @Test
    fun `a session start is refused at spawn depth with the pod guard's text`() = withDaemon(depth = 0) { fixture ->
        val starter = fixture.daemon.manager.startNew("root")
        val ticket = fixture.client.saveTicket("""{"title":"Deep","agentId":"backend"}""")
        val answer = fixture.moveFrom(starter.id, ticket.id)
        assertEquals(Envelope.ERROR, answer.type)
        assertTrue(answer.data.contains(SpawnGuard.REFUSED_DEPTH), answer.data)
        assertEquals(TicketStatus.TODO, fixture.daemon.requireBoard().get(ticket.id).status, "a refused start moves nothing")
    }

    @Test
    fun `a session start is refused at the pod cap with the pod guard's text`() = withDaemon(cap = 0) { fixture ->
        val starter = fixture.daemon.manager.startNew("root")
        val ticket = fixture.client.saveTicket("""{"title":"Full","agentId":"backend"}""")
        val answer = fixture.moveFrom(starter.id, ticket.id)
        assertEquals(Envelope.ERROR, answer.type)
        assertTrue(answer.data.contains(SpawnGuard.REFUSED_CAP), answer.data)
        assertEquals(TicketStatus.TODO, fixture.daemon.requireBoard().get(ticket.id).status)
    }

    @Test
    fun `an allowed session start records the spawner`() = withDaemon { fixture ->
        val starter = fixture.daemon.manager.startNew("root")
        val ticket = fixture.client.saveTicket("""{"title":"Ok","agentId":"backend"}""")
        val answer = fixture.moveFrom(starter.id, ticket.id)
        val started = answer.requireTicket()
        assertEquals(true, started.start?.ok, "the start succeeded: ${started.start}")
        val worker = assertNotNull(started.sessionId)
        assertEquals(starter.id, fixture.daemon.manager.get(worker)?.spawnerSessionId)
    }

    @Test
    fun `with the plugin present the board is attached before the first frame`() {
        var attachedBeforeStart = false
        val fixture = DaemonFixture.launch(beforeStart = { attachedBeforeStart = it.boardPorts.ops() != null })
        try {
            assertTrue(attachedBeforeStart, "the plugin board is attached after build and before the server serves (and restores) anything")
            fixture.client.await(Envelope.READY)
            val first = fixture.client.answerTo(Envelope.TICKET_LIST)
            assertEquals(Envelope.TICKETS, first.type, "the first ticket frame is answered by the board: ${first.data.take(80)}")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
