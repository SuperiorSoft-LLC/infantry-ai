// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPromptEvent
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire
import com.superiorsoftllc.infantry.prompts.Decision
import com.superiorsoftllc.infantry.prompts.PendingPrompts
import com.superiorsoftllc.infantry.prompts.PromptTimeoutPolicy
import com.superiorsoftllc.infantry.prompts.QuestionSpec
import com.superiorsoftllc.infantry.prompts.TimeoutAction
import com.superiorsoftllc.infantry.protocol.PromptOption
import com.superiorsoftllc.infantry.protocol.PromptResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-PROMPT-RELAY-1: a session on a runner that asks for a tool approval is shown in the orchestrator's prompts and the owner's
 * answer reaches the runner, with no daemon and no transport: the runner's [PendingPrompts] + [RemoteStarts] outbox on one side, the
 * orchestrator's [PendingPrompts] + [RunnerPromptRelay] on the other, the runner's local link between them (the wire frame is the
 * same call, encoded by the RemoteRunner plugin).
 */
class RunnerPromptRelayTest {
    private val ids = AtomicInteger()
    private val now = AtomicLong(1_000_000L)

    private inner class Rig(timeoutMs: Long = 600_000L, onTimeout: TimeoutAction = TimeoutAction.DENY, val store: java.io.File? = null) {
        val starts = RemoteStarts(cloneRoot = { error("no clone") }, outboxMax = { 100 }, clock = { now.get() })
        val runner = PendingPrompts(
            clock = { now.get() }, newId = { "rp" + ids.incrementAndGet() },
            policyFor = { PromptTimeoutPolicy(timeoutMs, onTimeout) },
        )
        val orchestrator = PendingPrompts(newId = { "op" + ids.incrementAndGet() })
        val reachable = AtomicBoolean(true)
        private val runnerLink: RunnerLink = LocalRunnerLink(
            LocalRunnerServices(
                ticketIdOf = { null }, info = { RunnerInfo("r1", RunnerLink.CONTRACT_VERSION) }, start = { null }, stop = { _, _, _ -> false },
                deliver = { _, _ -> MailResult.Delivered }, health = { RunnerHealth(RunnerHealth.UP) },
                answerPrompt = { id, reply -> runner.answerRelayed(id, reply, "orchestrator:test") },
            ),
        )
        private val gated = object : RunnerLink by runnerLink {
            override fun answerPrompt(sessionId: String, reply: PromptReply): OrchestratorReply<Boolean> =
                if (reachable.get()) runnerLink.answerPrompt(sessionId, reply) else OrchestratorReply.Unavailable("down")
        }
        var relay = RunnerPromptRelay(orchestrator, link = { name -> gated.takeIf { name == "r1" } }, projectOf = { "proj" }, log = {}, store = store)
        var orchestratorNow = orchestrator
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

        /** RUNNER-UNIFY-5: the orchestrator restarts: a fresh prompt store and relay on the same file; the runner is untouched. */
        fun restartOrchestrator(placed: (String) -> Boolean = { true }): Int {
            orchestratorNow = PendingPrompts(newId = { "op" + ids.incrementAndGet() })
            relay = RunnerPromptRelay(orchestratorNow, link = { name -> gated.takeIf { name == "r1" } }, projectOf = { "proj" }, log = { logs.add(it) }, store = store)
            return relay.restore(placed)
        }
        private val acked = HashMap<String, Long>()
        /** RUNNER-UNIFY-5: the frames the runner put on its sessions' stream, not yet taken by the orchestrator. */
        val frames = java.util.concurrent.CopyOnWriteArrayList<com.superiorsoftllc.infantry.protocol.Envelope>()

        init {
            runner.observer = relay.runnerObserver(starts) { frames.add(it) }
            runner.heldElsewhere = relay.heldElsewhere(starts)
            starts.linked("s1")
        }

        /** One watch round: read the runner's outbox, apply every new event on the orchestrator, acknowledge it. */
        fun pump(): List<RunnerEvent> {
            val events = starts.events(acked.toMap()).filter { it.seq > (acked[it.sessionId] ?: 0) }
            events.forEach { relay.apply("r1", it); acked[it.sessionId] = it.seq }
            return events
        }

        /** RUNNER-UNIFY-5: one pushed batch: every frame the runner published goes to the orchestrator's frame path. */
        fun pushFrames(): List<String> {
            val batch = frames.toList().also { frames.removeAll(it.toSet()) }
            batch.forEach { assertTrue(relay.applyFrame("r1", it.sessionId!!, it.type, it.data), "a prompt frame is the relay's") }
            return batch.map { it.type }
        }
    }

