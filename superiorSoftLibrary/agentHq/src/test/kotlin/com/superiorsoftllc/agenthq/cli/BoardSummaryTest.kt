package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.session.BackgroundWork
import com.superiorsoftllc.agenthq.session.SessionActivityLog
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionRecordSerializer
import com.superiorsoftllc.agenthq.session.SessionState
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
            listOf(ticket("aaaaaaaa1", "todo"), ticket("bbbbbbbb2", "in_progress", "s1234567890", "ticket/b"), ticket("cccccccc3", "done")),
            listOf(session("s1234567890", bg = BackgroundWork(1, "gradlew test", 0))),
            now,
        ) { "abc1234" }
        assertEquals(listOf("todo (1)", "in_progress (1)", "done (1)"), lines.filter { !it.startsWith("  ") })
        val line = lines.single { it.contains("bbbbbbbb") }
        assertTrue("qa s1234567" in line && "running background process (gradlew test)" in line && "ticket/b@abc1234" in line, line)
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
}
