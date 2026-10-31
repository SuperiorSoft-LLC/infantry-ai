package com.superiorsoftllc.agenthq.harness

import kotlinx.coroutines.flow.Flow

/**
 * HARNESS 1/4 - the harness contract. A harness is what turns a [HarnessRequest] into a STREAM of
 * typed [HarnessEvent]s. No harness is implemented here, no registry, no discovery.
 *
 * Third parties implement [Harness] against this file only. Bump [HARNESS_CONTRACT_VERSION] on any
 * change a plugin could observe (HARNESS 2/4 checks a plugin against it).
 *
 * 1.1.0 (HARNESS 4/4, additive): [HarnessEvent.SessionStarted] and [HarnessEvent.ToolResult]. Contract 1.0.0 had no
 * way to hand a run's session id back to the caller (so [HarnessCapability.SESSION_RESUME] only worked for a caller
 * that already held an id) and no way to report what a tool returned. Both gaps were found on the first harness and
 * confirmed on the second, so they are properties of the seam, not quirks of one vendor.
 *
 * 1.2.0 (CP-4, additive): [Harness.commands], the harness's command/option catalog (model, effort, compact...). A harness
 * that declares nothing offers no commands; the daemon serves the catalog to clients, it names no vendor.
 *
 * 1.3.0 (CP-4 slice d, additive): [HarnessRequest.effort] and [HarnessCapability.EFFORT_SELECTION], so `/effort` can set
 * the reasoning effort of a session's next run. A request that names an effort needs the capability, like a model does.
 *
 * 1.4.0 (BUG-M2-11b-1, additive): [CliHarness.sessionAdapter], the harness's persistent-mode protocol for the daemon's
 * session host (docs/AGENTHQ-SESSION-HOST-PLAN.md). Null by default = one-shot. Bumped because the handshake is exact-match.
 *
 * 1.5.0 (BUG-PERM-1, additive): [HarnessRequest.toolApproval], the run's resolved [ToolApproval]. A harness maps it to its
 * own permission mechanism; one that cannot honour a mode keeps its current behaviour (deny). Same value reaches a
 * [com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter] as `RunRequest.toolApproval`.
 *
 * 1.6.0 (HARNESS-CARRY-1, additive): [CliHarness.conversationCarry], how the harness takes over a conversation begun on
 * another harness (a provider switch). Default [ConversationCarry.REPLAY]: the history as the first run's opening context.
 *
 * 1.7.0 (CONTEXT-LIMIT-1, additive): [HarnessRequest.contextLimit] and [Harness.contextLimit], the session's context limit
 * in tokens and how this harness honours it (docs/CONTEXT-LIMIT.md). Default UNSUPPORTED: the limit is reported, not applied.
 *
 * 1.8.0 (EFFORT-1, additive): [Harness.effort], how this harness honours [HarnessRequest.effort] (docs/EFFORT.md). Default
 * UNSUPPORTED: an effort set on the session is recorded and reported as not applied.
 */
const val HARNESS_CONTRACT_VERSION: String = "1.8.0"

/** One run: prompt in. [model] / [sessionId] are optional and require the matching capability. */
data class HarnessRequest(
    val prompt: String,
    val harnessId: String,
    val project: String,
    val model: String? = null,
    val sessionId: String? = null,
    /** The reasoning effort for this run, one of the harness's own levels ([HarnessCommand.values]); needs [HarnessCapability.EFFORT_SELECTION]. */
    val effort: String? = null,
    /** BUG-PERM-1 (1.5.0): how freely this run's tools may run without approval; resolved by the daemon, never null. */
    val toolApproval: ToolApproval = ToolApproval.DENY,
    /** CONTEXT-LIMIT-1 (1.7.0): the session's context limit in tokens, null = none. Only passed to a harness whose [Harness.contextLimit] is not UNSUPPORTED. */
    val contextLimit: Int? = null,
)

/**
 * CONTEXT-LIMIT-1: how a harness honours a context limit (mirror of the harness API 2.1 type, docs/CONTEXT-LIMIT.md).
 *  - [SUPPORTED]: the CLI itself keeps the conversation at or under the limit;
 *  - [BEST_EFFORT]: approximated (e.g. compacted between turns, so one turn can pass it);
 *  - [UNSUPPORTED]: not applied; a limit set on the session is recorded and reported as not applied.
 */
enum class ContextLimitSupport {
    SUPPORTED, BEST_EFFORT, UNSUPPORTED;

    /** The wire spelling (`supported` / `best_effort` / `unsupported`). */
    val wire: String get() = name.lowercase()
}

/** [support] plus, in words, how the harness applies the limit or why it cannot. */
data class ContextLimitCapability(val support: ContextLimitSupport, val how: String = "") {
    val applies: Boolean get() = support != ContextLimitSupport.UNSUPPORTED

