package com.superiorsoftllc.agenthq.harness.api

import java.io.File

/**
 * HARNESS-PLUGIN-3 H3 (docs/HARNESS-PLUGINS.md section 3.1): the harness plugin contract, version 2.
 *
 * A harness plugin is a jar in a module folder (module.json `harness` block). It compiles against THIS module only
 * (`compileOnly`), lists its [HarnessPlugin] in `META-INF/services/com.superiorsoftllc.agenthq.harness.api.HarnessPlugin`,
 * and is loaded by the daemon in its own class loader, which sees `java.*`, `kotlin.*`, kotlinx and this package, nothing
 * else of the daemon.
 *
 * A harness here is DECLARATIVE about its process: it says which command to run for a request ([Harness.process]) and how
 * to read the lines that command prints ([Harness.newTranslator]). The daemon launches, sandboxes, gates (policy, Demo_Lock
 * seats, spawn gates), watches and kills that process exactly as it does a built-in harness's; the plugin never holds the
 * agent's process.
 *
 * Versioning: [HARNESS_API_VERSION] is semver. A plugin states the version it was BUILT against ([HarnessPlugin.contractVersion],
 * no default, so the const is inlined into the plugin). The daemon loads a plugin of the same MAJOR whose MINOR is not newer
 * than its own; a minor bump may only ADD members with a default, never change one. Everything else is a major.
 *
 * 2.0.0: first version (one-shot CLI harnesses: process + translator; [HarnessHost] services). The 1.x contract
 * (the daemon's own `harness.HarnessPlugin`, exact-match handshake) stays for the built-in harnesses on the daemon's
 * class path until they move to modules (steps H6-H8).
 *
 * 2.1.0 (CONTEXT-LIMIT-1, additive): [HarnessRequest.contextLimit] and [Harness.contextLimit], a per-session context limit
 * in tokens and how far this harness can honour it. A 2.0.0 plugin keeps [ContextLimitSupport.UNSUPPORTED] (the default),
 * so a limit set on its session is reported as not applied, never silently dropped (docs/CONTEXT-LIMIT.md).
 */
const val HARNESS_API_VERSION: String = "2.1.0"

/** What a harness jar ships: registers its harnesses, given the [HarnessHost] scoped to its module. */
interface HarnessPlugin {
    /** Must equal the module's name (module.json `name`, e.g. `Acme_EchoHarness`); the daemon refuses any other. */
    val pluginId: String

    /** The [HARNESS_API_VERSION] this plugin was built against: `override val contractVersion = HARNESS_API_VERSION`. No default on purpose. */
    val contractVersion: String

    /** Register every harness this plugin provides. Called once, after the version check. Each id must be declared in module.json `harness.ids`. */
    fun register(registrar: HarnessRegistrar)

    /** Release what the plugin holds. Called once, after its harnesses were removed (module disabled, daemon stopping). */
    fun unload() {}
}

/** Only valid during [HarnessPlugin.register]; a later call fails. */
interface HarnessRegistrar {
    /** The daemon services this plugin may call, scoped to its module's declarations and grants. Keep it for later use. */
    val host: HarnessHost

    fun register(harness: Harness)
}

interface Harness {
    /** The id a session, role, ticket or routing rule stores (`claude`, `pi`, `echo`...). Stable forever: renaming one is a data migration. */
    val id: String

    val capabilities: Set<HarnessCapability>

    /** The command this harness runs for [request]. The prompt reaches it on stdin (then stdin is closed); it must print to stdout. */
    fun process(request: HarnessRequest): HarnessProcess

    /** A fresh translator for one run: reads the process's stdout lines. */
    fun newTranslator(): HarnessTranslator

    /**
     * 2.1.0: how this harness honours [HarnessRequest.contextLimit]. Default [ContextLimitCapability.UNSUPPORTED]: the
     * daemon then never passes a limit and reports it as not applied. Declare [ContextLimitSupport.SUPPORTED] only when
     * [process] maps the limit to a mechanism the CLI enforces (a flag, an env var), [ContextLimitSupport.BEST_EFFORT]
     * when it is approximated (a soft threshold, a check between turns).
     */
    val contextLimit: ContextLimitCapability get() = ContextLimitCapability.UNSUPPORTED
}

/**
 * 2.1.0 (CONTEXT-LIMIT-1): how a harness honours a context limit. Ordered from strongest to none.
 *  - [SUPPORTED]: the CLI itself keeps the conversation at or under the limit (compacts or caps at it);
 *  - [BEST_EFFORT]: approximated (e.g. a compaction run between turns once the limit is passed: one turn can exceed it);
 *  - [UNSUPPORTED]: the harness has no way to apply it; a limit set on such a session is recorded and reported, not applied.
 */
enum class ContextLimitSupport { SUPPORTED, BEST_EFFORT, UNSUPPORTED }

/** [support] plus, in words, HOW the harness applies the limit (or why it cannot), shown next to the option to the user. */
data class ContextLimitCapability(val support: ContextLimitSupport, val how: String = "") {
    companion object {
        val UNSUPPORTED = ContextLimitCapability(ContextLimitSupport.UNSUPPORTED, "this harness declares no context limit mapping")
    }
}

/** One process to launch. [workingDir] null = the session's working directory. [env] is ADDED to the session's environment. */
data class HarnessProcess(
    val command: List<String>,
    val workingDir: File? = null,
    val env: Map<String, String> = emptyMap(),
)

/** One run. [model] / [sessionId] / [effort] are only set when the harness declares the matching capability. */
data class HarnessRequest(
    val prompt: String,
    val harnessId: String,
    /** The session's working directory (a project folder), possibly empty. */
    val project: String,
    val model: String? = null,
    /** The harness's own session id from an earlier [HarnessEvent.SessionStarted], to resume. */
    val sessionId: String? = null,
    val effort: String? = null,
    val toolApproval: ToolApproval = ToolApproval.DENY,
    /**
     * 2.1.0: the session's context limit in tokens (the most context its conversation may hold before the harness compacts or
     * caps it). Null = no limit (the harness's own default). Only set when [Harness.contextLimit] is not UNSUPPORTED.
     */
    val contextLimit: Int? = null,
)

/** How freely the run's tools may act without a human: the harness maps it to its CLI's own permission flags. */
enum class ToolApproval { DENY, ALLOW_EDITS, ALLOW_ALL, ASK }

enum class HarnessCapability { MODEL_SELECTION, SESSION_RESUME, TOOL_CALLS, REASONING, USAGE, EFFORT_SELECTION }

/** Neutral events a translator produces. A run ends with exactly one [Completion] or [Error] (from [HarnessTranslator.terminal]). */
sealed interface HarnessEvent {
    data class SessionStarted(val sessionId: String) : HarnessEvent
    data class TextDelta(val text: String) : HarnessEvent
    data class ToolCall(val callId: String, val name: String, val arguments: String) : HarnessEvent
    data class ToolResult(val callId: String, val output: String, val isError: Boolean = false) : HarnessEvent
    data class Reasoning(val text: String) : HarnessEvent
    data class Usage(val inputTokens: Long, val outputTokens: Long) : HarnessEvent
    data class Completion(val reason: String = "stop") : HarnessEvent
    data class Error(val message: String) : HarnessEvent
}

/** The only place a harness's wire schema is known. One instance per run. */
interface HarnessTranslator {
    fun translate(line: String): List<HarnessEvent>
    fun terminal(exitCode: Int, stderrTail: String): HarnessEvent
}
