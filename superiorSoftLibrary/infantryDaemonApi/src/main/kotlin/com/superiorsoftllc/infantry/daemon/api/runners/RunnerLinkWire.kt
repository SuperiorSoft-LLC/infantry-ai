// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.runners

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RUNNER-POOL-5 / RUNNER-POOL-6 (1.45.0; docs/RUNNER-POOL.md 2.1 and 5): the runner link frame family (`runner_link_*`). Each
 * request is one `(type, data = JSON)` frame over the link connection; the answer is [REPLY] (data = the answer JSON) or [ERROR]
 * (data = the reason). The credential and the caller's id travel beside the frame (the transport carries them, never in a frame
 * body); [PAIR] is the only frame accepted without them.
 *
 * It lives in the daemon API because it is the wire contract of TWO parties: core serves it on the runner (`runners/`), the
 * Infantry_RemoteRunner plugin's binding speaks it on the orchestrator. Machines on different app versions exchange [CONTRACT] by
 * [HELLO]; later steps add operations as minor bumps (docs/RUNNER-POOL.md section 10). Every field added later has a default, so an
 * older peer's frame still decodes ([json] ignores unknown keys).
 */
object RunnerLinkWire {
    /**
     * 1.1.0 (RUNNER-POOL-6): hello carries what the runner can run; health, deliver, events, transcript; a start may carry a git remote.
     * 1.2.0 (RUNNER-POOL-7): [FORWARD_PULL] / [FORWARD_ANSWER], the agent frames a runner forwards to its orchestrator.
     * 1.3.0 (RUNNER-POOL-8): [EventsRequest.acked] (the outbox keeps an event until acknowledged, seq per session);
     * [StartRequest.pushOnCommit].
     * 1.4.0 (RUNNER-CHECK-1): [CHECK], the read-only health check (`contract`, harness sign-in, grants, git, disk), answered as
     * [CheckAnswer] rows with the fix for each failing one.
     * 1.5.0 (RUNNER-TEST-1): [REMOVE], forget a session this orchestrator started (the turn `infantry runner test` ran leaves nothing
     * behind on the runner's session list). A runner that does not know it answers an error, and the caller stops at [STOP].
     * 1.6.0 (RUNNER-GRANT-AT-PAIR-1): [PairRequest.grantProjects] / [PairRequest.grantHarnesses] and [PairAnswer.grantedProjects] /
     * [PairAnswer.grantedHarnesses] / [PairAnswer.grantRefusal]: a code issued on the runner console with `--grant-on-pair` may
     * grant at most one project and installed harnesses, once, at pairing (owner decision D24). An older runner ignores the request
     * fields (defaults) and grants nothing; an older orchestrator sends none.
     * 1.7.0 (RUNNER-PROMPT-RELAY-1): [PROMPT_ANSWER], and the `prompt` / `prompt_resolved` event kinds: a session on the runner that asks
     * a human (a tool approval, a question) is shown in the orchestrator's app, and the owner's answer comes back with this frame.
     * 1.8.0 (RUNNER-CRED-1): [StartRequest.credentials], what the orchestrator lends the session (harness key env, the secrets it
     * may render, the git credential of its remote), held in the runner's memory for that session only. An older runner ignores it
     * (and needs its own keys, as before); an older orchestrator sends none.
     */
    const val CONTRACT = "1.8.0"

    /**
     * RUNNER-PROMPT-RELAY-1 (contract 1.7.0): the orchestrator's answer to a prompt a session of its own raised on this runner (the
     * `prompt` event). Only a session this orchestrator started may be answered; [PromptAnswerAck.applied] false = it was no longer open.
     */
    const val PROMPT_ANSWER = "runner_link_prompt_answer"

    const val PAIR = "runner_link_pair"
    const val HELLO = "runner_link_hello"
    const val REKEY = "runner_link_rekey"
    const val DETACH = "runner_link_detach"
    const val START = "runner_link_start"
    const val STOP = "runner_link_stop"