    companion object {
        val UNSUPPORTED = ContextLimitCapability(ContextLimitSupport.UNSUPPORTED, "this harness declares no context limit mapping")
    }
}

/** EFFORT-1: how a harness honours a reasoning effort (mirror of the harness API 2.2 type, docs/EFFORT.md). Same three states as the context limit. */
enum class EffortSupport {
    SUPPORTED, BEST_EFFORT, UNSUPPORTED;

    /** The wire spelling (`supported` / `best_effort` / `unsupported`). */
    val wire: String get() = name.lowercase()
}

/** [support] plus, in words, how the harness applies an effort level or why it cannot. */
data class EffortCapability(val support: EffortSupport, val how: String = "") {
    val applies: Boolean get() = support != EffortSupport.UNSUPPORTED

    companion object {
        val UNSUPPORTED = EffortCapability(EffortSupport.UNSUPPORTED, "this harness declares no effort mapping")
    }
}

/**
 * Typed events out. Order matters: a run ends with exactly one [Completion] or [Error].
 *
 * Granularity is NOT part of the contract: a [TextDelta] / [Reasoning] may carry one token or a whole block, and
 * a run may emit many in a row. A consumer appends them. See [HarnessParity] for the one declared divergence.
 */
sealed interface HarnessEvent {
    /** The harness's own id for this run's session, emitted at most once and before any other event of the run. A caller keeps it to pass back as [HarnessRequest.sessionId]. */
    data class SessionStarted(val sessionId: String) : HarnessEvent
    data class TextDelta(val text: String) : HarnessEvent
    data class ToolCall(val callId: String, val name: String, val arguments: String) : HarnessEvent
    /** What the tool named by [callId] returned. Always after the [ToolCall] with the same id. [output] is the text the tool produced. */
    data class ToolResult(val callId: String, val output: String, val isError: Boolean = false) : HarnessEvent
    data class Reasoning(val text: String) : HarnessEvent
    data class Usage(val inputTokens: Long, val outputTokens: Long) : HarnessEvent
    data class Completion(val reason: String = "stop") : HarnessEvent
    data class Error(val message: String) : HarnessEvent
}

enum class HarnessCapability { MODEL_SELECTION, SESSION_RESUME, TOOL_CALLS, REASONING, USAGE, EFFORT_SELECTION }

/** A capability the request needs but the harness lacks. Never resolved to a default. */
class MissingCapabilityException(val capability: HarnessCapability, val harnessId: String) :
    UnsupportedOperationException("harness '$harnessId' does not implement capability $capability")

interface Harness {
    val id: String

    /** The contract version this harness was built against. The const is inlined at the plugin's compile time. */
    val contractVersion: String get() = HARNESS_CONTRACT_VERSION

    val capabilities: Set<HarnessCapability>

    /**
     * The commands/options this harness offers a user at a `/` prompt (model, effort, compact...). Default: none.
     * Each is declared in terms of a [CommandAction], so the daemon can serve and execute the catalog without knowing
     * which vendor is behind it. The catalog is declarative: serving it executes nothing, and the daemon refuses to
     * execute a command whose action needs a capability the harness lacks.
     */
    val commands: List<HarnessCommand> get() = emptyList()

    /** CONTEXT-LIMIT-1 (1.7.0): how this harness honours [HarnessRequest.contextLimit]. Default: unsupported. */
    val contextLimit: ContextLimitCapability get() = ContextLimitCapability.UNSUPPORTED

    /** EFFORT-1 (1.8.0): how this harness honours [HarnessRequest.effort]. Default: supported when the harness declares EFFORT_SELECTION, else unsupported. */
    val effort: EffortCapability get() =
        if (HarnessCapability.EFFORT_SELECTION in capabilities) EffortCapability(EffortSupport.SUPPORTED, "the harness declares EFFORT_SELECTION")
        else EffortCapability.UNSUPPORTED

    /** Implementors produce the event stream here. Must emit incrementally, never only at completion. */
    fun events(request: HarnessRequest): Flow<HarnessEvent>

    /** Callers use this. Fails loudly (eagerly, at call time) if the request needs a capability the harness lacks. */
    fun run(request: HarnessRequest): Flow<HarnessEvent> {
        if (request.model != null) requireCapability(HarnessCapability.MODEL_SELECTION)
        if (request.sessionId != null) requireCapability(HarnessCapability.SESSION_RESUME)
        if (request.effort != null) requireCapability(HarnessCapability.EFFORT_SELECTION)
        return events(request)
    }

    fun requireCapability(capability: HarnessCapability) {
        if (capability !in capabilities) throw MissingCapabilityException(capability, id)
    }
}
