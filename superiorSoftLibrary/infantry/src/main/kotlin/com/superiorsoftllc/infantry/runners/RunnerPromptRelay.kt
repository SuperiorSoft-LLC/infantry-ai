// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-PROMPT-RELAY-1: a session on a runner that asks a human (a tool approval, a question) behaves as a local one: the prompt is
 * shown in the orchestrator's app and the owner's answer goes back to the runner. Both halves live in every daemon (a daemon is a
 * runner of its own orchestrator and an orchestrator of its own runners); neither names a harness.
 *
 * RUNNER side ([runnerObserver], [heldElsewhere]): a prompt of a session the orchestrator started ([RemoteStarts.isLinked]) goes on
 * that session's stream as a [RunnerLinkWire.PROMPT_FRAME] (link-only), and its close as a [RunnerLinkWire.PROMPT_RESOLVED_FRAME]. It
 * runs no timer of its own while the orchestrator is reachable; [PendingPrompts.expireHeld] applies `prompts.onTimeout` once the
 * orchestrator read nothing for a whole timer.
 *
 * ORCHESTRATOR side ([applyFrame], [retry]): a pushed prompt frame opens the same prompt in this daemon's [prompts] (every client shows
 * the card; the same `prompt_response` answers it; the same rules apply), and its answer goes to the runner with
 * [RunnerLink.answerPrompt]. An answer the runner could not be reached for is kept and sent again by [retry] until the runner takes
 * it or reports the prompt closed. The close frame closes the card here.
 *
 * RUNNER-UNIFY-6: the frames are the only path (RUNNER-UNIFY-5 put them on the stream; the outbox prompt events are gone).
 */
