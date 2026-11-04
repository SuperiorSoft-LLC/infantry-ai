// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.QueuedMessage
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

/** CHAT-QUEUE-1: `session_act` lists, adds, removes and forces the daemon's held messages, so scripts and agents see the one queue. */
class SessionToolsQueueTest {
    private val held = mutableListOf<QueuedMessage>()
    private val forced = mutableListOf<String>()
    private fun info(id: String, p: String) = SessionInfo(id, p, "backend", null, false, 0, "BUSY", false, null, 1)
    private val tools = SessionTools(
        { if (it == "other") info(it, "p2") else if (it.startsWith("s")) info(it, "p1") else null },
        { SessionStatus(it, "BUSY", null, 100L, 200L) },
        { _, _ -> emptyList() },
        { false },
        { _, _, _ -> },
        queued = { held.toList() },
        queueAdd = { _, text -> QueuedMessage("q-${held.size + 1}", text, 5L).also { held += it } },
        queueRemove = { _, id -> held.firstOrNull { it.id == id }?.also { held.remove(it) } ?: throw IllegalArgumentException("no queued message $id") },
        queueForce = { _, id ->
            if (id == "q-stuck") throw IllegalStateException("the harness cannot take a message mid-turn; it stays queued")
            forced += id; held.removeAll { it.id == id }; "injected"
        },
    )

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "s1"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `queue add, list, remove and force through session_act`() {
        assertTrue(!tools.act.call(call("""{"action":"queue_add","text":"first"}""")).isError)
        assertTrue(!tools.act.call(call("""{"action":"queue_add","text":"second"}""")).isError)
        val list = Json.parseToJsonElement(tools.act.call(call("""{"action":"queue_list"}""")).text) as JsonArray
        assertEquals(listOf("\"first\"", "\"second\""), list.map { it.jsonObject["text"].toString() })

        val removed = tools.act.call(call("""{"action":"queue_remove","id":"q-1"}"""))
        assertTrue(!removed.isError, removed.text)
        assertEquals(listOf("second"), held.map { it.text })

        val f = tools.act.call(call("""{"action":"queue_force","id":"q-2"}"""))
        assertTrue(!f.isError && f.text.contains("injected"), f.text)
        assertEquals(listOf("q-2"), forced)
        assertTrue(held.isEmpty())
    }

    @Test fun `refusals come back as tool errors with the reason`() {
        assertTrue(tools.act.call(call("""{"action":"queue_add"}""")).isError, "needs text")
        assertTrue(tools.act.call(call("""{"action":"queue_remove","id":"q-9"}""")).isError, "unknown id")
        val stuck = tools.act.call(call("""{"action":"queue_force","id":"q-stuck"}"""))
        assertTrue(stuck.isError && stuck.text.contains("mid-turn"), stuck.text)
        assertTrue(tools.act.call(call("""{"action":"queue_list","sessionId":"other"}""")).isError, "same project only")
    }
}
