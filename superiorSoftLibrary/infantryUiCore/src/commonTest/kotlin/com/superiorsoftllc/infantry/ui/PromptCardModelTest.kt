// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.PromptCards
import com.superiorsoftllc.infantry.ui.model.ToolApprovalSetting
import com.superiorsoftllc.infantry.ui.model.needsInput
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.PromptOptionView
import com.superiorsoftllc.infantry.ui.protocol.PromptRequestView
import com.superiorsoftllc.infantry.ui.protocol.PromptResponseWire
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROMPT-2 (contract 1.77.0): the prompt card's frames, answers and labels, without a window. */
class PromptCardModelTest {
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun approvalFrame(id: String, session: String = "s1", tool: String = "Bash", input: String = "git status") =
        Envelope(Envelope.PROMPT_REQUEST, """{"promptId":"$id","sessionId":"$session","kind":"tool_approval","createdAt":1,"tool":"$tool","input":"$input"}""", session)

    private fun resolved(id: String, by: String = "client:2") =
        Envelope(Envelope.PROMPT_RESOLVED, """{"promptId":"$id","resolvedBy":"$by","outcome":"deny"}""")

    private val question = PromptRequestView(
        "q1", "s1", PromptCards.KIND_QUESTION, question = "Which db?",
        options = listOf(PromptOptionView("Postgres", "relational"), PromptOptionView("Mongo", "documents"), PromptOptionView("Sqlite")),
    )

    @Test fun `a prompt_request opens a card for its session and a resend replaces it in place`() {
        val s = feed(approvalFrame("p1"), approvalFrame("p2"), approvalFrame("p1", input = "git log"))
        assertEquals(listOf("p1", "p2"), s.promptsFor("s1").map { it.promptId })
        assertEquals("git log", s.promptsFor("s1").first().input)
        assertTrue(s.promptsFor("other").isEmpty())
    }

    @Test fun `prompt_resolved closes the card whoever answered, and ready clears every card`() {
        val open = feed(approvalFrame("p1"), approvalFrame("p2"))
        assertEquals(listOf("p2"), feed(resolved("p1", by = "client:9"), from = open).promptsFor("s1").map { it.promptId })
        assertEquals(listOf("p2"), feed(resolved("p1", by = "timeout"), from = open).promptsFor("s1").map { it.promptId })
        assertTrue(feed(Envelope(Envelope.READY, "contract=1.78.0 instance=x"), from = open).openPrompts.isEmpty())
    }

    @Test fun `a prompt of an unselected session is a toast that opens it, gone when the prompt resolves`() {
        val s = feed(approvalFrame("p1"))
        val toast = s.toasts.single()
        assertEquals("s1", toast.sessionId)
        assertTrue(toast.body.orEmpty().let { "Bash" in it && "git status" in it })
        assertTrue(feed(resolved("p1"), from = s).toasts.isEmpty())
        assertTrue(feed(approvalFrame("p2"), from = AppState(selectedId = "s1")).toasts.isEmpty(), "the selected session shows its card")
    }

    @Test fun `a session with an open prompt needs you`() {
        val agent = AgentView(session = SessionRecord(id = "s1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"))
        val s = feed(approvalFrame("p1"))
        assertTrue(needsInput(agent, emptyList(), s.openPrompts))
        assertTrue(s.hasOpenPrompt("s1"))
        assertFalse(needsInput(agent, emptyList(), feed(resolved("p1"), from = s).openPrompts))
    }

    @Test fun `a question answer carries the chosen labels in option order and the Other text`() {
        val multi = question.copy(multiSelect = true, allowOther = true)
        assertEquals(
            PromptResponseWire("q1", answers = listOf("Postgres", "Sqlite"), otherText = "Redis"),
            PromptCards.answer(multi, setOf("Sqlite", "Postgres"), "  Redis "),
        )
        assertEquals(listOf("Mongo"), PromptCards.answer(question, setOf("Mongo"), "ignored")!!.answers)
        assertEquals("ignored", PromptCards.answer(question, setOf("Mongo"), "ignored")!!.otherText, "Other is always allowed")
        assertNull(PromptCards.answer(question, emptySet(), ""), "the daemon refuses an empty answer, so nothing is sent")
        assertEquals(setOf("Mongo"), PromptCards.toggle(question, setOf("Postgres"), "Mongo"), "radio replaces")
        assertEquals(setOf("Postgres", "Mongo"), PromptCards.toggle(multi, setOf("Postgres"), "Mongo"), "checkbox adds")
    }

    @Test fun `Esc denies a tool approval and sends nothing for a question`() {
        val approval = PromptRequestView("p1", "s1", PromptCards.KIND_TOOL_APPROVAL, tool = "Bash")
        assertEquals(PromptResponseWire("p1", decision = "deny"), PromptCards.escape(approval))
        assertNull(PromptCards.escape(question))
    }

    @Test fun `Always allow is offered only by a daemon that takes it, and names the shell prefix`() {
        val p = PromptRequestView("p1", "s1", PromptCards.KIND_TOOL_APPROVAL, tool = "Bash", input = "git push origin main")
        assertEquals(listOf("allow_once", "allow_session", "deny"), PromptCards.approvalChoices(p, allowAlways = PromptCards.offersAllowAlways("1.77.0")).map { it.decision })
        assertEquals(
            listOf("allow_once", "allow_session", "allow_always", "deny"),
            PromptCards.approvalChoices(p, allowAlways = PromptCards.offersAllowAlways("1.78.0")).map { it.decision },
        )
        assertEquals("Always allow `git` commands", PromptCards.alwaysAllowLabel("Bash", "git push origin main"))
        assertEquals("Always allow `git` commands", PromptCards.alwaysAllowLabel("PowerShell", "\"C:\\Program Files\\Git\\bin\\git.exe\" status"))
        assertEquals("Always allow Edit", PromptCards.alwaysAllowLabel("Edit", "src/A.kt"))
    }

    @Test fun `queue position, the response frame and the ask label`() {
        assertNull(PromptCards.position(1))
        assertEquals("1 of 2", PromptCards.position(2))
        val env = WireRequests.promptResponse(PromptResponseWire("p1", decision = "allow_once"))
        assertEquals(Envelope.PROMPT_RESPONSE, env.type)
        assertEquals("""{"promptId":"p1","decision":"allow_once"}""", env.data)
        assertEquals("Ask me each time", ToolApprovalSetting.label("ask"))
    }
}
