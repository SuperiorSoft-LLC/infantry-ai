// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ASK-STUCK-1 (ticket c2d09882): Claude's AskUserQuestion is answered through Infantry AI's prompt card in every Tool approval mode, never by
 * the CLI's own terminal menu (which waited on keys nobody sends: the owner's "stuck on AskUserQuestion").
 */
class ClaudeQuestionHookTest {
    private val root: File = Files.createTempDirectory("claude-question-test").toFile()

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private val call = """{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_input":{"questions":[
        {"question":"Which color?","header":"Color","multiSelect":false,"options":[{"label":"Red","description":"warm"},{"label":"Green","description":""},{"label":"Blue (Recommended)","description":"calm"}]},
        {"question":"Which sizes?","header":"Size","multiSelect":true,"options":[{"label":"S"},{"label":"M"}]}
    ]}}"""

    private fun output(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject["hookSpecificOutput"]!!.jsonObject

    @Test fun `each question becomes a card and the answers go back as updatedInput answers`() {
        val asked = mutableListOf<HarnessQuestion>()
        val reply = ClaudeQuestionHook.decide(call) { q ->
            asked += q
            if (q.question.contains("color")) HarnessAnswer(listOf("Green"), null, true) else HarnessAnswer(emptyList(), "S and M", true)
        }
        val out = output(reply)
        assertEquals("PreToolUse", out["hookEventName"]!!.jsonPrimitive.content)
        assertEquals("allow", out["permissionDecision"]!!.jsonPrimitive.content)
        val input = out["updatedInput"]!!.jsonObject
        assertEquals(2, input["questions"]!!.jsonArray.size, "the questions are kept")
        val answers = input["answers"]!!.jsonObject
        assertEquals("Green", answers["Which color?"]!!.jsonPrimitive.content)
        assertEquals("S and M", answers["Which sizes?"]!!.jsonPrimitive.content, "the owner's own text is an answer")
        assertEquals(listOf("Red", "Green", "Blue (Recommended)"), asked[0].options)
        assertEquals("Blue (Recommended)", asked[0].recommended, "the one marked label is the timer's pick")
        assertTrue(asked[0].question.startsWith("[Color] Which color?") && "- Red: warm" in asked[0].question && "- Blue (Recommended): calm" in asked[0].question, asked[0].question)
        assertNull(asked[1].recommended, "no mark, no pick")
        // ASK-OTHER-1: the card itself is multi-select (no "type them" hint) and every card takes the owner's own text.
        assertTrue(asked[1].multiSelect && !asked[0].multiSelect, "the tool's multiSelect reaches the card")
        assertTrue(asked.all { it.allowOther }, "every card has an Other field")
        assertTrue("Several allowed" !in asked[1].question, "no typing hint any more")
    }

    @Test fun `an unanswered question denies the call with a reason instead of leaving the menu waiting`() {
        val out = output(ClaudeQuestionHook.decide(call) { HarnessAnswer.NONE })
        assertEquals("deny", out["permissionDecision"]!!.jsonPrimitive.content)
        assertEquals(ClaudeQuestionHook.NO_ANSWER, out["permissionDecisionReason"]!!.jsonPrimitive.content)
        assertEquals("deny", output(ClaudeQuestionHook.decide(call) { error("host down") })["permissionDecision"]!!.jsonPrimitive.content, "a failing ask is no answer")
        assertEquals("deny", output(ClaudeQuestionHook.decide("not json") { HarnessAnswer(listOf("x"), null, true) })["permissionDecision"]!!.jsonPrimitive.content)
    }

    @Test fun `several picked labels are joined the way AskUserQuestion takes them`() {
        assertEquals("S, M, also XL", ClaudeQuestionHook.answerText(HarnessAnswer(listOf("S", "M"), "also XL", true)))
        assertNull(ClaudeQuestionHook.answerText(HarnessAnswer(listOf("S"), null, false)))
        assertNull(ClaudeQuestionHook.answerText(HarnessAnswer(emptyList(), "  ", true)))
    }

    private class Quiet : SessionOutput {
        val details = mutableListOf<String>()
        override fun write(text: String): Boolean = true
        override fun closeInput() {}
        override fun detail(text: String) { details += text }
        override fun providerSession(id: String) {}
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) {}
    }

    private fun post(url: String, body: String): String {
        val http = java.net.http.HttpClient.newHttpClient()
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body()
    }

    @Test fun `every approval mode, allowAll included, carries the hook and its answer comes from the session's own card`() {
        for (approval in ToolApproval.entries) {
            val asked = mutableListOf<Pair<String, String>>()
            val state = File(root, approval.name)
            val cwd = File(root, "work-${approval.name}").apply { mkdirs() }
            val session = ClaudeTtySession(
                executable = { "claude.exe" }, mcp = { null to emptyMap() }, stateDir = { state }, configDir = { File(root, "config") },
                newSessionId = { "11111111-2222-3333-4444-555555555555" }, poll = false,
                ask = { sid, q -> asked += sid to q.question; HarnessAnswer(listOf("Green"), null, true) },
            )
            val request = HarnessRequest(prompt = "", harnessId = "claude", project = cwd.absolutePath, model = "haiku", toolApproval = approval)
            val launched = session.launch(request, mapOf(ClaudeCommand.SESSION_ID_ENV to "infantry-session-1"))
            val settings = Json.parseToJsonElement(File(File(state, ClaudeTtySession.TTY_DIR), "11111111-2222-3333-4444-555555555555.settings.json").readText()).jsonObject
            val entry = settings["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray.map { it.jsonObject }
                .single { it["matcher"]?.jsonPrimitive?.content == ClaudeQuestionHook.TOOL }
            val url = entry["hooks"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(ClaudeQuestionHook.PATH in url, url)
            val c = session.connect(Quiet(), launched) as ClaudeTtySession.TtyConnection
            val out = output(post(url, call))
            assertEquals("allow", out["permissionDecision"]!!.jsonPrimitive.content, "$approval")
            assertEquals("Green", out["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["Which color?"]!!.jsonPrimitive.content)
            assertEquals(listOf("infantry-session-1", "infantry-session-1"), asked.map { it.first }, "asked for the Infantry session, once per question")
            c.onExit(0)
            assertEquals("deny", output(post(url, call))["permissionDecision"]!!.jsonPrimitive.content, "an exited session no longer answers")
        }
    }
}
