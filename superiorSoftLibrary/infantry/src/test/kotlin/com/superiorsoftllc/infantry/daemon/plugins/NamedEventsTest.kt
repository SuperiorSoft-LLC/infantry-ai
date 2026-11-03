package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SCRIPT-3: the generic named-event bus, the daemon's own events and the built-in emit tool. */
class NamedEventsTest {
    @Test fun `exact name and prefix subscriptions each hear only theirs`() {
        val bus = NamedEventBus()
        val exact = CopyOnWriteArrayList<String>(); val prefix = CopyOnWriteArrayList<String>(); val all = CopyOnWriteArrayList<String>()
        bus.subscribe("ticket.moved") { exact += it.name }
        bus.subscribe("ticket.*") { prefix += it.name }
        bus.subscribe("*") { all += it.name }
        bus.publish("ticket.moved", source = "t"); bus.publish("ticket.created", source = "t"); bus.publish("mail.sent", source = "t")
        assertEquals(listOf("ticket.moved"), exact)
        assertEquals(listOf("ticket.moved", "ticket.created"), prefix)
        assertEquals(3, all.size)
    }

    @Test fun `payload and cause chain round trip and a closed subscription hears nothing`() {
        val bus = NamedEventBus()
        val got = CopyOnWriteArrayList<NamedEvent>()
        val sub = bus.subscribe("a.b") { got += it }
        val root = bus.publish("a.b", """{"x":1}""", "plugin-1")
        val child = bus.publish("a.b", "{}", "plugin-2", causeId = root.causeId, depth = 1)
        assertEquals(root.id, root.causeId)
        assertEquals("""{"x":1}""", got[0].payload); assertEquals("plugin-1", got[0].source)
        assertEquals(root.id, child.causeId); assertEquals(1, got[1].depth)
        sub.close(); bus.publish("a.b", source = "x")
        assertEquals(2, got.size)
    }

    @Test fun `bad names and payloads are refused and a failing listener does not break others`() {
        val bus = NamedEventBus()
        assertFailsWith<IllegalArgumentException> { bus.publish("Bad Name", source = "x") }
        assertFailsWith<IllegalArgumentException> { bus.publish("a.b", "[1]", "x") }
        assertFailsWith<IllegalArgumentException> { bus.subscribe("UPPER") { } }
        val got = CopyOnWriteArrayList<String>()
        bus.subscribe("a.b") { error("boom") }; bus.subscribe("a.b") { got += it.name }
        bus.publish("a.b", source = "x")
        assertEquals(listOf("a.b"), got)
    }

    @Test fun `a ticket move produces ticket moved with from and to and other daemon happenings are named`() {
        val out = CopyOnWriteArrayList<Pair<String, String>>()
        val pub = DaemonEventPublisher { n, p -> out += n to p }
        pub.ticket(TicketEvent(1, "t1", TicketEvent.MOVED, "todo", "in_progress"))
        pub.ticket(TicketEvent(2, "t1", TicketEvent.DONE, "in_progress", "done"))
        pub.ticket(TicketEvent(3, "t2", TicketEvent.CREATED, null, "todo"))
        pub.sessionStatus("s1", "BUSY", "backend", "p"); pub.sessionStatus("s1", "IDLE", "backend", "p"); pub.sessionStatus("s1", "IDLE", "backend", "p")
        pub.sessionEnded("s1")
        assertEquals(listOf("ticket.moved", "ticket.moved", "ticket.created", "session.started", "session.turn_started", "session.idle", "session.turn_ended", "session.ended"), out.map { it.first })
        assertTrue("\"from\":\"todo\"" in out[0].second && "\"to\":\"in_progress\"" in out[0].second)
        assertTrue(out.map { it.first }.all { it in NamedEventBus.DAEMON_EVENTS })
    }

    @Test fun `the emit tool reaches a subscriber for any role and is listed with a write action`() {
        val host = DaemonPluginHost.empty()
        val latch = CountDownLatch(1); val got = CopyOnWriteArrayList<NamedEvent>()
        host.namedEvents.subscribe("merge.*") { got += it; latch.countDown() }
        val args = JsonObject(mapOf("name" to JsonPrimitive("merge.done"), "payload" to JsonPrimitive("""{"ticket":"t1"}""")))
        for (role in listOf("vc", "backend", "qa")) {
            val client = JsonObject(mapOf("sessionId" to JsonPrimitive("sess-$role"), "role" to JsonPrimitive(role)))
            val r = host.callTool("infantry_event_emit", args, Caller(owner = false, agent = true), client) { _, _ -> Envelope(Envelope.ERROR, "unused") }
            assertTrue(!r.isError, r.text)
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(3, got.size)
        assertEquals("session:sess-vc", got[0].source); assertEquals("""{"ticket":"t1"}""", got[0].payload)
        assertEquals("write", host.actionFor(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, """{"name":"infantry_event_emit"}"""))?.kind)
        assertTrue(host.toolDescriptors().toString().contains("infantry_event_emit"))
        val bad = host.callTool("infantry_event_emit", JsonObject(mapOf("name" to JsonPrimitive("Not Dotted"))), Caller(owner = true)) { _, _ -> Envelope(Envelope.ERROR, "x") }
        assertTrue(bad.isError)
    }
}