class RunnerPromptRelay(
    private val prompts: PendingPrompts,
    /** The link to a runner by name (the pool's). */
    private val link: (runner: String) -> RunnerLink?,
    /** The project of a session placed on a runner (the start's), for "Always allow" rules. */
    private val projectOf: (sessionId: String) -> String?,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * RUNNER-UNIFY-5: where the cards relayed here and not settled yet are kept, so a restart of this daemon reopens them ([restore]).
     * The runner holds such a prompt with no timer while this daemon is reachable, and it never sends a frame this daemon already
     * took again: without this file a card open at a restart strands its session. Null = kept in memory only (tests).
     */
    private val store: File? = null,
) {
    private data class Pending(val runner: String, val sessionId: String, val reply: PromptReply)

    /** Answers not delivered yet, by prompt id. */
    private val undelivered = ConcurrentHashMap<String, Pending>()

    /** RUNNER-UNIFY-5: a relayed card not settled yet (still open here, or answered and not delivered to the runner yet). */
    @Serializable
    private data class Held(val promptId: String, val runner: String, val sessionId: String, val payload: String)

    private val held = ConcurrentHashMap<String, Held>()

    /**
     * RUNNER-UNIFY-5 (orchestrator side): one frame a runner PUSHED on [sessionId]'s stream. A prompt frame opens the card and its
     * close closes it, through the same prompt store as a local session's. True = it was a prompt frame (consumed); any other frame
     * type is not this relay's.
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
        val id = request.promptId.trim()
        if (id.isNotEmpty() && held.putIfAbsent(id, Held(id, runner, sessionId, payload)) == null) save()
        // UNDISPATCHED: the card is open in the store before this returns, so a close read right after it (the same batch) finds it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val reply = runCatching { prompts.relay(request, p.input, projectOf(sessionId)) }
                .onFailure { log("[infantry] runner $runner: prompt relay failed: ${it.message}") }.getOrNull()
            if (reply == null) {
                // Closed by the runner, or this very card was already shown (a replay): it is settled only when it is not open here.
                if (prompts.snapshot().none { it.promptId.trim() == id }) settled(id)
                return@launch
            }
            deliver(Pending(runner, sessionId, reply))
        }
    }

    private fun close(payload: String) {
        val frame = runCatching { json.decodeFromString(PromptResolved.serializer(), payload) }.getOrNull() ?: return
        undelivered.remove(frame.promptId)
        settled(frame.promptId.trim())
        prompts.closeRelayed(frame)
    }

    /**
     * RUNNER-UNIFY-5 (orchestrator side): reopen the cards that were open (or answered and not delivered) when this daemon stopped,
     * for sessions still placed on a runner ([placed]). The card gets the runner's timer again from now. A prompt the runner closed
     * meanwhile is closed by its close frame, which this daemon had not taken yet and so reads after the restart; a card of a session
     * no longer placed is dropped. Returns how many were reopened.
     */
    fun restore(placed: (sessionId: String) -> Boolean): Int {
        val f = store?.takeIf { it.isFile } ?: return 0
        val kept = runCatching { json.decodeFromString(ListSerializer(Held.serializer()), f.readText()) }.getOrElse {
            log("[infantry] relayed prompts not restored: ${it.message}"); return 0
        }
        var n = 0
        for (h in kept) {
            if (!runCatching { placed(h.sessionId) }.getOrDefault(false)) continue
            open(h.runner, h.sessionId, h.payload)
            n++
        }
        if (n != kept.size) save()
        if (n > 0) log("[infantry] reopened $n relayed prompt card(s) still open on their runner")
        return n
    }

    private fun settled(promptId: String) {
        if (held.remove(promptId) != null) save()
    }

    @Synchronized
    private fun save() {
        val f = store ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val text = json.encodeToString(ListSerializer(Held.serializer()), held.values.sortedBy { it.promptId })
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            // Windows refuses to replace a file another handle has open (a reader, an indexer): a stale file would reopen an answered
            // card at the next restart, so fall back to a plain replace, then to writing the file in place.
            runCatching {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                f.writeText(text)
                tmp.delete()
            }
        }.onFailure { log("[infantry] relayed prompts not saved: ${it.message}") }
    }

    /** ORCHESTRATOR: send every answer a runner could not be reached for again (the daemon calls it every heartbeat round). */
    fun retry(): Int = undelivered.values.toList().count { deliver(it) }

    private fun deliver(p: Pending): Boolean {
        val l = link(p.runner)
        when (val r = l?.answerPrompt(p.sessionId, p.reply) ?: OrchestratorReply.Unavailable("runner '${p.runner}' is not paired")) {
            is OrchestratorReply.Ok -> {
                undelivered.remove(p.reply.promptId)
                settled(p.reply.promptId.trim())
                return true
            }
            is OrchestratorReply.Unavailable -> {
                // A refused answer (the runner had no such prompt any more, or the session is not ours) is not retried for ever: the
                // runner's own close frame, or the session's end, closes the card.
                if (undelivered.put(p.reply.promptId, p) == null) log("[infantry] runner ${p.runner}: prompt answer not delivered yet (${r.reason}); retried each heartbeat")
                return false
            }
        }
    }

    /** RUNNER: whether [sessionId]'s prompts are answered by the orchestrator (it started the session here). */
    fun heldElsewhere(starts: RemoteStarts): (String) -> Boolean = starts::isLinked

    /**
     * RUNNER: the prompts of the orchestrator's sessions ([RemoteStarts.isLinked]), and their close, go on the session's stream as
     * [RunnerLinkWire.PROMPT_FRAME] / [RunnerLinkWire.PROMPT_RESOLVED_FRAME] through [publish] (the stream sequencer, link-only), so the
     * orchestrator reads them with the session's pushed frames.
     */
    fun runnerObserver(starts: RemoteStarts, publish: (Envelope) -> Unit = {}): PromptObserver = object : PromptObserver {
        override fun opened(request: PromptRequest, fullInput: String?) {
            val payload = RunnerPromptEvent(json.encodeToString(PromptRequest.serializer(), request), fullInput).encode()
            if (starts.isLinked(request.sessionId)) runCatching { publish(Envelope(RunnerLinkWire.PROMPT_FRAME, payload, request.sessionId)) }
        }

        override fun resolved(request: PromptRequest, frame: PromptResolved) {
            // A relayed card closing on the orchestrator is not a runner prompt: only the runner's own sessions are reported.
            if (frame.resolvedBy.startsWith(PendingPrompts.RUNNER_RESOLVED)) return
            val payload = json.encodeToString(PromptResolved.serializer(), frame)
            if (starts.isLinked(request.sessionId)) runCatching { publish(Envelope(RunnerLinkWire.PROMPT_RESOLVED_FRAME, payload, request.sessionId)) }
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
