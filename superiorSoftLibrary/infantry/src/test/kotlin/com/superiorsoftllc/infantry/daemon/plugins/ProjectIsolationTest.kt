// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.cli.mcp.ProjectScope
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROJECT-ISOLATION-1: no project's tickets, sessions or events reach another project; the owner still reaches every project. */
class ProjectIsolationTest {
    private val tickets = mutableMapOf("ticket-a1" to "A", "ticket-b1" to "B")
    private val sessions = mutableMapOf(
        "s-a-worker" to ProjectIsolation.Who("A", "backend"),
        "s-a-pm" to ProjectIsolation.Who("A", "pm"),
        "s-b-worker" to ProjectIsolation.Who("B", "frontend"),
        "s-owner" to ProjectIsolation.Who("A", "owner"),
        "s-assistant" to ProjectIsolation.Who("A", "assistant"),
    )
    private val iso = ProjectIsolation(ticketProject = { tickets[it] }, session = { sessions[it] })

    private fun args(vararg kv: Pair<String, Any>): JsonObject = buildJsonObject {
        kv.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    private fun refusal(sid: String?, a: JsonObject, caller: Caller = Caller(owner = true), cross: Boolean = false) =
        iso.toolRefusal("t", a, caller, sid, null, cross)

    @Test fun `a worker and a pm are refused another project's ticket, session and project`() {
        for (sid in listOf("s-a-worker", "s-a-pm")) {
            assertNotNull(refusal(sid, args("ticketId" to "ticket-b1")), sid)
            assertNotNull(refusal(sid, args("sessionId" to "s-b-worker")), sid)
            assertNotNull(refusal(sid, args("targetSessionId" to "s-b-worker")), sid)
            assertNotNull(refusal(sid, args("projectId" to "B")), sid)
            assertNotNull(refusal(sid, args("project" to "B")), sid)
            assertTrue(refusal(sid, args("ticketId" to "ticket-b1"))!!.contains("belongs to project 'B'"))
        }
    }

    @Test fun `projectId all is refused for every agent of a project, pm included`() {
        assertNotNull(refusal("s-a-worker", args("projectId" to "all")))
        assertNotNull(refusal("s-a-pm", args("projectId" to "all")))
    }

    @Test fun `the caller's own project is allowed`() {
        assertNull(refusal("s-a-worker", args("ticketId" to "ticket-a1")))
        assertNull(refusal("s-a-pm", args("sessionId" to "s-a-worker", "projectId" to "A")))
        assertNull(refusal("s-b-worker", args("ticketId" to "ticket-b1")))
    }

    @Test fun `an unknown target is left to the tool`() {
        assertNull(refusal("s-a-worker", args("ticketId" to "ticket-nope")))
    }

    @Test fun `the owner token (no session) and the owner's agent roles cross projects`() {
        assertNull(refusal(null, args("ticketId" to "ticket-b1", "projectId" to "all")))
        assertNull(refusal("s-owner", args("ticketId" to "ticket-b1")))
        assertNull(refusal("s-assistant", args("projectId" to "all")))
    }

    @Test fun `a project hook's script (Caller project) is confined even with no session`() {
        val hook = Caller(owner = true, project = "A")
        assertNotNull(refusal(null, args("ticketId" to "ticket-b1"), hook))
        assertNotNull(refusal(null, args("projectId" to "all"), hook))
        assertNull(refusal(null, args("ticketId" to "ticket-a1"), hook))
    }

    @Test fun `the active session of a connection scopes like the client session`() {
        val c = Caller(owner = true, activeSession = "s-a-worker")
        assertNotNull(refusal(null, args("ticketId" to "ticket-b1"), c))
    }

    @Test fun `allowCrossProject passes only for a tool whose schema declares it`() {
        val a = args("sessionId" to "s-b-worker", "allowCrossProject" to true)
        assertNull(refusal("s-a-worker", a, cross = true))
        assertNotNull(refusal("s-a-worker", a, cross = false))
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"allowCrossProject":{"type":"boolean"}}}""").jsonObject
        assertTrue(ProjectIsolation.declaresCrossProject(schema))
        assertTrue(!ProjectIsolation.declaresCrossProject(buildJsonObject { putJsonObject("properties") {} }))
    }

    @Test fun `an event is stamped with its ticket's or session's project`() {
        fun proj(s: String) = Json.parseToJsonElement(s).jsonObject["projectId"]?.jsonPrimitive?.content
        assertEquals("B", proj(iso.stamp("ticket.commented", """{"ticketId":"ticket-b1"}""")))
        assertEquals("A", proj(iso.stamp("session.idle", """{"sessionId":"s-a-worker"}""")))
        assertEquals("A", proj(iso.stamp("exec.done", """{"workerSession":"s-a-pm"}""")))
        assertEquals("X", proj(iso.stamp("ticket.moved", """{"ticketId":"ticket-b1","projectId":"X"}""")))
        assertNull(proj(iso.stamp("custom", """{"n":1}""")))
        assertEquals("not json", iso.stamp("x", "not json"))
    }

    @Test fun `a deleted ticket and an ended session still carry their project on their last event`() {
        iso.stamp("ticket.created", """{"ticketId":"ticket-b1"}""")
        iso.stamp("session.spawned", """{"sessionId":"s-b-worker"}""")
        tickets.remove("ticket-b1"); sessions.remove("s-b-worker")
        assertTrue(iso.stamp("ticket.deleted", """{"ticketId":"ticket-b1"}""").contains("\"projectId\":\"B\""))
        assertTrue(iso.stamp("session.ended", """{"sessionId":"s-b-worker"}""").contains("\"projectId\":\"B\""))
        // Forgotten afterwards.
        assertTrue(!iso.stamp("ticket.deleted", """{"ticketId":"ticket-b1"}""").contains("projectId"))
    }

    @Test fun `the named event bus delivers stamped payloads`() {
        val seen = CopyOnWriteArrayList<String>()
        val bus = NamedEventBus(executor = { it.run() }, stamp = iso::stamp)
        bus.subscribe("ticket.commented") { e -> seen += e.payload }
        bus.publish("ticket.commented", """{"ticketId":"ticket-b1"}""", source = "test")
        assertEquals(1, seen.size)
        assertEquals("B", Json.parseToJsonElement(seen[0]).jsonObject["projectId"]?.jsonPrimitive?.content)
    }

    @Test fun `ProjectScope confines a pm to its project and keeps the owner's roles across`() {
        fun rec(id: String, role: String, p: String) = SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
            state = SessionState.IDLE, role = role, projectId = p,
        )
        val all = listOf(rec("pm", "pm", "A"), rec("ow", "owner", "A"))
        fun env(id: String): (String) -> String? = { if (it == "INFANTRY_SESSION_ID") id else null }
        assertNotNull(ProjectScope.resolve("all", all, env("pm"), null).refusal)
        assertNotNull(ProjectScope.resolve("B", all, env("pm"), null).refusal)
        assertNull(ProjectScope.resolve("all", all, env("ow"), null).refusal)
        assertTrue(ProjectScope.crossesProjects(null, hasSession = false))
        assertTrue(!ProjectScope.crossesProjects("pm"))
    }
}