    /**
     * RUNNER-TEST-1 (contract 1.5.0): forget a session this orchestrator started, so a throwaway turn leaves nothing on the runner's
     * session list. The runner ends it first when it is still live (the owner's `session_remove --force` rule), so one frame does
     * both; only a session of this orchestrator may be removed.
     */
    const val REMOVE = "runner_link_remove"
    const val HEALTH = "runner_link_health"
    const val DELIVER = "runner_link_deliver"
    const val EVENTS = "runner_link_events"
    const val TRANSCRIPT = "runner_link_transcript"

    /**
     * RUNNER-CHECK-1 (docs/wiki/RunnerPairing-design.md 4, step 4): the orchestrator's health check of one runner, answered by
     * [CheckAnswer]. Read only: nothing is started, cloned or written.
     */
    const val CHECK = "runner_link_check"

    /**
     * RUNNER-POOL-7 (docs/RUNNER-POOL.md 2.3 b): the orchestrator DIALS the runner, so the runner's forwarded agent frames travel back
     * on the orchestrator's calls: [FORWARD_PULL] waits up to [ForwardPullRequest.waitMs] for frames its workers sent, and
     * [FORWARD_ANSWER] hands back the answer of one of them. Nothing listens on the orchestrator.
     */
    const val FORWARD_PULL = "runner_link_forward_pull"
    const val FORWARD_ANSWER = "runner_link_forward_answer"
    const val REPLY = "runner_link_reply"
    const val ERROR = "runner_link_error"

    /**
     * RUNNER-GRANT-AT-PAIR-1 (1.6.0): the code, and, when the owner confirmed it, the grants this orchestrator asks the runner to
     * apply ONCE at pairing. The runner trusts only its own record of the code (`--grant-on-pair`), never these fields, and it adds
     * ids to `runner.projects` / `runner.harnesses` (never replaces them). A `*` in either list is refused; at most
     * `limits.runners.grantProjectsMax` project ids are accepted.
     */
    @Serializable
    data class PairRequest(
        val code: String,
        val orchestratorId: String,
        val orchestratorName: String = "",
        val grantProjects: List<String> = emptyList(),
        val grantHarnesses: List<String> = emptyList(),
    )

    /**
     * [grantedProjects] / [grantedHarnesses] are the ids this pairing actually granted (empty = nothing). [grantRefusal] is why a
     * requested grant was not applied: the code was not issued with `--grant-on-pair`, so grants stay the runner's own business.
     */
    @Serializable
    data class PairAnswer(
        val runnerId: String,
        val runnerName: String,
        val credential: String,
        val contract: String = CONTRACT,
        val grantedProjects: List<String> = emptyList(),
        val grantedHarnesses: List<String> = emptyList(),
        val grantRefusal: String? = null,
    )

    /** One harness on the runner: installed, its models, signed in or not (a yes/no, never the credential). */
    @Serializable
    data class HarnessWire(val id: String, val models: List<String> = emptyList(), val loggedIn: Boolean = false)

    /** [harnesses], [projects] (the runner's `runner.projects`), [capacity] (0 = no cap) and [os]: what this runner can run. */
    @Serializable
    data class HelloAnswer(
        val runnerId: String,
        val runnerName: String,
        val contract: String = CONTRACT,
        val issuedAt: Long,
        val harnesses: List<HarnessWire> = emptyList(),
        val projects: List<String> = emptyList(),
        val capacity: Int = 0,
        val os: String = "",
    )

    @Serializable
    data class RekeyAnswer(val credential: String, val issuedAt: Long)

