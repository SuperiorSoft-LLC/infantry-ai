package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renaming a project is an edit `project_save` (id + name): the id, and so everything keyed by it, stays. */
class ProjectRenameDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timeoutMs = 4_000L

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

    @Test
    fun renameKeepsIdOtherFieldsAndTicketsResolve() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Old name","goal":"keep me"}""")
        val created = json.decodeFromString(Project.serializer(), fixture.client.await(Envelope.PROJECT, timeoutMs = timeoutMs).data)
        fixture.send(Envelope.TICKET_SAVE, """{"title":"Survives rename","projectId":"${created.id}"}""")
        val ticket = json.decodeFromString(Ticket.serializer(), fixture.client.await(Envelope.TICKET, timeoutMs = timeoutMs).data)

        fixture.send(Envelope.PROJECT_SAVE, """{"id":"${created.id}","name":"  New name  "}""")
        val renamed = json.decodeFromString(
            Project.serializer(), fixture.client.await(Envelope.PROJECT, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(created.id, renamed.id)
        assertEquals("New name", renamed.name)
        assertEquals("keep me", renamed.goal, "an edit that leaves the goal out keeps it")

        fixture.send(Envelope.TICKET_GET, ticket.id)
        val fetched = json.decodeFromString(
            Ticket.serializer(), fixture.client.await(Envelope.TICKET, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(created.id, fetched.projectId, "the ticket still points at the renamed project")
    }

    @Test
    fun blankNameIsRefusedAndNothingChanges() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Stays"}""")
        val created = json.decodeFromString(Project.serializer(), fixture.client.await(Envelope.PROJECT, timeoutMs = timeoutMs).data)

        fixture.send(Envelope.PROJECT_SAVE, """{"id":"${created.id}","name":"   "}""")
        val error = fixture.client.await(Envelope.ERROR, timeoutMs = timeoutMs)
        assertTrue("project_save refused" in error.data, error.data)

        fixture.send(Envelope.PROJECT_GET, created.id)
        val again = json.decodeFromString(
            Project.serializer(), fixture.client.await(Envelope.PROJECT, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals("Stays", again.name)
    }
}
