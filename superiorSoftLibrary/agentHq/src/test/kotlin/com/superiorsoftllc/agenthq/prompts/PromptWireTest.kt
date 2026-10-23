package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResolved
import com.superiorsoftllc.agenthq.protocol.PromptResponse
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
import kotlin.test.assertTrue

/** PROMPT-1 (contract 1.77.0) over the real daemon socket. */
class PromptWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopAll() {
        scope.cancel()
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun respond(send: (String, String) -> Unit, response: PromptResponse) =
        send(Envelope.PROMPT_RESPONSE, json.encodeToString(PromptResponse.serializer(), response))

    @Test
    fun theContractIs1_79_0() {
        assertEquals("1.102.0", EventVocabulary.VERSION)
    }

    @Test
    fun aReconnectingClientSeesTheOpenPromptInItsSnapshot() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val asker = scope.async { fixture.daemon.pendingPrompts.requestApproval("s-wire", "Bash", "ls -la") }
        val live = json.decodeFromString(PromptRequest.serializer(), fixture.client.await(Envelope.PROMPT_REQUEST).data)
        assertEquals("s-wire", fixture.client.await(Envelope.PROMPT_REQUEST).sessionId)

        val late = fixture.connectClient()
        val replayed = json.decodeFromString(PromptRequest.serializer(), late.await(Envelope.PROMPT_REQUEST).data)
        assertEquals(live, replayed, "the connect snapshot carries the open prompt as it was sent")

        respond(late::send, PromptResponse(live.promptId, decision = "allow_once"))
        assertEquals(Decision.ALLOW_ONCE, runBlocking { withTimeout(5_000) { asker.await() } })

        // Resolved: a client connecting now sees no card.
        val after = fixture.connectClient()
        after.await(Envelope.WORKTREES)
        assertTrue(after.of(Envelope.PROMPT_REQUEST).isEmpty())
    }

    @Test
    fun aSecondClientGetsPromptResolvedAndADuplicateOrUnknownAnswerIsRefused() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val second = fixture.connectClient()
        val asker = scope.async { fixture.daemon.pendingPrompts.requestApproval("s-wire", "Edit", "src/A.kt") }
        val request = json.decodeFromString(PromptRequest.serializer(), second.await(Envelope.PROMPT_REQUEST).data)
        fixture.client.await(Envelope.PROMPT_REQUEST)

        respond(fixture::send, PromptResponse(request.promptId, decision = "deny"))
        val closed = json.decodeFromString(PromptResolved.serializer(), second.await(Envelope.PROMPT_RESOLVED).data)
        assertEquals(request.promptId, closed.promptId)
        assertEquals("deny", closed.outcome)
        assertTrue(closed.resolvedBy.startsWith("client:"), "resolvedBy names the answering connection, got ${closed.resolvedBy}")
        fixture.client.await(Envelope.PROMPT_RESOLVED)
        assertEquals(Decision.DENY, runBlocking { withTimeout(5_000) { asker.await() } })

        respond(second::send, PromptResponse(request.promptId, decision = "allow_once"))
        second.awaitWhere("duplicate refusal") { it.type == Envelope.ERROR && "prompt_response refused" in it.data && "already resolved" in it.data }
        respond(second::send, PromptResponse("no-such-prompt", decision = "deny"))
        second.awaitWhere("unknown refusal") { it.type == Envelope.ERROR && "unknown prompt 'no-such-prompt'" in it.data }
        assertEquals(1, second.of(Envelope.PROMPT_RESOLVED).size, "a refused answer resolves nothing")
    }
}
