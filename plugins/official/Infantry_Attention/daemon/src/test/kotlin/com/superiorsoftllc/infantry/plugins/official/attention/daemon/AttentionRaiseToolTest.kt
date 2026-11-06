// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BRIDGE-EVENTS-1: infantry_attention_raise, the bridge a script (the shipped resume-after-restart) uses to reach the owner. */
class AttentionRaiseToolTest {
    private val raised = mutableListOf<List<String?>>()
    private val tool = AttentionRaiseTool { kind, ticketId, sessionId, outcome, detail ->
        if (kind == "bogus") throw IllegalArgumentException("unknown kind bogus")
        raised += listOf(kind, ticketId, sessionId, outcome, detail); "att-1"
    }

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = true)
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `raises with every field and returns the id`() {
        val r = tool.call(call("""{"kind":"session_resumed","outcome":"resumed_after_restart","detail":"1 session(s) had their turn cut","ticketId":"ticket-aaaa1111","sessionId":"s1"}"""))
        assertFalse(r.isError, r.text)
        assertEquals("""{"id":"att-1"}""", r.text)
        assertEquals(listOf("session_resumed", "ticket-aaaa1111", "s1", "resumed_after_restart", "1 session(s) had their turn cut"), raised.single())
    }

    @Test fun `missing fields and a refused kind are errors, a long detail is cut`() {
        assertTrue(tool.call(call("""{"outcome":"x","detail":"d"}""")).isError)
        assertTrue(tool.call(call("""{"kind":"k","detail":"d"}""")).isError)
        assertTrue(tool.call(call("""{"kind":"k","outcome":"x","detail":"  "}""")).isError)
        val refused = tool.call(call("""{"kind":"bogus","outcome":"x","detail":"d"}"""))
        assertTrue(refused.isError && "unknown kind bogus" in refused.text, refused.text)
        tool.call(call("""{"kind":"k","outcome":"x","detail":"${"a".repeat(3000)}"}"""))
        assertEquals(AttentionRaiseTool.DETAIL_MAX, raised.single()[4]!!.length)
        assertEquals(null, raised.single()[1])
    }
}
