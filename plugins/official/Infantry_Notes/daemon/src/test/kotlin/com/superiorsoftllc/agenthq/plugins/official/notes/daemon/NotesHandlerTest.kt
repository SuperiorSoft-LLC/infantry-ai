package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.decisions.Decision
import com.superiorsoftllc.agenthq.issues.Issue
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-7c: the decision_* and issue_* frames answered by the handlers (the same types and refusal texts core spoke). */
class NotesHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val path = Files.createTempFile("agenthq-notes-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val connections = mutableListOf<java.sql.Connection>()
    private val pushed = mutableListOf<Frame>()

    private fun storage() = NotesTestDb.open(path).also { connections += it.first }.second

    private val sessions = mapOf("s1" to "agent-1")

    private fun decisions() = DecisionHandler(
        DecisionStore(storage(), clock = { 1L }),
        author = { id -> sessions[id]?.let { DecisionAuthor(id, it, "backend") } },
        push = { pushed += it },
    )

    private fun issues() = IssueHandler(
        IssueStore(storage(), clock = { 1L }),
        actor = { id -> sessions[id]?.let { IssueActor(id, it) } },
        push = { pushed += it },
    )


    @AfterTest
    fun cleanup() {
        connections.forEach { runCatching { it.close() } }
        listOf("", "-wal", "-shm").forEach { java.io.File(path + it).delete() }
    }

    @Test
    fun aDecisionAddIsPushedAndListedAndRead() {
        val h = decisions()
        assertNull(h.answer(Frame(Envelope.DECISION_ADD, """{"projectId":"P","summary":"use sqlite","body":"embedded"}""")), "the push is the answer")
        val first = json.decodeFromString(Decision.serializer(), pushed.single().data)
        assertEquals(Envelope.DECISION_ITEM to "D1", pushed.single().type to first.ref)
        h.answer(Frame(Envelope.DECISION_ADD, """{"projectId":"P","summary":"use postgres","supersedes":"D1"}"""))
        assertEquals(1, json.decodeFromString(Decision.serializer(), pushed.last().data).supersedes)

        val list = h.answer(Frame(Envelope.DECISION_LIST, """{"projectId":"P"}"""))!!
        assertEquals(Envelope.DECISIONS, list.type)
        assertEquals(listOf("D2"), json.decodeFromString(ListSerializer(Decision.serializer()), list.data).map { it.ref })
        val get = h.answer(Frame(Envelope.DECISION_GET, """{"projectId":"P","ref":"1"}"""))!!
        assertEquals("embedded", json.decodeFromString(Decision.serializer(), get.data).body)
    }

    @Test
    fun decisionRefusalsKeepTheirText() {
        val h = decisions()
        val missing = h.answer(Frame(Envelope.DECISION_GET, """{"projectId":"P","ref":"D9"}"""))!!
        assertEquals(Envelope.ERROR, missing.type)
        assertTrue(missing.data.startsWith("decision_get refused: "), missing.data)
        val unknown = h.answer(Frame(Envelope.DECISION_ADD, """{"projectId":"P","summary":"a","sessionId":"nope"}"""))!!
        assertTrue(unknown.data.contains("session_not_found"), unknown.data)
        assertNull(h.answer(Frame("something_else")), "not this handler's frame")
    }

    @Test
    fun anIssueIsOpenedAttemptedAndFixedThroughTheFrames() {
        val h = issues()
        assertNull(h.answer(Frame(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"It leaks","location":"a/b.kt","sessionId":"s1"}""")))
        assertEquals("I1", json.decodeFromString(Issue.serializer(), pushed.single().data).ref)
        h.answer(Frame(Envelope.ISSUE_ATTEMPT, """{"projectId":"P","summary":"tried gc","outcome":"failed","sessionId":"s1"}"""))
        h.answer(Frame(Envelope.ISSUE_FIX, """{"projectId":"P","summary":"closed it","sessionId":"s1"}"""))
        val fixed = json.decodeFromString(Issue.serializer(), pushed.last().data)
        assertEquals(Issue.STATUS_FIXED, fixed.status)
        assertEquals(listOf("attempt", "fix"), fixed.events.map { it.kind })
        val refused = h.answer(Frame(Envelope.ISSUE_ATTEMPT, """{"projectId":"P","issue":"I1","summary":"again","outcome":"failed"}"""))!!
        assertEquals(Envelope.ERROR, refused.type)
        assertTrue(refused.data.startsWith("issue_attempt refused: "), refused.data)
    }
}
