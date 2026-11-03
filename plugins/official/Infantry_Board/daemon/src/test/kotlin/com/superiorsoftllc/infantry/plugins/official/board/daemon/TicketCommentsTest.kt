// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.plugins.official.board.daemon.TicketComments.Author
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TICKET-COMMENTS-2: the comment store, createdBy / notify and the notify mail, over a real SQLite file. */
class TicketCommentsTest {
    private class JdbcStorage(private val c: Connection) : PluginStorage {
        override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                generateSequence { if (rs.next()) cols.associateWith { c -> rs.getObject(c) } else null }.toList()
            }
        }
        override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val mails = mutableListOf<Pair<String, String>>()
    private val settings = mutableMapOf<String, String>()
    private var tick = 0L
    private val published = mutableListOf<TicketComments.Comment>()

    /** Registry agents: role:pm, role:vc, role:qa, role:backend. Live: pm-1, vc-1, backend-1 (no qa session). */
    private val live = mapOf("role:pm" to listOf("pm-1"), "role:vc" to listOf("vc-1"), "role:backend" to listOf("backend-1"))
    private val comments = TicketComments(
        db = JdbcStorage(conn),
        isRegistryAgent = { it in setOf("role:pm", "role:vc", "role:qa", "role:backend") },
        isSession = { it.startsWith("worker-") },
        liveSessions = { agent, _ -> live[agent].orEmpty() },
        notifier = object : SessionNotifier {
            override fun notifySession(sessionId: String, subject: String, body: String) = mails.add(sessionId to body)
            override fun notifyRole(projectId: String, role: String, subject: String, body: String) = 0
        },
        setting = { settings[it] },
        clock = { ++tick },
        onAdded = { published += it },
    ).also { it.migrate() }

    private val worker = Author(Author.SESSION, "backend-1", "role:backend")
    private val ticket = "ticket-3430e823"

    @AfterTest fun close() = conn.close()

    private fun created(explicit: List<String>? = null) = comments.onCreated(ticket, "p1", worker, explicit)

    @Test fun addAndReadEveryWhichAndKind() {
        created()
        val a = comments.add(ticket, "p1", "T", worker, "note", "open", "first")
        val b = comments.add(ticket, "p1", "T", worker, "issue", "open", "second")
        val c = comments.add(ticket, "p1", "T", worker, "done", "resolved", "third")
        assertEquals(listOf(b.id), comments.read(ticket, "latestOpen", null, null).map { it.id })
        assertEquals(listOf(c.id), comments.read(ticket, "latest", null, null).map { it.id })
        assertEquals(listOf(a.id), comments.read(ticket, "first", null, null).map { it.id })
        assertEquals(listOf(a.id, b.id, c.id), comments.read(ticket, "all", null, null).map { it.id })
        assertEquals(listOf(b.id, c.id), comments.read(ticket, "all", null, 2).map { it.id })
        assertEquals(listOf(b.id), comments.read(ticket, "all", "issue", null).map { it.id })
        assertTrue(comments.read("other", "latestOpen", null, null).isEmpty())
        assertFailsWith<IllegalArgumentException> { comments.read(ticket, "newest", null, null) }
    }

    @Test fun addPublishesTicketCommentedWithItsIdsAndFirstLine() {
        created()
        val c = comments.add(ticket, "p1", "T", worker, "note", "open", "first line\nsecond line")
        assertEquals(listOf(c.id), published.map { it.id })
        assertEquals(ticket, published.single().ticketId)
        assertEquals("first line", published.single().body.lineSequence().first())
    }

    @Test fun resolveClosesTheCommentAndSendsNoMail() {
        created(listOf("role:pm"))
        val c = comments.add(ticket, "p1", "T", Author.owner, "issue", "open", "fix it")
        mails.clear()
        val done = comments.resolve(c.shortId, Author.owner)!!
        assertEquals("resolved", done.state)
        assertEquals("owner", done.resolvedBy)
        assertEquals(0, comments.summary(ticket).open)
        assertTrue(mails.isEmpty())
        assertNull(comments.resolve("zzzzzzzz", Author.owner))
    }

    @Test fun notifyIsCreatorPlusProjectDefaultList() {
        settings["board.notify.p1"] = "role:vc, role:qa, role:gone"
        created()
        assertEquals(listOf("role:backend", "role:vc", "role:qa"), comments.meta(ticket)!!.notify)
        assertEquals("role:backend", comments.meta(ticket)!!.createdBy)
        // no default list: the creator only; createdBy is never edited
        comments.onCreated("t2", "p-none", worker, null)
        assertEquals(listOf("role:backend"), comments.meta("t2")!!.notify)
        comments.onCreated("t2", "p-none", Author.owner, null)
        assertEquals("role:backend", comments.meta("t2")!!.createdBy)
    }

    @Test fun invokedSessionsAreRefusedAsNotifyTargets() {
        val e = assertFailsWith<IllegalArgumentException> { comments.validateTargets(listOf("worker-12")) }
        assertTrue("not invoked ticket workers" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { comments.validateTargets(listOf("nobody")) }
        assertEquals(listOf("role:pm"), comments.validateTargets(listOf("pm")))
        created()
        assertFailsWith<IllegalArgumentException> { comments.editNotify(ticket, null, listOf("worker-1"), emptyList()) }
        assertEquals(listOf("role:backend", "role:pm"), comments.editNotify(ticket, null, listOf("pm"), emptyList()))
        assertEquals(listOf("role:pm"), comments.editNotify(ticket, null, emptyList(), listOf("backend")))
        assertEquals(listOf("role:qa"), comments.editNotify(ticket, listOf("qa"), emptyList(), emptyList()))
    }

    @Test fun oneMailPerCommentPerNotifiedAgentAndNotTheAuthor() {
        settings["board.notify.p1"] = "role:pm,role:vc"
        created()
        val c = comments.add(ticket, "p1", "My title", worker, "done", "open", "merged at abc123\nsecond line")
        // the author (backend-1) is notified-by-creator but skipped; pm and vc get exactly one line each
        assertEquals(listOf("pm-1", "vc-1"), mails.map { it.first })
        val line = mails.first().second
        assertEquals(
            "[TICKET COMMENT] 3430e823 My title: done/open comment ${c.shortId} by role:backend (ackend-1): merged at abc123 second line. Read: infantry_ticket_comments 3430e823",
            line,
        )
        assertTrue(comments.summary(ticket).undelivered.isEmpty())
    }

    @Test fun longBodyIsCutAt200() {
        created(listOf("role:pm"))
        comments.add(ticket, "p1", "T", Author.owner, "note", "open", "x".repeat(500))
        assertTrue(mails.single().second.contains("x".repeat(200) + ". Read:"))
        assertTrue(!mails.single().second.contains("x".repeat(201)))
    }

    @Test fun anAgentWithNoLiveSessionIsRecordedUndelivered() {
        created(listOf("role:qa", "role:pm"))
        val c = comments.add(ticket, "p1", "T", Author.owner, "note", "open", "hello")
        assertEquals(listOf("pm-1"), mails.map { it.first })
        assertEquals(listOf("role:qa"), comments.get(c.id)!!.undelivered)
        assertEquals(listOf("role:qa"), comments.summary(ticket).undelivered)
        assertEquals(1, comments.summary(ticket).open)
        assertEquals(c.id, comments.summary(ticket).latestOpen!!.id)
    }
}
