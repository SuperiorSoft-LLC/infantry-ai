// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEMO-MODEL-1: a session whose stored history the provider refuses (400 invalid_request) is moved to a fresh one, once per turn. */
class OpenCodePoisonedSessionTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val refused400 = obj("""{"name":"APIError","data":{"message":"Bad Request","statusCode":400,"isRetryable":false,"responseBody":"{\"error\":{\"type\":\"invalid_request\",\"message\":\"unexpected token <|constrain|>\"}}"}}""")

    @Test fun `a 400 invalid_request is a poisoned history`() = assertTrue(OpenCodeServeSession.isPoisonedHistory(refused400))

    @Test fun `a 400 named only in the message text counts too`() =
        assertTrue(OpenCodeServeSession.isPoisonedHistory(obj("""{"name":"UnknownError","data":{"message":"HTTP 400 invalid_request_error"}}""")))

    @Test fun `quota, 5xx and other 400s are not`() {
        assertFalse(OpenCodeServeSession.isPoisonedHistory(obj("""{"name":"APIError","data":{"message":"rate limited","statusCode":429}}""")))
        assertFalse(OpenCodeServeSession.isPoisonedHistory(obj("""{"name":"APIError","data":{"message":"invalid_request upstream","statusCode":500}}""")))
        assertFalse(OpenCodeServeSession.isPoisonedHistory(obj("""{"name":"APIError","data":{"message":"context too long","statusCode":400}}""")))
        assertFalse(OpenCodeServeSession.isPoisonedHistory(null))
    }

    @Test fun `the first user message is read back from the session history, synthetic parts skipped`() {
        val messages = """[
            {"info":{"id":"m1","role":"user"},"parts":[{"type":"text","text":"PERSONA"},{"type":"text","text":"file dump","synthetic":true},{"type":"text","text":"hello"}]},
            {"info":{"id":"m2","role":"assistant"},"parts":[{"type":"text","text":"<|start|>oops"}]},
            {"info":{"id":"m3","role":"user"},"parts":[{"type":"text","text":"later"}]}
        ]"""
        assertEquals("PERSONA\nhello", OpenCodeServeSession.firstUserText(messages))
        assertNull(OpenCodeServeSession.firstUserText("[]"))
    }

    @Test fun `the recovery prompt carries the persona ahead of the turn, or only the turn when there is none`() {
        val p = OpenCodeServeSession.recoveryPrompt("PERSONA", "now")
        assertTrue(p.startsWith("PERSONA") && p.endsWith("now") && OpenCodeServeSession.RECOVERY_NOTE in p, p)
        assertEquals("now", OpenCodeServeSession.recoveryPrompt(null, "now"))
        assertEquals("now", OpenCodeServeSession.recoveryPrompt("now", "now"))
    }

    private class Out : SessionOutput {
        var end: SessionTurnEnd? = null
        var reason: String? = null
        val lines = mutableListOf<String>()
        override fun write(text: String) = true
        override fun closeInput() {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun output(line: String) { lines += line }
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { this.end = end; this.reason = reason }
    }

    private fun feed(turn: OpenCodeServeSession.Turn, ses: String, vararg events: String) = events.forEach { e ->
        val o = obj(e)
        val props = o["properties"] as JsonObject
        turn.onEvent((o["type"] as JsonPrimitive).content, props, props["part"] as? JsonObject, ses)
    }

    private fun busy(s: String) = """{"type":"session.status","properties":{"sessionID":"$s","status":{"type":"busy"}}}"""
    private fun idle(s: String) = """{"type":"session.idle","properties":{"sessionID":"$s"}}"""
    private fun error(s: String) = """{"type":"session.error","properties":{"sessionID":"$s","error":$refused400}}"""
    private fun text(s: String) = """{"type":"message.part.updated","properties":{"sessionID":"$s","part":{"id":"p1","messageID":"a1","type":"text","text":"hi","time":{"start":1,"end":2}}}}"""

    @Test fun `the turn waits out the old session and completes on the fresh one`() {
        val out = Out()
        val reasons = mutableListOf<String>()
        lateinit var turn: OpenCodeServeSession.Turn
        turn = OpenCodeServeSession.Turn(out, "m/x", recover = { reasons += it; true })
        feed(turn, "ses_old", busy("ses_old"), error("ses_old"), idle("ses_old"))
        assertNull(out.end, "the old session's idle does not end the recovering turn")
        assertEquals(1, reasons.size)
        assertTrue(out.lines.none { "\"error\"" in it }, "no error line for a recovered refusal")
        turn.recovered()
        feed(turn, "ses_new", busy("ses_new"), text("ses_new"), idle("ses_new"))
        assertEquals(SessionTurnEnd.COMPLETED, out.end)
    }

    @Test fun `HARNESS-ALL-1 a failed turn's reason is opencode's own error text`() {
        val out = Out()
        val turn = OpenCodeServeSession.Turn(out, "google/gemini", recover = null)
        val missing = """{"type":"session.error","properties":{"sessionID":"s","error":{"name":"AI_LoadAPIKeyError","data":{"message":"Google Generative AI API key is missing."}}}}"""
        feed(turn, "s", busy("s"), missing, idle("s"))
        assertEquals(SessionTurnEnd.FAILED, out.end)
        assertEquals("Google Generative AI API key is missing.", out.reason, "not only 'opencode reported an error'")
    }

    @Test fun `a second refusal in the same turn fails it (no loop)`() {
        val out = Out()
        var calls = 0
        lateinit var turn: OpenCodeServeSession.Turn
        turn = OpenCodeServeSession.Turn(out, "m/x", recover = { calls++; true })
        feed(turn, "ses_old", busy("ses_old"), error("ses_old"), idle("ses_old"))
        turn.recovered()
        feed(turn, "ses_new", busy("ses_new"), error("ses_new"), idle("ses_new"))
        assertEquals(1, calls)
        assertEquals(SessionTurnEnd.FAILED, out.end)
    }

    @Test fun `without a recovery the refusal fails the turn as before`() {
        val out = Out()
        val turn = OpenCodeServeSession.Turn(out, "m/x")
        feed(turn, "s", busy("s"), error("s"), idle("s"))
        assertEquals(SessionTurnEnd.FAILED, out.end)
    }
}
