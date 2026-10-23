package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.ApprovalRuleDelete
import com.superiorsoftllc.agenthq.protocol.ApprovalRulesList
import com.superiorsoftllc.agenthq.protocol.ApprovalRulesQuery
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResponse
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PROMPT-6 (contract 1.78.0) over the real daemon socket and the real settings table. */
class ApprovalRulesWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopAll() {
        scope.cancel()
        fixtures.asReversed().forEach { runCatching { it.stop() } }
        fixtures.map { it.stateDir }.distinct().forEach { runCatching { it.deleteRecursively() } }
    }

    private fun rulesFrame(envelope: Envelope) = json.decodeFromString(ApprovalRulesList.serializer(), envelope.data)

    /** A session of [projectId] whose stub turn has ended, so no turn end races a prompt opened on it. */
    private fun idleSession(fixture: DaemonFixture, projectId: String): String {
        val id = fixture.daemon.manager.startNew("bootstrap", projectId = projectId).id
        // The turn's end resolves that session's open prompts (PROMPT-1), so wait for its `finished` before asking.
        fixture.client.awaitWhere("session $id finished") { it.type == Envelope.FINISHED && it.sessionId == id }
        fixture.await(what = "session $id idle") {
            fixture.daemon.manager.get(id)?.state.let { it != null && it != SessionState.STARTING && it != SessionState.BUSY }
        }
        return id
    }

    @Test
    fun theContractIs1_78_0() {
        assertEquals("1.105.0", EventVocabulary.VERSION)
    }

    @Test
    fun allowAlwaysOverTheSocketMakesTheNextSessionOfTheProjectAutoAllowed() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val first = idleSession(fixture, "proj-a")
        val asker = scope.async { fixture.daemon.pendingPrompts.requestApproval(first, "Bash", "git status") }
        val request = json.decodeFromString(PromptRequest.serializer(), fixture.client.await(Envelope.PROMPT_REQUEST).data)
        fixture.send(Envelope.PROMPT_RESPONSE, json.encodeToString(PromptResponse.serializer(), PromptResponse(request.promptId, decision = "allow_always")))
        assertEquals(Decision.ALLOW_ALWAYS, runBlocking { withTimeout(5_000) { asker.await() } })

        assertNotNull(fixture.daemon.store.settings.get(ApprovalRules.KEY), "the rule is in the settings table under ${ApprovalRules.KEY}")
        val second = idleSession(fixture, "proj-a")
        val prompts = fixture.client.of(Envelope.PROMPT_REQUEST).size
        assertEquals(Decision.ALLOW_ALWAYS, runBlocking { withTimeout(5_000) { fixture.daemon.pendingPrompts.requestApproval(second, "Bash", "git diff") } })
        val notice = fixture.client.awaitWhere("auto-allow notice") { it.type == Envelope.STDERR && it.sessionId == second && "auto-allowed by rule" in it.data }
        assertEquals("[agenthq] auto-allowed by rule Bash git", notice.data)
        assertEquals(prompts, fixture.client.of(Envelope.PROMPT_REQUEST).size, "no prompt_request for the auto-allowed call")

        // Another project's session is not covered: it opens a card.
        val other = idleSession(fixture, "proj-b")
        scope.async { fixture.daemon.pendingPrompts.requestApproval(other, "Bash", "git diff") }
        fixture.client.awaitWhere("proj-b prompts") { it.type == Envelope.PROMPT_REQUEST && it.sessionId == other }
    }

    @Test
    fun approvalRulesListsAndApprovalRuleDeleteRemovesAndSurvivesARestart() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val a = fixture.daemon.pendingPrompts.rules.add("proj-a", "Bash", "git status")!!
        val b = fixture.daemon.pendingPrompts.rules.add("proj-b", "Edit", "src/A.kt")!!

        fixture.send(Envelope.APPROVAL_RULES, json.encodeToString(ApprovalRulesQuery.serializer(), ApprovalRulesQuery("proj-a")))
        assertEquals(ApprovalRulesList("proj-a", listOf(a)), rulesFrame(fixture.client.await(Envelope.APPROVAL_RULES)))
        fixture.send(Envelope.APPROVAL_RULES, "")
        assertEquals(ApprovalRulesList(null, listOf(a, b)), rulesFrame(fixture.client.await(Envelope.APPROVAL_RULES, occurrence = 1)))

        // A restart over the same state dir reads the rules back from the settings table.
        fixture.stop()
        val restarted = DaemonFixture.launch(stateDir = fixture.stateDir).also { fixtures += it }
        restarted.client.await(Envelope.READY)
        assertEquals(listOf(a, b), restarted.daemon.pendingPrompts.rules.list())

        restarted.send(Envelope.APPROVAL_RULE_DELETE, json.encodeToString(ApprovalRuleDelete.serializer(), ApprovalRuleDelete(a.ruleId)))
        assertEquals(ApprovalRulesList("proj-a", emptyList()), rulesFrame(restarted.client.await(Envelope.APPROVAL_RULES)))
        assertEquals(listOf(b), restarted.daemon.pendingPrompts.rules.list())

        restarted.send(Envelope.APPROVAL_RULE_DELETE, json.encodeToString(ApprovalRuleDelete.serializer(), ApprovalRuleDelete(a.ruleId)))
        val refused = restarted.client.awaitWhere("unknown rule refusal") { it.type == Envelope.ERROR && "approval_rule_delete refused" in it.data }
        assertTrue("unknown rule '${a.ruleId}'" in refused.data, refused.data)
        assertEquals(listOf(b), restarted.daemon.pendingPrompts.rules.list(), "a refused delete changes nothing")
    }
}
