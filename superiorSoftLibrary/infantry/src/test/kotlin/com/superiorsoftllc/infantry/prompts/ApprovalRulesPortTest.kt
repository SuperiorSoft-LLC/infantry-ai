package com.superiorsoftllc.infantry.prompts

import com.superiorsoftllc.infantry.daemon.api.ApprovalRuleMatch
import com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.PromptRequest
import com.superiorsoftllc.infantry.protocol.PromptResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-10b: PendingPrompts reaches the stored "Always allow" rules only through the `hooks.approvalRules` port (the store is Infantry_Approvals'). */
class ApprovalRulesPortTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val notices = CopyOnWriteArrayList<Pair<String, String>>()

    /** A port backed by a list; [store] false = the plugin declines to keep a rule. */
    private class FakePort(var store: Boolean = true) : ApprovalRulesHook {
        val remembered = CopyOnWriteArrayList<Triple<String?, String, String>>()
        override fun match(projectId: String?, tool: String, input: String): ApprovalRuleMatch? =
            remembered.firstOrNull { it.first == projectId && it.second == tool }?.let { ApprovalRuleMatch(tool, if (tool == "Bash") input.substringBefore(' ') else null) }
        override fun remember(projectId: String?, tool: String, input: String): Boolean {
            if (store) remembered += Triple(projectId, tool, input)
            return store
        }
    }

    private fun prompts(port: ApprovalRulesHook? = null) = (
        if (port == null) PendingPrompts(timeoutMillis = { 60_000 }, projectOf = { "proj-a" })
        else PendingPrompts(timeoutMillis = { 60_000 }, rules = port, projectOf = { "proj-a" })
    ).also { p ->
        p.sink = { e -> frames += e }
        p.notice = { sid, text -> notices += sid to text }
    }

    private fun requests() = frames.filter { it.type == Envelope.PROMPT_REQUEST }.map { json.decodeFromString(PromptRequest.serializer(), it.data) }

    private suspend fun answer(p: PendingPrompts, session: String, tool: String, input: String, decision: String): Decision = coroutineScope {
        val asker = async { p.requestApproval(session, tool, input) }
        val request = withTimeout(5_000) { while (p.snapshot().isEmpty()) delay(5); p.snapshot().single { it.sessionId == session } }
        p.respond(PromptResponse(request.promptId, decision = decision), "client:1")
        withTimeout(5_000) { asker.await() }
    }

    @Test
    fun aMatchingRuleFromThePortAnswersWithNoCardAndOneNotice() = runBlocking {
        val port = FakePort()
        val p = prompts(port)
        assertEquals(Decision.ALLOW_ALWAYS, answer(p, "s1", "Bash", "git status", "allow_always"))
        assertEquals(listOf(Triple<String?, String, String>("proj-a", "Bash", "git status")), port.remembered.toList())
        frames.clear()
        assertEquals(Decision.ALLOW_ALWAYS, withTimeout(5_000) { p.requestApproval("s2", "Bash", "git push") })
        assertTrue(requests().isEmpty(), "the rule answers with no prompt_request")
        assertEquals(listOf("s2" to "auto-allowed by rule Bash git"), notices.toList())
        assertTrue(!p.isAllowedForSession("s1", "Bash"), "a stored rule is not a session-wide allow")
    }

    @Test
    fun noPluginMeansNoStoredRulesAndAllowAlwaysDegradesToAllowSession() = runBlocking {
        val p = prompts()
        assertEquals(Decision.ALLOW_ALWAYS, answer(p, "s1", "Bash", "git status", "allow_always"), "the asker still gets its allow, no error")
        assertTrue(p.isAllowedForSession("s1", "Bash"), "with nothing stored the answer lasts the session")
        frames.clear()
        assertEquals(Decision.ALLOW_SESSION, withTimeout(5_000) { p.requestApproval("s1", "Bash", "ls") }, "same session: no new card")
        assertTrue(requests().isEmpty())
        // Another session has no grant and no rule: it asks.
        val asker = async2(p, "s2")
        assertTrue(asker, "a different session still opens a card")
    }

    @Test
    fun aPortThatCannotKeepTheRuleAlsoDegradesToTheSession() = runBlocking {
        val p = prompts(FakePort(store = false))
        answer(p, "s1", "Edit", "src/A.kt", "allow_always")
        assertTrue(p.isAllowedForSession("s1", "Edit"))
    }

    @Test
    fun aThrowingPortFailsOpenToAskingAndToTheSession() = runBlocking {
        val p = prompts(object : ApprovalRulesHook {
            override fun match(projectId: String?, tool: String, input: String): ApprovalRuleMatch? = error("boom")
            override fun remember(projectId: String?, tool: String, input: String): Boolean = error("boom")
        })
        assertEquals(Decision.ALLOW_ALWAYS, answer(p, "s1", "Bash", "git status", "allow_always"))
        assertTrue(p.isAllowedForSession("s1", "Bash"))
        assertNull(requests().firstOrNull { it.sessionId == "s2" })
    }

    /** True when asking in [session] opens a card (then ends the session to release the asker). */
    private suspend fun async2(p: PendingPrompts, session: String): Boolean = coroutineScope {
        val before = requests().size
        val asker = async { p.requestApproval(session, "Bash", "ls") }
        withTimeout(5_000) { while (!asker.isCompleted && requests().size == before) delay(5) }
        val opened = !asker.isCompleted
        if (opened) { p.endSession(session); withTimeout(5_000) { asker.await() } }
        opened
    }
}
