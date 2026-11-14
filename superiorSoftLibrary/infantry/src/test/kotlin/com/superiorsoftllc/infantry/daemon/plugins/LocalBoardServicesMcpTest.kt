// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.context.TicketContextComposer
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketDraft
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import com.superiorsoftllc.infantry.epics.EpicStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TicketMcp
import com.superiorsoftllc.infantry.protocol.TicketMcpRequire
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCP-SELECT-1b: the ticket `mcp` field: save/get round trip, the stored JSON, the brief section and the worker self-grant gate. */
class LocalBoardServicesMcpTest {
    private val dir: File = Files.createTempDirectory("infantry-ticket-mcp").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    @AfterTest fun cleanUp() { runCatching { store.close() }; dir.deleteRecursively() }

    private fun session(id: String, role: String, ticketId: String?) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, role = role, projectId = "A", ticketId = ticketId,
    )

    private val sessions = listOf(session("s-worker", "backend", "t1"), session("s-pm", "pm", null))
    private var tick = 0L
    private var idn = 0
    private val board = TicketBoard(store, clock = { ++tick }, idFactory = { "t${++idn}" })
    private val svc = LocalBoardServices(
        board = { board }, epicStore = { EpicStore() }, sessions = { sessions }, link = { null }, gate = { _, _ -> null },
        baseCheck = { _, _ -> },
    )
    private val owner = Caller(owner = true)
    private fun asSession(id: String) = Caller(owner = false, agent = true, activeSession = id)
    private fun <T> BoardResult<T>.ok(): T = if (this is BoardResult.Ok) value else error("expected Ok, got $this")
    private fun BoardResult<*>.refusedText(): String = (this as? BoardResult.Refused)?.text ?: error("expected Refused, got $this")

    private val docs = """{"require":[{"server":"context7","purpose":"read library docs"}]}"""

    @Test fun `mcp round-trips through create, get and update, is kept when absent and cleared when empty`() {
        val made = svc.tickets.create(TicketDraft(title = "docs ticket", projectId = "A", mcp = docs), owner).ok()
        val read = TicketMcp.decode(assertNotNull(svc.tickets.get(made.id, owner).ok().mcp))
        assertEquals(listOf(TicketMcpRequire(server = "context7", purpose = "read library docs")), read.require)
        assertEquals(read, board.get(made.id).mcp)
        // an edit that names no mcp keeps it
        svc.tickets.update(made.id, TicketPatch(title = "renamed"), owner).ok()
        assertEquals(read, board.get(made.id).mcp)
        // a bigger block replaces it
        svc.tickets.update(made.id, TicketPatch(mcp = """{"require":[{"server":"a","purpose":"x"},{"capability":"web","purpose":"y"}]}"""), owner).ok()
        assertEquals(2, board.get(made.id).mcp!!.require.size)
        // an empty one clears it (walled again)
        svc.tickets.update(made.id, TicketPatch(mcp = "{}"), owner).ok()
        assertNull(board.get(made.id).mcp)
        assertNull(svc.tickets.get(made.id, owner).ok().mcp)
    }

    @Test fun `an entry without a purpose or without exactly one of server and capability is refused`() {
        assertTrue(svc.tickets.create(TicketDraft(title = "x", mcp = """{"require":[{"server":"a"}]}"""), owner).refusedText().contains("needs a purpose"))
        assertTrue(svc.tickets.create(TicketDraft(title = "x", mcp = """{"require":[{"purpose":"p"}]}"""), owner).refusedText().contains("exactly one of server or capability"))
        assertTrue(svc.tickets.create(TicketDraft(title = "x", mcp = "not json"), owner).refusedText().contains("not valid JSON"))
        assertEquals(0, board.list().size)
    }

    @Test fun `the start brief lists each granted tool with its purpose, and nothing when walled`() {
        val made = board.save(TicketSave(title = "t", projectId = "A", mcp = TicketMcp(listOf(TicketMcpRequire(server = "context7", purpose = "read library docs"))))).ticket
        val lines = board.get(made.id).mcp!!.require.map { "${it.server ?: it.capability}: ${it.purpose.trim()}" }
        val section = assertNotNull(TicketContextComposer.mcpSection(lines))
        assertTrue(section.startsWith(TicketContextComposer.MCP_HEADING))
        assertTrue(section.contains("- context7: read library docs"))
        assertNull(TicketContextComposer.mcpSection(emptyList()))
    }

    @Test fun `a worker cannot set mcp on its own ticket, the PM and owner can`() {
        val own = board.save(TicketSave(title = "mine", projectId = "A")).ticket
        assertEquals("t1", own.id)
        val refused = svc.tickets.update(own.id, TicketPatch(mcp = docs), asSession("s-worker")).refusedText()
        assertTrue(refused.contains("a worker cannot set mcp on its own ticket"), refused)
        assertNull(board.get(own.id).mcp)
        svc.tickets.update(own.id, TicketPatch(title = "still editable"), asSession("s-worker")).ok()
        svc.tickets.update(own.id, TicketPatch(mcp = docs), asSession("s-pm")).ok()
        assertNotNull(board.get(own.id).mcp)
    }
}