    /**
     * A remote start: the subset of a start spec an orchestrator may name. A runner path is never taken from the wire. With
     * [gitRemote] the runner clones (or fetches) that remote into its clone cache and cuts the ticket's worktree there
     * ([title], [base], [branch] = the branch the ticket already holds); [prompt] is the composed first turn. [envNames] are the
     * environment variable NAMES the session needs: the runner fills them from its own store. RUNNER-CRED-1: values cross only
     * in [credentials].
     */
    @Serializable
    data class StartRequest(
        val startId: String,
        val projectId: String? = null,
        val provider: String? = null,
        val model: String? = null,
        val role: String? = null,
        val agentId: String? = null,
        val effort: String? = null,
        val ticketId: String? = null,
        val contextLimit: Int? = null,
        val prompt: String? = null,
        val title: String? = null,
        val gitRemote: String? = null,
        val base: String? = null,
        val branch: String? = null,
        val envNames: List<String> = emptyList(),
        /** RUNNER-POOL-8: push the ticket branch after each worker commit as well as after each turn. */
        val pushOnCommit: Boolean = false,
        /**
         * RUNNER-GIT-IDENT-1 (additive): the git author/committer identity the worker commits with: the orchestrator's own (its
         * project checkout's `user.name` / `user.email`). The runner writes it into its clone unless its own `runner.gitName` /
         * `runner.gitEmail` override it. Null = the orchestrator has none (an older orchestrator sends neither).
         */
        val gitName: String? = null,
        val gitEmail: String? = null,
        /** RUNNER-PAIR-1 (additive): the orchestrator's resolved Tool approval mode for this start ([StartSpec.toolApproval]); null = an older orchestrator. */
        val toolApproval: String? = null,
        /** RUNNER-DOCKER-MATRIX-1 (additive): the orchestrator relays this session's prompts ([StartSpec.promptRelay]); false = an older one. */
        val promptRelay: Boolean = false,
        /**
         * RUNNER-CRED-1 (contract 1.8.0, additive): the credentials lent to this session; null = none (the runner uses its own).
         * The ONLY field of the link that carries secret values: it crosses only the pinned, authenticated link, and no party
         * logs it ([toString] names keys only).
         */
        val credentials: com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCredentials? = null,
    ) {
        override fun toString(): String = "StartRequest(startId=$startId, projectId=$projectId, provider=$provider, ticketId=$ticketId, credentials=$credentials)"
    }

    /**
     * [branch] / [base] are the ticket branch the runner cut or reused (null without a git remote). RUNNER-WORKTREE-PATH-1 (additive):
     * [worktreePath] is that branch's worktree on the runner's disk; an older runner omits it.
     */
    @Serializable
    data class StartAnswer(
        val sessionId: String? = null,
        val refusal: String? = null,
        val branch: String? = null,
        val base: String? = null,
        val worktreePath: String? = null,
    )

    @Serializable
    data class StopRequest(val sessionId: String, val reason: String? = null, val force: Boolean = false, val ticketId: String? = null)

    @Serializable
    data class StopAnswer(val stopped: Boolean)

    @Serializable
    data class RemoveRequest(val sessionId: String)

    /** [removed] is false when the runner no longer had the session (removed twice, or an older runner ignored it). */
    @Serializable
    data class RemoveAnswer(val removed: Boolean = false)

    /** RUNNER-PROMPT-RELAY-1: the owner's answer to prompt [promptId] of [sessionId], the fields of a `prompt_response`. */
    @Serializable
    data class PromptAnswerRequest(
        val sessionId: String,
        val promptId: String,
        val decision: String? = null,
        val answers: List<String> = emptyList(),
        val otherText: String? = null,
    )

    @Serializable
    data class PromptAnswerAck(val applied: Boolean = false)

    @Serializable
    data class HealthAnswer(val state: String, val running: Int = 0, val freeSlots: Int = -1, val load: Double? = null)

    /** One mail turn: [origin] is the wire's `MessageOrigin` JSON text, [messageIds] the mailbox ids folded into it. */
    @Serializable
    data class DeliverRequest(val sessionId: String, val text: String, val origin: String, val wakeReason: String, val messageIds: List<String> = emptyList())

    @Serializable
    data class DeliverAnswer(val kind: String, val reason: String = "")

    /**
     * RUNNER-POOL-8: [acked] maps a session to the highest seq the orchestrator applied: the runner drops those and answers every event
     * still kept. Empty = acknowledge nothing; then [sinceSeq] (an older orchestrator's read) filters the answer.
     */
    @Serializable
    data class EventsRequest(val sinceSeq: Long = 0, val acked: Map<String, Long> = emptyMap())

