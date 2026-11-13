// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPromptEvent
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire
import com.superiorsoftllc.infantry.prompts.PendingPrompts
import com.superiorsoftllc.infantry.prompts.PromptObserver
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.PromptRequest
import com.superiorsoftllc.infantry.protocol.PromptResolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-PROMPT-RELAY-1: a session on a runner that asks a human (a tool approval, a question) behaves as a local one: the prompt is
 * shown in the orchestrator's app and the owner's answer goes back to the runner. Both halves live in every daemon (a daemon is a
 * runner of its own orchestrator and an orchestrator of its own runners); neither names a harness.
 *
 * RUNNER side ([runnerObserver], [heldElsewhere]): a prompt of a session the orchestrator started ([RemoteStarts.isLinked]) is queued
 * in the outbox as a [RunnerEvent.PROMPT] event, and its close as [RunnerEvent.PROMPT_RESOLVED]. It runs no timer of its own while
 * the orchestrator is reachable; [PendingPrompts.expireHeld] applies `prompts.onTimeout` once the orchestrator read nothing for a
 * whole timer.
 *
 * ORCHESTRATOR side ([apply], [retry]): a [RunnerEvent.PROMPT] event opens the same prompt in this daemon's [prompts] (every client
 * shows the card; the same `prompt_response` answers it; the same rules apply), and its answer goes to the runner with
 * [RunnerLink.answerPrompt]. An answer the runner could not be reached for is kept and sent again by [retry] until the runner takes
 * it or reports the prompt closed. [RunnerEvent.PROMPT_RESOLVED] closes the card here.
 *
 * RUNNER-UNIFY-5 (docs/RUNNER-UNIFY.md 5.4): the same prompt and close also cross as frames on the session's pushed stream
 * ([applyFrame]); an orchestrator on `runners.framePrompts` takes them from there and ignores the events. The event path stays
 * until RUNNER-UNIFY-6 deletes it.
 */
class RunnerPromptRelay(
    private val prompts: PendingPrompts,
    /** The link to a runner by name (the pool's). */
    private val link: (runner: String) -> RunnerLink?,
    /** The project of a session placed on a runner (the start's), for "Always allow" rules. */
    private val projectOf: (sessionId: String) -> String?,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class Pending(val runner: String, val sessionId: String, val reply: PromptReply)

    /** Answers not delivered yet, by prompt id. */
    private val undelivered = ConcurrentHashMap<String, Pending>()

    /** ORCHESTRATOR: one event a runner queued; prompt events are relayed, the others ignored. */
    fun apply(runner: String, event: RunnerEvent) {
        when (event.kind) {
            RunnerEvent.PROMPT -> open(runner, event.sessionId, event.payload)
            RunnerEvent.PROMPT_RESOLVED -> close(event.payload)
        }
    }

    /**
     * RUNNER-UNIFY-5 (orchestrator side, behind `runners.framePrompts`): one frame a runner PUSHED on [sessionId]'s stream. A prompt
     * frame opens the card and its close closes it, exactly as the events do (same payloads, same prompt store). True = it was a
     * prompt frame (consumed); any other frame type is not this relay's.
     */
    fun applyFrame(runner: String, sessionId: String, type: String, data: String): Boolean {
        when (type) {
            RunnerLinkWire.PROMPT_FRAME -> open(runner, sessionId, data)
            RunnerLinkWire.PROMPT_RESOLVED_FRAME -> close(data)
            else -> return false
        }
        return true
    }

    private fun open(runner: String, sessionId: String, payload: String) {
        val p = RunnerPromptEvent.decode(payload) ?: return log("[infantry] runner $runner: unreadable prompt of $sessionId")
        val request = runCatching { json.decodeFromString(PromptRequest.serializer(), p.request) }.getOrElse {
            return log("[infantry] runner $runner: unreadable prompt of $sessionId: ${it.message}")
        }
        // The card is the runner session's: never a prompt naming another session.
        if (request.sessionId != sessionId) return log("[infantry] runner $runner: prompt ${request.promptId.take(40)} names another session; dropped")
        // UNDISPATCHED: the card is open in the store before this returns, so a close read right after it (the same batch) finds it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val reply = runCatching { prompts.relay(request, p.input, projectOf(sessionId)) }
                .onFailure { log("[infantry] runner $runner: prompt relay failed: ${it.message}") }.getOrNull() ?: return@launch
            deliver(Pending(runner, sessionId, reply))
        }
    }

    private fun close(payload: String) {
        val frame = runCatching { json.decodeFromString(PromptResolved.serializer(), payload) }.getOrNull() ?: return
        undelivered.remove(frame.promptId)
        prompts.closeRelayed(frame)
    }

    /** ORCHESTRATOR: send every answer a runner could not be reached for again (the daemon calls it every heartbeat round). */
    fun retry(): Int = undelivered.values.toList().count { deliver(it) }

    private fun deliver(p: Pending): Boolean {
        val l = link(p.runner)
        when (val r = l?.answerPrompt(p.sessionId, p.reply) ?: OrchestratorReply.Unavailable("runner '${p.runner}' is not paired")) {
            is OrchestratorReply.Ok -> {
                undelivered.remove(p.reply.promptId)
                return true
            }
            is OrchestratorReply.Unavailable -> {
                // A refused answer (the runner had no such prompt any more, or the session is not ours) is not retried for ever: the
                // runner's own prompt_resolved event, or the session's end, closes the card.
                if (undelivered.put(p.reply.promptId, p) == null) log("[infantry] runner ${p.runner}: prompt answer not delivered yet (${r.reason}); retried each heartbeat")
                return false
            }
        }
    }

    /** RUNNER: whether [sessionId]'s prompts are answered by the orchestrator (it started the session here). */
    fun heldElsewhere(starts: RemoteStarts): (String) -> Boolean = starts::isLinked

    /**
     * RUNNER: queues the prompts of the orchestrator's sessions, and their close, in [starts]' outbox. RUNNER-UNIFY-5: the same two
     * also go on the session's stream as [RunnerLinkWire.PROMPT_FRAME] / [RunnerLinkWire.PROMPT_RESOLVED_FRAME] through [publish]
     * (the stream sequencer, link-only), so an orchestrator on `runners.framePrompts` reads them with the session's pushed frames.
     */
    fun runnerObserver(starts: RemoteStarts, publish: (Envelope) -> Unit = {}): PromptObserver = object : PromptObserver {
        override fun opened(request: PromptRequest, fullInput: String?) {
            val payload = RunnerPromptEvent(json.encodeToString(PromptRequest.serializer(), request), fullInput).encode()
            starts.onPrompt(request.sessionId, payload)
            if (starts.isLinked(request.sessionId)) runCatching { publish(Envelope(RunnerLinkWire.PROMPT_FRAME, payload, request.sessionId)) }
        }

        override fun resolved(request: PromptRequest, frame: PromptResolved) {
            // A relayed card closing on the orchestrator is not a runner prompt: only the runner's own sessions are reported.
            if (frame.resolvedBy.startsWith(PendingPrompts.RUNNER_RESOLVED)) return
            val payload = json.encodeToString(PromptResolved.serializer(), frame)
            starts.onPromptResolved(request.sessionId, payload)
            if (starts.isLinked(request.sessionId)) runCatching { publish(Envelope(RunnerLinkWire.PROMPT_RESOLVED_FRAME, payload, request.sessionId)) }
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