    private suspend fun waitFor(what: String, check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(5)
        assertTrue(check(), what)
    }

    @Test
    fun anApprovalOnTheRunnerIsShownOnTheOrchestratorAndItsAnswerGoesBack() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls -la /work") }
        waitFor("the runner opened the prompt") { rig.runner.snapshot().isNotEmpty() }
        val onRunner = rig.runner.snapshot().single()
        assertEquals(listOf(RunnerEvent.PROMPT), rig.pump().map { it.kind }, "the prompt went to the outbox as one event")
        waitFor("the orchestrator shows the card") { rig.orchestrator.snapshot().isNotEmpty() }
        val card = rig.orchestrator.snapshot().single()
        assertEquals(onRunner.promptId, card.promptId, "the same prompt id on both sides")
        assertEquals("s1", card.sessionId)
        assertEquals("bash", card.tool)
        assertNotNull(card.expiresAt, "the orchestrator runs the runner's timer")

        rig.orchestrator.respond(PromptResponse(card.promptId, decision = Decision.ALLOW_ONCE.wire), "client:1")
        val outcome = withTimeout(5_000) { asker.await() }
        assertEquals(Decision.ALLOW_ONCE, outcome.decision, "the owner's answer reached the runner's asker")
        assertFalse(outcome.timedOut)
        assertEquals(listOf(RunnerEvent.PROMPT_RESOLVED), rig.pump().map { it.kind }, "the runner reports the close")
        assertTrue(rig.orchestrator.snapshot().isEmpty())
    }

    @Test
    fun aQuestionOnTheRunnerIsAnsweredFromTheOrchestrator() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.askQuestion("s1", QuestionSpec("Which db?", listOf(PromptOption("sqlite"), PromptOption("postgres")))) }
        waitFor("the runner opened the question") { rig.runner.snapshot().isNotEmpty() }
        rig.pump()
        waitFor("the orchestrator shows it") { rig.orchestrator.snapshot().isNotEmpty() }
        val card = rig.orchestrator.snapshot().single()
        rig.orchestrator.respond(PromptResponse(card.promptId, answers = listOf("postgres")), "client:1")
        val answer = withTimeout(5_000) { asker.await() }
        assertTrue(answer.answered)
        assertEquals(listOf("postgres"), answer.answers)
    }

    @Test
    fun theRunnerRunsNoTimerOfItsOwnWhileTheOrchestratorReadsItsOutbox() = runBlocking {
        val rig = Rig(timeoutMs = 50L)
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        delay(300)
        assertEquals(1, rig.runner.snapshot().size, "a relayed prompt does not expire on the runner's own 50 ms timer")
        assertEquals(0, rig.runner.expireHeld(rig.starts.lastOrchestratorRead), "the timer has not run out on the runner's clock")
        rig.pump()
        assertEquals(0, rig.runner.expireHeld(rig.starts.lastOrchestratorRead), "reachable orchestrator: nothing expires here")
        // the orchestrator's card runs the runner's 50 ms timer, resolves by prompts.onTimeout (deny) and answers that back
        waitFor("the orchestrator's timer answered") { asker.isCompleted }
        assertEquals(Decision.DENY, asker.await().decision)
    }

    @Test
    fun anUnreachableOrchestratorFallsBackToTheRunnersOnTimeout() = runBlocking {
        val rig = Rig(timeoutMs = 60_000L, onTimeout = TimeoutAction.APPROVE)
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        // nobody read the outbox: after a whole timer the runner applies prompts.onTimeout itself
        now.addAndGet(59_000)
        assertEquals(0, rig.runner.expireHeld(rig.starts.lastOrchestratorRead))
        now.addAndGet(2_000)
        assertEquals(1, rig.runner.expireHeld(rig.starts.lastOrchestratorRead))
        val outcome = withTimeout(5_000) { asker.await() }
        assertEquals(Decision.ALLOW_ONCE, outcome.decision, "prompts.onTimeout = approve")
        assertTrue(outcome.timedOut)
    }

    @Test
    fun anAnswerTheRunnerCouldNotTakeIsSentAgain() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        rig.pump()
        waitFor("card") { rig.orchestrator.snapshot().isNotEmpty() }
        rig.reachable.set(false)
        rig.orchestrator.respond(PromptResponse(rig.orchestrator.snapshot().single().promptId, decision = Decision.DENY.wire), "client:1")
        delay(100)
        assertFalse(asker.isCompleted, "the runner was down: the answer is kept")
        rig.reachable.set(true)
        waitFor("retried") { rig.relay.retry() >= 0 && asker.isCompleted }
        assertEquals(Decision.DENY, asker.await().decision)
    }

    @Test
    fun aSessionTheOrchestratorDidNotStartIsNeitherRelayedNorAnsweredFromIt() = runBlocking {
        val rig = Rig(timeoutMs = 100L)
        val asker = async { rig.runner.requestApprovalOutcome("local-session", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        assertTrue(rig.pump().isEmpty(), "a local session's prompt stays local")
        val id = rig.runner.snapshot().single().promptId
        assertFalse(rig.runner.answerRelayed("s1", PromptReply(id, Decision.ALLOW_ONCE.wire), "orchestrator:test"), "an answer naming another session is not applied")
        assertEquals(Decision.DENY, withTimeout(5_000) { asker.await() }.decision, "its own timer still runs")
    }

    @Test
    fun aReplayedPromptEventOpensNoSecondCard() {
        val rig = Rig()
        val event = RunnerEvent("s1", 1, RunnerEvent.PROMPT, RunnerPromptEvent("""{"promptId":"x1","sessionId":"s1","kind":"tool_approval","createdAt":1,"tool":"bash"}""", "ls").encode())
        rig.relay.apply("r1", event)
        runBlocking { waitFor("card") { rig.orchestrator.snapshot().isNotEmpty() } }
        rig.relay.apply("r1", event)
        Thread.sleep(100)
        assertEquals(1, rig.orchestrator.snapshot().size)
        assertNull(RunnerPromptEvent.decode("not json"))
        // a prompt naming another session than its event is dropped
        rig.relay.apply("r1", RunnerEvent("s1", 2, RunnerEvent.PROMPT, RunnerPromptEvent("""{"promptId":"x2","sessionId":"other","kind":"tool_approval","createdAt":1}""").encode()))
        Thread.sleep(100)
        assertEquals(listOf("x1"), rig.orchestrator.snapshot().map { it.promptId })
    }

    @Test
    fun framePromptsAnApprovalCrossesAsAStreamFrameAndItsAnswerGoesBack() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls -la /work") }
        waitFor("the runner opened the prompt") { rig.runner.snapshot().isNotEmpty() }
        val onRunner = rig.runner.snapshot().single()
        assertEquals(listOf(RunnerLinkWire.PROMPT_FRAME), rig.pushFrames(), "the prompt is one frame on the session's stream")
        // The card is open in the store when applyFrame returns: no wait, so a close in the same batch finds it.
        val card = rig.orchestrator.snapshot().single()
        assertEquals(onRunner.promptId, card.promptId, "the same prompt id on both sides")
        assertEquals("s1", card.sessionId)
        rig.orchestrator.respond(PromptResponse(card.promptId, decision = Decision.ALLOW_ONCE.wire), "client:1")
        assertEquals(Decision.ALLOW_ONCE, withTimeout(5_000) { asker.await() }.decision, "the owner's answer reached the runner's asker")
        assertEquals(listOf(RunnerLinkWire.PROMPT_RESOLVED_FRAME), rig.pushFrames(), "the runner reports the close as a frame")
        assertTrue(rig.orchestrator.snapshot().isEmpty())
    }

    @Test
    fun framePromptsAPromptOpenedAndClosedInOneBatchLeavesNoCard() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        val id = rig.runner.snapshot().single().promptId
        rig.runner.respond(PromptResponse(id, decision = Decision.DENY.wire), "client:runner")
        withTimeout(5_000) { asker.await() }
        assertEquals(listOf(RunnerLinkWire.PROMPT_FRAME, RunnerLinkWire.PROMPT_RESOLVED_FRAME), rig.pushFrames())
        assertTrue(rig.orchestrator.snapshot().isEmpty(), "a prompt the runner closed in the same batch is not left open here")
    }

    @Test
    fun framePromptsOnlyTheOrchestratorsSessionsCrossAndOtherFramesAreNotTheRelays() = runBlocking {
        val rig = Rig()
        val asker = async { rig.runner.requestApprovalOutcome("local-only", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        assertTrue(rig.frames.isEmpty(), "a session the orchestrator did not start puts no prompt frame on the link")
        assertFalse(rig.relay.applyFrame("r1", "s1", com.superiorsoftllc.infantry.protocol.Envelope.STDOUT, "x"))
        rig.runner.closeAll()
        runCatching { withTimeout(5_000) { asker.await() } }
        Unit
    }

    @Test
    fun framePromptsACardOpenAtAnOrchestratorRestartIsReopenedAndItsAnswerReachesTheRunner() = runBlocking {
        val file = kotlin.io.path.createTempFile("relayed-prompts", ".json").toFile().also { it.delete(); it.deleteOnExit() }
        val rig = Rig(store = file)
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        rig.pushFrames()
        assertEquals(1, rig.orchestratorNow.snapshot().size)
        // The orchestrator stops with the card open; the runner keeps holding its prompt (no timer while the orchestrator is reachable).
        assertEquals(1, rig.restartOrchestrator(), "the open card is reopened from the file")
        val card = rig.orchestratorNow.snapshot().single()
        rig.orchestratorNow.respond(PromptResponse(card.promptId, decision = Decision.ALLOW_ONCE.wire), "client:1")
        assertEquals(Decision.ALLOW_ONCE, withTimeout(5_000) { asker.await() }.decision, "the answer after the restart reached the runner")
        runCatching { waitFor("delivered") { file.readText().trim() == "[]" } }
        assertEquals("[]", file.readText().trim(), "the file after delivery; relay log: ${rig.logs}")
        assertEquals(0, rig.restartOrchestrator(), "a delivered answer leaves nothing to reopen")
    }

    @Test
    fun framePromptsARestoredCardTheRunnerClosedMeanwhileIsClosedByItsCloseFrame() = runBlocking {
        val file = kotlin.io.path.createTempFile("relayed-prompts", ".json").toFile().also { it.delete(); it.deleteOnExit() }
        val rig = Rig(store = file)
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        rig.pushFrames()
        // While the orchestrator is down the runner closes the prompt; its close frame is read only after the restart.
        rig.runner.respond(PromptResponse(rig.runner.snapshot().single().promptId, decision = Decision.DENY.wire), "client:runner")
        withTimeout(5_000) { asker.await() }
        assertEquals(1, rig.restartOrchestrator())
        assertEquals(listOf(RunnerLinkWire.PROMPT_RESOLVED_FRAME), rig.pushFrames())
        assertTrue(rig.orchestratorNow.snapshot().isEmpty(), "the reopened card closes with the runner's close")
        assertEquals(0, rig.restartOrchestrator(), "and is not reopened again")
    }

    @Test
    fun framePromptsACardOfASessionNoLongerPlacedIsNotReopened() = runBlocking {
        val file = kotlin.io.path.createTempFile("relayed-prompts", ".json").toFile().also { it.delete(); it.deleteOnExit() }
        val rig = Rig(store = file)
        val asker = async { rig.runner.requestApprovalOutcome("s1", "bash", "ls") }
        waitFor("open") { rig.runner.snapshot().isNotEmpty() }
        rig.pushFrames()
        assertEquals(0, rig.restartOrchestrator(placed = { false }))
        assertTrue(rig.orchestratorNow.snapshot().isEmpty())
        rig.runner.closeAll()
        runCatching { withTimeout(5_000) { asker.await() } }
        Unit
    }
}
