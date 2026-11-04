// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatNoteToolsTest {
    private val pushed = mutableListOf<JsonObject>()
    private val stored = mutableListOf<JsonObject>()
    private val woken = mutableListOf<Pair<String, String>>()
    private val tools = ChatNoteTools({ if (it == "other") "p2" else if (it.startsWith("s")) "p1" else null }, { pushed += it }, { stored += it }, { i, t -> woken += i to t }, maxChars = 10, perMinute = 3)

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "s1"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `a note is pushed and stored without waking`() {
        val r = tools.handle(call("""{"text":"build ok 12345678901234","level":"ok","sessionId":"s2"}"""))
        assertTrue(!r.isError, r.text)
        assertEquals(1, pushed.size); assertEquals(1, stored.size); assertTrue(woken.isEmpty())
        assertTrue(pushed[0]["text"].toString().contains("build ok 1\""))
    }

    @Test fun `wake prompts the agent, another project and bad level are refused`() {
        tools.handle(call("""{"text":"x","wake":true}"""))
        assertEquals(listOf("s1" to "x"), woken)
        assertTrue(tools.handle(call("""{"text":"x","sessionId":"other"}""")).isError)
        assertTrue(tools.handle(call("""{"text":"x","level":"loud"}""")).isError)
    }

    @Test fun `wake on a busy session succeeds, other wake failures and unknown sessions still fail`() {
        val busy = ChatNoteTools({ "p1" }, { pushed += it }, { stored += it }, { _, _ -> throw IllegalStateException("session s1 already has a run in flight") })
        val r = busy.handle(call("""{"text":"x","wake":true}"""))
        assertTrue(!r.isError, r.text); assertTrue(r.text.contains("busy"), r.text); assertEquals(1, stored.size)
        val broken = ChatNoteTools({ "p1" }, { pushed += it }, { stored += it }, { _, _ -> throw IllegalStateException("boom") })
        assertTrue(broken.handle(call("""{"text":"x","wake":true}""")).isError)
        assertTrue(tools.handle(call("""{"text":"x","sessionId":"zzz","wake":true}""")).isError)
    }

    @Test fun `a warn note is accepted, stored and read back with level warn, as a warning not a failure`() {
        val log = ScriptRunLog()
        val t = ChatNoteTools({ "p1" }, { pushed += it }, log::addNote, { _, _ -> })
        val r = t.handle(call("""{"text":"memory low: 3750 MB free","level":"warn"}"""))
        assertTrue(!r.isError, r.text); assertTrue(r.text.contains("warn"), r.text)
        val back = log.timelineItems().single()
        assertEquals("warn", back["meta"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals(true, back["ok"]!!.jsonPrimitive.boolean)
        val text = back["text"]!!.jsonPrimitive.content
        assertTrue(text.endsWith(" · WARNING"), text); assertTrue("FAILED" !in text, text)
        val fail = tools.handle(call("""{"text":"x","level":"fail"}""")); assertTrue(!fail.isError)
        assertEquals(false, stored.last()["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun `rate limit`() {
        repeat(3) { tools.handle(call("""{"text":"x"}""")) }
        assertTrue(tools.handle(call("""{"text":"x"}""")).isError)
    }
}
