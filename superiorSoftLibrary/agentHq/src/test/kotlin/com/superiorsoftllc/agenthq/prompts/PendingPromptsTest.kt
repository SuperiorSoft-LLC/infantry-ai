package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.PromptRequest
import com.superiorsoftllc.agenthq.protocol.PromptResolved
import com.superiorsoftllc.agenthq.protocol.PromptResponse
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.ToolApprovalNotice
import com.superiorsoftllc.agenthq.session.ToolApprovals
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PROMPT-1 (contract 1.77.0): the PendingPrompts store, without a daemon. */
class PendingPromptsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val ids = AtomicInteger()

    private fun store(timeoutMs: Long = 60_000) =
        PendingPrompts(timeoutMillis = { timeoutMs }, clock = { 1_000L }, newId = { "p" + ids.incrementAndGet() }).also { it.sink = { e -> frames += e } }

    private fun requests() = frames.filter { it.type == Envelope.PROMPT_REQUEST }.map { json.decodeFromString(PromptRequest.serializer(), it.data) }
    private fun resolved() = frames.filter { it.type == Envelope.PROMPT_RESOLVED }.map { json.decodeFromString(PromptResolved.serializer(), it.data) }

    /** Wait until [count] prompts are open (the asker has suspended). */
    private suspend fun open(prompts: PendingPrompts, count: Int = 1) = withTimeout(5_000) {
        while (prompts.snapshot().size < count) delay(5)
        prompts.snapshot()
    }

    private val spec = QuestionSpec("Which db?", listOf(PromptOption("sqlite", "local"), PromptOption("postgres")), multiSelect = false, allowOther = true)

    @Test
    fun anApprovalRoundTripsRequestToResponse() = runBlocking {
        val prompts = store()
        val asker: Deferred<Decision> = async { prompts.requestApproval("s1", "Bash", "rm -rf build\n  && ls") }
        val request = open(prompts).single()

        assertEquals("tool_approval", request.kind)
        assertEquals("s1", request.sessionId)
        assertEquals("Bash", request.tool)
        assertEquals("rm -rf build && ls", request.input, "the input is a one-line summary")
        assertEquals(1_000L, request.createdAt)
        assertEquals(listOf(request), requests(), "prompt_request went to the sink")
        assertEquals("s1", frames.first().sessionId)

        val frame = prompts.respond(PromptResponse(request.promptId, decision = "allow_once"), "client:7")
        assertEquals(Decision.ALLOW_ONCE, withTimeout(5_000) { asker.await() })
        assertEquals(PromptResolved(request.promptId, "client:7", "allow_once"), frame)
        assertEquals(listOf(frame), resolved())
        assertTrue(prompts.snapshot().isEmpty())
    }

    @Test
    fun aQuestionRoundTripsWithAnswersAndOtherText() = runBlocking {
        val prompts = store()
        val asker = async { prompts.askQuestion("s1", spec) }
        val request = open(prompts).single()
        assertEquals("question", request.kind)
        assertEquals("Which db?", request.question)
        assertEquals(spec.options, request.options)
        assertTrue(request.allowOther)
        assertFalse(request.multiSelect)

        prompts.respond(PromptResponse(request.promptId, answers = listOf("postgres"), otherText = " v16 "), "client:1")
        assertEquals(Answer(listOf("postgres"), "v16", answered = true), withTimeout(5_000) { asker.await() })
        assertEquals("answered", resolved().single().outcome)
    }

    @Test
    fun anUnansweredPromptTimesOutAsDeny() = runBlocking {
        val prompts = store(timeoutMs = 50)
        // The guard is longer than the prompt timeout: without the timeout the asker never returns and this is null.
        val decision = withTimeoutOrNull(5_000) { prompts.requestApproval("s1", "Bash", "ls") }
        assertEquals(Decision.DENY, decision, "an unanswered approval must resolve as deny after prompts.timeoutSec")
        assertEquals("timeout", resolved().single().resolvedBy)
        assertEquals("deny", resolved().single().outcome)
        assertTrue(prompts.snapshot().isEmpty())

        val answer = withTimeoutOrNull(5_000) { prompts.askQuestion("s1", spec) }
        assertEquals(Answer.NONE, answer, "an unanswered question resolves as no answer")
        assertEquals("no_answer", resolved().last().outcome)
    }

    @Test
    fun anExpiredApprovalResolvesWithTheConfiguredActionAndIsLogged() = runBlocking {
        val notices = CopyOnWriteArrayList<String>()
        val prompts = PendingPrompts(
            clock = { 1_000L }, newId = { "p" + ids.incrementAndGet() },
            policyFor = { PromptTimeoutPolicy(50, TimeoutAction.APPROVE) },
        ).also { it.sink = { e -> frames += e }; it.notice = { s, text -> notices += "$s: $text" } }
        // Nobody answers: the timer must unblock the asker with the configured action (approve = allow once).
        val outcome = withTimeoutOrNull(5_000) { prompts.requestApprovalOutcome("s1", "Bash", "ls") }
        assertEquals(ApprovalOutcome(Decision.ALLOW_ONCE, timedOut = true, timeoutSec = 0), outcome)
        assertEquals(1_050L, requests().single().expiresAt, "the card carries its deadline")
        assertEquals("allow_once", requests().single().onTimeout)
        assertEquals(PromptResolved("p1", "timeout", "allow_once"), resolved().single())
        assertTrue(notices.single().startsWith("s1: tool approval for Bash timed out"), notices.toString())
    }

    @Test
    fun anExpiredQuestionAutoSelectsItsRecommendedOptionAndIsLogged() = runBlocking {
        val notices = CopyOnWriteArrayList<String>()
        val prompts = PendingPrompts(
            clock = { 1_000L }, newId = { "p" + ids.incrementAndGet() },
            policyFor = { PromptTimeoutPolicy(50, TimeoutAction.DENY) },
        ).also { it.sink = { e -> frames += e }; it.notice = { s, text -> notices += "$s: $text" } }
        val options = listOf(PromptOption("sqlite"), PromptOption("postgres (Recommended)"))
        // ASK-TIMER-2: Claude marks its recommendation in the label; nobody answers, so the timer picks it.
        val recommended = QuestionSpec.recommendedIn(options)
        val answer = withTimeoutOrNull(5_000) { prompts.askQuestion("s1", QuestionSpec("Which db?", options, recommended = recommended)) }
        assertEquals(Answer(listOf("postgres (Recommended)"), null, answered = true), answer)
        assertEquals("postgres (Recommended)", requests().single().recommended, "the card names what the timer will pick")
        assertEquals(PromptResolved("p1", "timeout", "answered"), resolved().single())
        assertTrue(notices.single().startsWith("s1: auto-selected recommended: postgres (Recommended)"), notices.toString())

        // A recommended approval wins over prompts.onTimeout (deny here).
        val outcome = withTimeoutOrNull(5_000) { prompts.requestApprovalOutcome("s1", "Bash", "ls", recommended = Decision.ALLOW_SESSION) }
        assertEquals(ApprovalOutcome(Decision.ALLOW_ONCE, timedOut = true, timeoutSec = 0), outcome, "the timer only ever allows once")
        assertEquals("allow_once", requests().last().onTimeout)
        assertTrue(notices.last().startsWith("s1: auto-selected recommended: Allow"), notices.toString())
    }

    @Test
    fun allowSessionIsRememberedForThatSessionUntilItEnds() = runBlocking {
        val prompts = store()
        val first = async { prompts.requestApproval("s1", "Bash", "ls") }
        prompts.respond(PromptResponse(open(prompts).single().promptId, decision = "allow_session"), "client:1")
        assertEquals(Decision.ALLOW_SESSION, first.await())

        // Same session, same tool: no prompt at all.
        assertEquals(Decision.ALLOW_SESSION, withTimeout(1_000) { prompts.requestApproval("s1", "Bash", "pwd") })
        assertEquals(1, requests().size, "a remembered tool opens no prompt")

        // Another tool, or another session, still asks.
        val other = async { prompts.requestApproval("s2", "Bash", "ls") }
        assertEquals("s2", open(prompts).single().sessionId)
        prompts.respond(PromptResponse(prompts.snapshot().single().promptId, decision = "deny"), "client:1")
        assertEquals(Decision.DENY, other.await())

        // The session ends: the memory goes with it.
        prompts.endSession("s1")
        assertFalse(prompts.isAllowedForSession("s1", "Bash"))
        val again = async { prompts.requestApproval("s1", "Bash", "ls") }
        open(prompts)
        assertEquals(3, requests().size)
        prompts.endSession("s1")
        assertEquals(Decision.DENY, again.await())
    }

    @Test
    fun aSessionThatEndsResolvesItsOpenPromptsOnly() = runBlocking {
        val prompts = store()
        val a = async { prompts.requestApproval("s1", "Bash", "ls") }
        val b = async { prompts.askQuestion("s1", spec) }
        val c = async { prompts.requestApproval("s2", "Bash", "ls") }
        open(prompts, 3)

        prompts.onSessionEvent(SessionEvent.Exit("s1", code = 0))
        assertEquals(Decision.DENY, withTimeout(5_000) { a.await() })
        assertEquals(Answer.NONE, withTimeout(5_000) { b.await() })
        assertEquals(listOf("session_end", "session_end"), resolved().map { it.resolvedBy })
        assertEquals(listOf("s2"), prompts.snapshot().map { it.sessionId }, "another session's prompt stays open")

        prompts.endSession("s2")
        assertEquals(Decision.DENY, c.await())
    }

    @Test
    fun anUnknownDuplicateOrMalformedResponseIsRefused() = runBlocking {
        val prompts = store()
        assertFailsWith<IllegalArgumentException> { prompts.respond(PromptResponse("nope", decision = "deny"), "client:1") }
            .also { assertTrue("unknown prompt 'nope'" in it.message!!) }

        val asker = async { prompts.requestApproval("s1", "Bash", "ls") }
        val id = open(prompts).single().promptId
        for (bad in listOf(PromptResponse(id, decision = "yes"), PromptResponse(id), PromptResponse(id, decision = "deny", answers = listOf("x")))) {
            assertFailsWith<IllegalArgumentException> { prompts.respond(bad, "client:1") }
        }
        assertEquals(1, prompts.snapshot().size, "a refused answer leaves the prompt open")

        prompts.respond(PromptResponse(id, decision = "allow_once"), "client:1")
        assertEquals(Decision.ALLOW_ONCE, asker.await())
        assertFailsWith<IllegalArgumentException> { prompts.respond(PromptResponse(id, decision = "deny"), "client:2") }
            .also { assertTrue("already resolved" in it.message!!) }
        assertEquals(1, resolved().size, "a duplicate answer resolves nothing")

        val question = async { prompts.askQuestion("s1", spec.copy(allowOther = false)) }
        val qid = open(prompts).single().promptId
        for (bad in listOf(
            PromptResponse(qid, answers = listOf("mysql")),
            PromptResponse(qid, answers = listOf("sqlite", "postgres")),
            PromptResponse(qid, decision = "deny"),
            PromptResponse(qid),
        )) {
            assertFailsWith<IllegalArgumentException> { prompts.respond(bad, "client:1") }
        }
        prompts.endSession("s1")
        assertEquals(Answer.NONE, question.await())
    }

    @Test
    fun askIsAnAdditiveModeThatIsNotTheDefaultAndDeniesWhereAHarnessCannotPrompt() {
        assertEquals(ToolApproval.ASK, ToolApproval.parse("ask"))
        assertEquals(listOf("deny", "allowEdits", "allowAll", "ask"), ToolApproval.WIRE)
        assertEquals(ToolApproval.DENY, ToolApprovals.resolve(null, null, { null }, { null }).mode, "ask is not the default")
        assertEquals("setting", ToolApprovals.resolve(null, ToolApproval.ASK, { null }, { null }).source)
        assertEquals(ToolApproval.ASK, ToolApprovals.resolve(null, null, { null }, { if (it == ToolApprovals.ENV) "ask" else null }).mode)
        assertEquals(ToolApproval.DENY, ToolApproval.ASK.withoutPrompt())
        assertEquals(
            "claude denied tool Bash (Tool approval = ask) (this harness can't prompt). Change it in Settings > Tool approval.",
            ToolApprovalNotice.text("claude", "Bash", ToolApproval.ASK),
        )
        assertFalse("can't prompt" in ToolApprovalNotice.text("claude", "Bash", ToolApproval.DENY))
    }
}
