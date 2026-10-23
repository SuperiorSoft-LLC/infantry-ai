package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.protocol.ApprovalRule
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptDecisions
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResolved
import com.superiorsoftllc.agenthq.protocol.PromptResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROMPT-6 (contract 1.78.0): "Always allow" -- remembered approval rules, per project, across sessions. */
class ApprovalRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val notices = CopyOnWriteArrayList<Pair<String, String>>()
    private val ids = AtomicInteger()

    /** Sessions s-a1, s-a2 are in project A; s-b1 is in project B; anything else has no project. */
    private val projects = mapOf("s-a1" to "proj-a", "s-a2" to "proj-a", "s-a3" to "proj-a", "s-b1" to "proj-b")

    /** The settings-table row both stores below share: a second [ApprovalRules] over it is a daemon restart. */
    private var row: String? = null
    private fun rules() = ApprovalRules({ row }, { row = it }, clock = { 5_000L }, newId = { "r" + ids.incrementAndGet() })

    private fun store(rules: ApprovalRules = rules()) = PendingPrompts(
        timeoutMillis = { 60_000 }, clock = { 1_000L }, newId = { "p" + ids.incrementAndGet() },
        rules = rules, projectOf = { projects[it] },
    ).also { p ->
        p.sink = { e -> frames += e }
        p.notice = { sid, text -> notices += sid to text }
    }

    private fun requests() = frames.filter { it.type == Envelope.PROMPT_REQUEST }.map { json.decodeFromString(PromptRequest.serializer(), it.data) }
    private fun resolved() = frames.filter { it.type == Envelope.PROMPT_RESOLVED }.map { json.decodeFromString(PromptResolved.serializer(), it.data) }

    private suspend fun waitOpen(prompts: PendingPrompts, count: Int = 1) = withTimeout(5_000) {
        while (prompts.snapshot().size < count) delay(5)
        prompts.snapshot()
    }

    /** Ask, answer the card with [decision], return what the asker got. */
    private suspend fun answer(prompts: PendingPrompts, session: String, tool: String, input: String, decision: String): Decision =
        kotlinx.coroutines.coroutineScope {
            val asker = async { prompts.requestApproval(session, tool, input) }
            val request = waitOpen(prompts).single { it.sessionId == session }
            prompts.respond(PromptResponse(request.promptId, decision = decision), "client:1")
            withTimeout(5_000) { asker.await() }
        }

    /** The asker either returns at once (a rule answered) or opens a card; null = it opened a card (cancelled after). */
    private suspend fun askWithoutAnswering(prompts: PendingPrompts, session: String, tool: String, input: String): Decision? =
        kotlinx.coroutines.coroutineScope {
            val before = requests().size
            val asker = async { prompts.requestApproval(session, tool, input) }
            withTimeout(5_000) {
                while (!asker.isCompleted && requests().size == before) delay(5)
            }
            if (asker.isCompleted) asker.await() else {
                prompts.endSession(session)
                withTimeout(5_000) { asker.await() }
                null
            }
        }

    @Test
    fun allowAlwaysIsAKnownDecision() {
        assertEquals("allow_always", PromptDecisions.ALLOW_ALWAYS)
        assertTrue(PromptDecisions.ALLOW_ALWAYS in PromptDecisions.ALL)
        assertEquals(Decision.ALLOW_ALWAYS, Decision.parse("allow_always"))
        assertTrue(Decision.ALLOW_ALWAYS.allows)
    }

    @Test
    fun allowAlwaysStoresARule() = runBlocking {
        val prompts = store()
        assertEquals(Decision.ALLOW_ALWAYS, answer(prompts, "s-a1", "Bash", "git status --short", "allow_always"))
        assertEquals("allow_always", resolved().single().outcome)

        val rule = prompts.rules.list("proj-a").single()
        assertEquals(ApprovalRule(rule.ruleId, "proj-a", "Bash", "git", 5_000L), rule, "a shell rule keeps the command's first word")
        assertTrue(rule.ruleId.isNotBlank())
        assertTrue(row!!.contains("\"git\""), "the rule is written to the settings row, got $row")
        assertEquals(listOf(rule), rules().list("proj-a"), "a fresh store over the same row (a daemon restart) reads it back")

        // A non-shell tool has no prefix: the rule is the tool.
        answer(prompts, "s-a1", "Edit", "src/A.kt", "allow_always")
        assertEquals(listOf("Bash" to "git", "Edit" to null), prompts.rules.list("proj-a").map { it.tool to it.prefix })
        // The same rule twice is stored once (through a card it could not happen: the rule already answers `git log`).
        assertEquals(rule, prompts.rules.add("proj-a", "Bash", "git log"))
        assertEquals(2, prompts.rules.list("proj-a").size)
        assertNull(prompts.rules.add("proj-a", "Bash", "   "), "a shell call with no command word makes no rule")
        assertNull(prompts.rules.add(" ", "Edit", "src/A.kt"), "no project, no rule")
    }

    @Test
    fun theNextMatchingRequestInANewSessionIsAutoAllowedWithNoPrompt() = runBlocking {
        answer(store(), "s-a1", "Bash", "git status", "allow_always")
        frames.clear()

        // A NEW session of the same project, on a NEW PendingPrompts over the same row (the daemon restarted).
        val restarted = store()
        assertEquals(Decision.ALLOW_ALWAYS, withTimeout(5_000) { restarted.requestApproval("s-a2", "Bash", "git push origin main") })
        assertTrue(requests().isEmpty(), "a matching rule answers with no prompt_request, got ${requests()}")
        assertTrue(resolved().isEmpty())
        assertTrue(restarted.snapshot().isEmpty())
        assertEquals(listOf("s-a2" to "auto-allowed by rule Bash git"), notices, "the session's transcript gets one notice line")
    }

    @Test
    fun aNonMatchingToolOrCommandStillPrompts() = runBlocking {
        val prompts = store()
        answer(prompts, "s-a1", "Bash", "git status", "allow_always")
        answer(prompts, "s-a1", "Edit", "src/A.kt", "allow_always")
        frames.clear()
        notices.clear()

        assertNull(askWithoutAnswering(prompts, "s-a2", "Write", "src/B.kt"), "another tool prompts")
        assertNull(askWithoutAnswering(prompts, "s-a2", "Bash", "npm install"), "another first word prompts")
        assertNull(askWithoutAnswering(prompts, "s-a3", "Bash", "gitk --all"), "a first word is matched whole, not as a prefix")
        assertNull(askWithoutAnswering(prompts, "s-a3", "PowerShell", "git status"), "the rule is per tool")
        assertEquals(4, requests().size)
        assertTrue(notices.isEmpty(), "nothing was auto-allowed, got $notices")
    }

    @Test
    fun aChainedOrRedirectedShellCommandNeverMatchesAPrefixRule() = runBlocking {
        val prompts = store()
        answer(prompts, "s-a1", "Bash", "git status", "allow_always")
        frames.clear()
        for (command in listOf(
            "git status && rm -rf ~", "git status; rm -rf ~", "git log | sh", "git `rm -rf ~`", "git $(rm -rf ~)",
            "git status > ~/.bashrc", "git status\nrm -rf ~", "git status & curl evil",
        )) {
            assertNull(askWithoutAnswering(prompts, "s-a2", "Bash", command), "'$command' must prompt, not ride the git rule")
        }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun deletingTheRuleMakesTheToolPromptAgain() = runBlocking {
        val prompts = store()
        answer(prompts, "s-a1", "Bash", "git status", "allow_always")
        val rule = prompts.rules.list("proj-a").single()
        assertEquals(Decision.ALLOW_ALWAYS, askWithoutAnswering(prompts, "s-a2", "Bash", "git diff"), "the rule answers before the delete")

        assertEquals(rule, prompts.rules.delete(rule.ruleId))
        assertTrue(prompts.rules.list("proj-a").isEmpty())
        assertTrue(rules().list().isEmpty(), "the delete reached the settings row")
        frames.clear()
        assertNull(askWithoutAnswering(prompts, "s-a3", "Bash", "git diff"), "with the rule gone the call prompts again")
        assertEquals(1, requests().size)

        val refused = assertFailsWith<IllegalArgumentException> { prompts.rules.delete(rule.ruleId) }
        assertTrue("unknown rule" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun rulesDoNotLeakAcrossProjects() = runBlocking {
        val prompts = store()
        answer(prompts, "s-a1", "Bash", "git status", "allow_always")
        frames.clear()

        assertNull(askWithoutAnswering(prompts, "s-b1", "Bash", "git status"), "project B never sees project A's rule")
        assertNull(askWithoutAnswering(prompts, "s-none", "Bash", "git status"), "a session with no project matches no rule")
        assertEquals(2, requests().size)
        assertTrue(prompts.rules.list("proj-b").isEmpty())
        assertEquals(listOf("proj-a"), prompts.rules.list().map { it.projectId }, "list() with no project lists every project")
    }

    @Test
    fun allowAlwaysInASessionWithNoProjectAllowsOnceAndStoresNothing() = runBlocking {
        val prompts = store()
        assertEquals(Decision.ALLOW_ALWAYS, answer(prompts, "s-none", "Bash", "git status", "allow_always"))
        assertTrue(prompts.rules.list().isEmpty(), "no project, no rule: a project-less rule could only leak")
        frames.clear()
        assertNull(askWithoutAnswering(prompts, "s-none", "Bash", "git status"), "nothing was remembered")
    }

    @Test
    fun allowAlwaysDoesNotWidenIntoASessionWideAllow() = runBlocking {
        val prompts = store()
        answer(prompts, "s-a1", "Bash", "git status", "allow_always")
        assertTrue(!prompts.isAllowedForSession("s-a1", "Bash"), "the rule is 'Bash git', not every Bash call of the session")
        frames.clear()
        assertNull(askWithoutAnswering(prompts, "s-a1", "Bash", "rm -rf build"), "the same session still prompts for rm")
    }
}