    /** One session event of a remote-started session: [kind] `status` (payload = the state name) or `finished` (payload = exit code). */
    @Serializable
    data class EventWire(val sessionId: String, val seq: Long, val kind: String, val payload: String = "")

    @Serializable
    data class EventsAnswer(val events: List<EventWire> = emptyList())

    @Serializable
    data class TranscriptRequest(val sessionId: String, val fromLine: Int = 0, val maxLines: Int = 0)

    @Serializable
    data class TranscriptAnswer(val lines: List<String> = emptyList())

    /**
     * RUNNER-CHECK-1: what to check. [projectId] and [harness] are the one the owner means to run there (a runner grants each id
     * separately); [gitRemote] is the project's `origin` the runner would clone; [contract] is the orchestrator's [CONTRACT], so a
     * runner that speaks another one can report the mismatch as a row instead of failing the whole call.
     */
    @Serializable
    data class CheckRequest(
        val projectId: String? = null,
        val harness: String? = null,
        val gitRemote: String? = null,
        val contract: String? = null,
        /** RUNNER-GIT-IDENT-1 (additive): the identity a start would carry ([StartRequest.gitName]), for the `git:identity` row. */
        val gitName: String? = null,
        val gitEmail: String? = null,
        /**
         * RUNNER-CRED-1 (additive): the harness ids whose API key the orchestrator lends this runner at each start, so a harness
         * that is installed but not signed in here is ready anyway (unless `runner.acceptCredentials` is false). Names only.
         */
        val lentKeys: List<String> = emptyList(),
    )

    /**
     * One checked thing: [id] is `contract`, `harness:<id>`, `grant:project`, `grant:harness`, `git`, `git:identity`, `disk` (runner side) or
     * `link`, `git:origin` (decided by the orchestrator); [detail] is what was found; [fix] is the exact command or step when it
     * failed (null when [ok]). A row id is stable: a client may switch on it.
     */
    @Serializable
    data class CheckRow(val id: String, val ok: Boolean, val detail: String, val fix: String? = null)

    @Serializable
    data class CheckAnswer(val rows: List<CheckRow> = emptyList())

    @Serializable
    data class ForwardPullRequest(val waitMs: Long = 0)

    /** One frame a worker of a link-started session sent: [id] is the runner's key for its answer, [sessionId] the agent session. */
    @Serializable
    data class ForwardFrameWire(val id: String, val sessionId: String, val type: String, val data: String = "", val requestId: String? = null)

    @Serializable
    data class ForwardPullAnswer(val frames: List<ForwardFrameWire> = emptyList())

    /** The answer to forwarded frame [id]: a frame ([type], [data], [sessionId]), none ([none]), or [unavailable] with the reason. */
    @Serializable
    data class ForwardAnswerRequest(
        val id: String,
        val type: String? = null,
        val data: String = "",
        val sessionId: String? = null,
        val none: Boolean = false,
        val unavailable: String? = null,
    )

    @Serializable
    data class ForwardAnswerAck(val delivered: Boolean)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/**
 * RUNNER-POOL-6 (1.45.0, additive): the orchestrator side's calls to its PAIRED runners, for a runner binding plugin. Core keeps the
 * runner's url, pinned fingerprint and pair credential (they never reach a plugin) and sends `(type, data)` over the loaded
 * [RunnerLinkTransport]. A daemon with no paired runner, or no transport, answers [NoRunnerLinks].
 */
interface RunnerLinks {
    /** The names of the runners this orchestrator is paired with, sorted. */
    fun paired(): List<String>

    /** One `(type, data)` frame to runner [runner]; throws [LinkFailure] when it is not paired or no answer arrives. */
    fun call(runner: String, type: String, data: String): LinkAnswer
}

object NoRunnerLinks : RunnerLinks {
    override fun paired(): List<String> = emptyList()
    override fun call(runner: String, type: String, data: String): LinkAnswer = throw LinkFailure("no runner link on this daemon")
}
