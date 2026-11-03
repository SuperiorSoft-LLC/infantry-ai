package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.StatusPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-ACT-1: a session keeps an ACTIVITY HISTORY -- one entry per tool call (command, search, file
 * read) in arrival order, capped at the last 200. Model only; the UI box comes in UI-ACT-2. The
 * existing `activity` field stays the LAST entry of that history, so existing callers do not break.
 */
class ActivityHistoryModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, heartbeat: Long = 1) =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = heartbeat, state = SessionState.BUSY, providerSessionId = "p")

    private fun session(r: SessionRecord, seq: Long?) =
        Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), r), r.id, seq)

    private fun ready() = Envelope(Envelope.READY, "provider=stub contract=1.11.0 instance=A since=0")

    private fun tool(id: String, seq: Long, name: String, detail: String? = null) = Envelope(
        Envelope.STATUS,
        json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, tool = name, activity = detail)),
        id,
        seq,
    )

    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun AppState.labels(id: String) = agents.getValue(id).activityLog.map { it.label }

    @Test fun threeToolFramesGiveThreeEntriesInOrder() {
        // Every tool frame appends one entry: what it ran, in the order it ran.
        val s = feed(
            ready(), session(rec("a"), 1),
            tool("a", 2, "bash", "gradle test"),
            tool("a", 3, "grep", "TicketBoard"),
            tool("a", 4, "read", "Model.kt"),
        )
        assertEquals(listOf("bash: gradle test", "grep: TicketBoard", "read: Model.kt"), s.labels("a"))
    }

    @Test fun theLatestStillEqualsTheLastEntry() {
        // `activity` stays the LAST entry of the history, so every existing caller still works.
        val s = feed(
            ready(), session(rec("a"), 1),
            tool("a", 2, "bash", "gradle test"),
            tool("a", 3, "read", "Model.kt"),
        )
        val a = s.agents.getValue("a")
        assertEquals(a.activityLog.last(), a.activity)
        assertEquals("read: Model.kt", a.activity?.label)
    }

    @Test fun theSessionsOwnPlumbingIsHistoryButNotTheRunningTool() {
        // Contract 1.64.0 (BUG-M2-11b-1): `starting …` / `process started …` ride `status` with tool = the
        // provider id. They land in the history, but the status row must keep reading "Thinking..." until a
        // real tool runs, so `activity` stays null for them.
        val booted = feed(
            ready(), session(rec("a"), 1),
            tool("a", 2, "stub", "starting stub model default in /w"),
            tool("a", 3, "stub", "process started (no OS process)"),
        )
        assertEquals(listOf("stub: starting stub model default in /w", "stub: process started (no OS process)"), booted.labels("a"))
        assertEquals(null, booted.agents.getValue("a").activity)

        val s = feed(tool("a", 4, "bash", "gradle test"), from = booted)
        assertEquals("bash: gradle test", s.agents.getValue("a").activity?.label)
    }

    @Test fun twoSessionsDoNotMix() {
        // The history is per session: interleaved tool frames land only in their own session's log.
        val s = feed(
            ready(), session(rec("a"), 1), session(rec("b"), 1),
            tool("a", 2, "bash", "gradle test"),
            tool("b", 2, "grep", "TicketBoard"),
            tool("a", 3, "read", "Model.kt"),
        )
        assertEquals(listOf("bash: gradle test", "read: Model.kt"), s.labels("a"))
        assertEquals(listOf("grep: TicketBoard"), s.labels("b"))
    }

    @Test fun theHistoryIsCappedAtTheLast200() {
        // 205 tool frames keep the LAST 200 entries: the oldest 5 are dropped.
        var s = feed(ready(), session(rec("a"), 1))
        for (i in 1..205) s = reduce(s, tool("a", (i + 1).toLong(), "bash", "frame $i"), 100)
        val log = s.agents.getValue("a").activityLog
        assertEquals(200, log.size)
        assertEquals("bash: frame 6", log.first().label)
        assertEquals("bash: frame 205", log.last().label)
    }
}
