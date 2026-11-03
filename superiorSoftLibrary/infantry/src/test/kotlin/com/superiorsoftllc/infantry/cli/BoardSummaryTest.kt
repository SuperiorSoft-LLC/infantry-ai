package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.session.BackgroundWork
import com.superiorsoftllc.infantry.session.SessionActivityLog
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionRecordSerializer
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSummaryTest {
    private val now = 1_000_000L

    private fun ticket(id: String, status: String, sessionId: String? = null, branch: String? = null) = Ticket(
        id = id, title = "title $id", status = status, position = 0, createdAt = 0, updatedAt = 0, sessionId = sessionId, branch = branch,
    )

    private fun session(id: String, state: SessionState = SessionState.IDLE, bg: BackgroundWork? = null) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = state, role = "qa", backgroundWork = bg,
    )

    @Test
    fun emptyBoard() {
        assertEquals(listOf("board is empty: no tickets"), BoardSummary.render(emptyList(), emptyList(), now) { null })
    }

    @Test
    fun groupsByColumnWithWorkerStateAndCommit() {
        val lines = BoardSummary.render(
            listOf(ticket("ticket-aaaaaaa1", "todo"), ticket("ticket-bbbbbbb2", "in_progress", "s1234567890", "ticket/b"), ticket("ticket-ccccccc3", "done")),
            listOf(session("s1234567890", bg = BackgroundWork(1, "gradlew test", 0))),
            now,
        ) { "abc1234" }
        assertEquals(listOf("todo (1)", "in_progress (1)", "done (1)"), lines.filter { !it.startsWith("  ") })
        val line = lines.single { it.contains("bbbbbbb2") }
        assertTrue("qa s1234567" in line && "working (background: gradlew test)" in line && "ticket/b@abc1234" in line, line)
    }

    @Test
    fun sessionWithNoActivityYet() {
        val s = session("s1")
        assertEquals("idle (last output never, last event never)", BoardSummary.activity(s, now))
        val json = Json.encodeToJsonElement(SessionRecordSerializer, s).jsonObject
        assertFalse("lastAction" in json || "lastOutputAt" in json || "lastEventAt" in json)
    }

    @Test
    fun activityLogStampsServedRecord() {
        SessionActivityLog.clear()
        SessionActivityLog.output("s2", "Bash: ls", 900_000L)
        SessionActivityLog.event("s2", null, 950_000L)
        val json = Json.encodeToJsonElement(SessionRecordSerializer, session("s2")).jsonObject
        assertEquals("\"Bash: ls\"", json["lastAction"].toString())
        assertEquals("900000", json["lastOutputAt"].toString())
        assertEquals("950000", json["lastEventAt"].toString())
        SessionActivityLog.clear()
    }

    @Test
    fun idsSharingTheirFirstCharactersStayDistinguishable() {
        // ticket-91a3d988: the old line cut the id to 8 characters INCLUDING `ticket-`, so all three printed `ticket-1`.
        val ids = listOf("ticket-1a2b3c4d", "ticket-1a2b3c4e", "ticket-1f000000")
        val lines = BoardSummary.render(ids.map { ticket(it, "todo") }, emptyList(), now) { null }.filter { it.startsWith("  ") }
        assertEquals(listOf("1a2b3c4d", "1a2b3c4e", "1f000000"), lines.map { it.trim().substringBefore(" | ") })
        assertEquals("  1a2b3c4d | title ticket-1a2b3c4d", lines[0])
    }

    @Test
    fun aLongTitleIsCutWithAnEllipsisAndAShortOneIsNot() {
        assertEquals("short", BoardSummary.cutTitle("short"))
        assertEquals("x".repeat(100), BoardSummary.cutTitle("x".repeat(100)))
        val cut = BoardSummary.cutTitle("y".repeat(150))
        assertEquals(100, cut.length)
        assertTrue(cut.endsWith("...") && cut.startsWith("y".repeat(97)), cut)
        assertEquals("two lines", BoardSummary.cutTitle("two\nlines"))
        val long = Ticket(id = "ticket-abcdef12", title = "z".repeat(150), status = "todo", position = 0, createdAt = 0, updatedAt = 0)
        val line = BoardSummary.render(listOf(long), emptyList(), now) { null }.last()
        assertEquals("  abcdef12 | " + "z".repeat(97) + "...", line)
    }
}
