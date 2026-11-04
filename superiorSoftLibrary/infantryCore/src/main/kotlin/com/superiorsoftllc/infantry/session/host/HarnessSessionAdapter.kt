// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.prompts.Decision
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessRunner

/*
 * BUG-M2-11b-1: the SessionHost seam (docs/INFANTRY-SESSION-HOST-PLAN.md section 4). The host owns a session's process
 * lifecycle for EVERY harness; an adapter owns one harness's protocol and nothing else. No type in this file names a
 * harness.
 */

/** How a turn ended. A crash or a failed launch is FAILED; only the host's OWN abort makes INTERRUPTED. */
enum class TurnEnd { COMPLETED, FAILED, INTERRUPTED }

/**
 * The one lifecycle setting (plan section 8), read on every decision, never cached. 0 = keep-alive off: every harness
 * runs one-shot, still through the host. N > 0 = a session's process lives between turns and the host stops it after N
 * idle minutes (BUG-M2-11f, [SessionHost.stopIdle]).
 */
data class LifecyclePolicy(val keepAliveMinutes: Int) {
    init {
        require(keepAliveMinutes in 0..MAX_KEEP_ALIVE_MINUTES) { "keepAliveMinutes must be 0..$MAX_KEEP_ALIVE_MINUTES" }
    }

    companion object {
        const val DEFAULT_KEEP_ALIVE_MINUTES = 30
        const val MAX_KEEP_ALIVE_MINUTES = 1440
        val DEFAULT = LifecyclePolicy(DEFAULT_KEEP_ALIVE_MINUTES)
    }
}

/** The adapter's own handle on what it launched. The host only reads pid / isAlive, subscribes onExit, kills. */
interface HostedProcess {
    /** Null = no OS process right now (a one-shot handle between turns, the stub). */
    val pid: Long?
    val isAlive: Boolean

    /** Once, when the OS process ends for any reason. A handle with no OS process never calls it. */
    fun onExit(handler: (exitCode: Int) -> Unit)

    fun killTree()
}

/** Where an adapter reports. Thread-safe. */
interface AdapterSink {
    /** A provider stdout line, VERBATIM (it becomes a raw `stdout` frame). */
    fun output(line: String)

    /** A provider stderr line, verbatim. */
    fun diagnostic(line: String)

    /** Plumbing for Activity; never a chat line. */
    fun detail(text: String)

    /** The provider's session id once known. */
    fun providerSession(id: String)

    /** Exactly once per sendTurn. */
    fun turnEnded(end: TurnEnd, exitCode: Int? = null, reason: String? = null)

    /** PROMPT-3: the daemon session this sink serves; what a prompt is filed under. Blank = not a daemon session. */
    val sessionId: String get() = ""

    /**
     * PROMPT-3: where an adapter relays a call its CLI wants a human for (Tool approval `ask`). The default cannot
     * prompt: every approval is denied, every question unanswered.
     */
    val prompts: com.superiorsoftllc.infantry.prompts.PromptAsker
        get() = com.superiorsoftllc.infantry.prompts.PromptAsker.CANNOT_PROMPT
    /**
     * PROMPT-4: ask the session's owner to approve one [tool] call ([input] = a short summary of it). BLOCKS until the
     * owner answers, the prompt times out or the session ends (all of which but an approval are [Decision.DENY]); an
     * interrupted caller gets [Decision.DENY] too. Call it off the adapter's event thread. Default: nobody can prompt.
     */
    fun requestApproval(tool: String, input: String): Decision = Decision.DENY
}

interface HarnessSessionAdapter {
    /** Named in the one chat line. */
    val label: String

    val bootTimeoutMs: Long get() = 60_000

    /** CLI-LIVE-1: idle minutes before the host stops a resting process; [global] is `session.keepAliveMinutes`. [Int.MAX_VALUE] = never. */
    fun idleMinutes(global: Int): Int = global

    /** A line protocol cannot run over a PTY (echo, CRLF, line limits), so it gets the host's piped runner. */
    val needsPipes: Boolean get() = true

    /** What a live process is bound to. A different value on the next turn = restart and resume. */
    fun fingerprint(request: RunRequest, env: Map<String, String>): String

    /** Start a process ready to take turns, through [runner]. May block up to [bootTimeoutMs]. */
    fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess

    /** Send one turn on a live process; its end comes back through [AdapterSink.turnEnded]. */
    fun sendTurn(process: HostedProcess, request: RunRequest)

    /** Ask the harness to stop the current turn and KEEP the process. false = it cannot; the host kills. */
    fun abortTurn(process: HostedProcess): Boolean

    /** Ask the process to exit. false = it has no graceful exit; the host kills at once. */
    fun close(process: HostedProcess): Boolean
}

/** Session-scoped callbacks, implemented once by the session manager. Never called while the host holds its lock. */
interface HostEvents {
    fun stdout(sessionId: String, line: String)
    fun stderr(sessionId: String, line: String)

    /** The ONE chat line per launch, plus the failure explanations. */
    fun bootLine(sessionId: String, text: String)
    fun activity(sessionId: String, detail: String)
    fun providerSession(sessionId: String, id: String)

    /** The session's live OS process, or none. */
    fun process(sessionId: String, pid: Long?)
    fun turnEnded(sessionId: String, end: TurnEnd, exitCode: Int?, processExited: Boolean, reason: String?)

    /** PROMPT-4: [AdapterSink.requestApproval] for [sessionId]. Blocking; default: nobody can prompt. */
    fun requestApproval(sessionId: String, tool: String, input: String): Decision = Decision.DENY

    /** A kept process died with no turn in flight. */
    fun processExited(sessionId: String, exitCode: Int)

    /** BUG-M2-11f: the host launched [kept], an OS process it keeps between turns; persist it so a restart can reap it. */
    fun keptProcess(sessionId: String, kept: KeptProcess) {}

    /** BUG-M2-11f: the kept process [pid] ended (any reason). Clear the record only if it still names that pid. */
    fun keptProcessEnded(sessionId: String, pid: Long) {}
}

/**
 * The ONLY place where "persistent or not" is decided (plan section 3.2). A harness with a persistent mode returns an
 * adapter from [AgentProvider.sessionAdapter]; one without gets [OneShotAdapter]; keep-alive off sends every harness
 * down the one-shot path. No harness is named here, by id or by type.
 */
fun selectAdapter(provider: AgentProvider, policy: LifecyclePolicy): HarnessSessionAdapter =
    if (policy.keepAliveMinutes == 0) OneShotAdapter(provider)
    else provider.sessionAdapter() ?: OneShotAdapter(provider)
