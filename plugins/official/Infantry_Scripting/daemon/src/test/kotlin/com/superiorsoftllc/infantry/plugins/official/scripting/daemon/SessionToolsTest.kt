// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.SessionStatus
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionToolsTest {
    private val interrupted = mutableListOf<String>()
    private val suppressed = mutableListOf<Triple<String, String?, Long>>()
    private var state = "BUSY"
    private fun info(id: String, p: String) = SessionInfo(id, p, "backend", null, false, 0, state, false, null, 1)
    private val tools = SessionTools(
        { if (it == "other") info(it, "p2") else if (it.startsWith("s")) info(it, "p1") else null },
        { SessionStatus(it, state, "Bash: gradlew", 100L, 200L) },
        { _, n -> (1..500).map { "line $it" }.takeLast(n) },
        { interrupted += it; state == "BUSY" },
        { n, s, ms -> suppressed += Triple(n, s, ms) },
        maxLines = 50, maxChars = 8, perMinute = 6, noticeNames = { setOf("nudge.worker_idle") },
    )

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "s1"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `read returns state and capped lines`() {
        val r = tools.read.call(call("""{"sessionId":"s2","lines":500}"""))
        assertTrue(!r.isError, r.text)
        val o = Json.parseToJsonElement(r.text).jsonObject
        assertEquals("\"BUSY\"", o["state"].toString())
        assertEquals("\"Bash: gr\"", o["lastAction"].toString())
        assertEquals(50, (o["lines"] as JsonArray).size)
        assertEquals("\"line 500\"", (o["lines"] as JsonArray).last().toString())
    }

    @Test fun `read defaults to the caller and refuses another project`() {
        assertTrue(!tools.read.call(call("{}")).isError)
        assertTrue(tools.read.call(call("""{"sessionId":"other"}""")).isError)
        assertTrue(tools.read.call(call("""{"sessionId":"nope"}""")).isError)
    }

    @Test fun `interrupt and end_turn act on a busy session, end_turn on idle is a no-op`() {
        assertTrue(!tools.act.call(call("""{"action":"interrupt","sessionId":"s2"}""")).isError)
        assertEquals(listOf("s2"), interrupted)
        assertTrue(!tools.act.call(call("""{"action":"end_turn"}""")).isError)
        state = "IDLE"
        interrupted.clear()
        val r = tools.act.call(call("""{"action":"end_turn"}"""))
        assertTrue(!r.isError); assertTrue(interrupted.isEmpty())
        assertTrue(tools.act.call(call("""{"action":"interrupt"}""")).isError)
        assertTrue(tools.act.call(call("""{"action":"dance"}""")).isError)
    }

    @Test fun `suppress marks a known notice, with seconds clamped, and refuses unknown names and foreign sessions`() {
        assertTrue(!tools.suppressNotice.call(call("""{"name":"nudge.worker_idle","sessionId":"s2","seconds":99999}""")).isError)
        assertEquals(Triple("nudge.worker_idle", "s2", 3_600_000L), suppressed.single())
        assertTrue(tools.suppressNotice.call(call("""{"name":"bogus"}""")).isError)
        assertTrue(tools.suppressNotice.call(call("""{"name":"*","sessionId":"other"}""")).isError)
    }

    @Test fun `rate limit per project`() {
        repeat(6) { tools.read.call(call("{}")) }
        assertTrue(tools.read.call(call("{}")).isError)
    }
}
