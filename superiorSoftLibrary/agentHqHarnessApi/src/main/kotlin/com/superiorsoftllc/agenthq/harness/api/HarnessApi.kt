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
 *
 * 2.2.0 (EFFORT-1, additive): [Harness.effort], how this harness honours [HarnessRequest.effort] (the reasoning effort level,
 * one of low / medium / high / xhigh / max). Before 2.2.0 the request field existed but a harness could not say whether it
 * applies it. A 2.0 / 2.1 plugin keeps [EffortSupport.UNSUPPORTED] (the default): an effort set on its session is reported as
 * not applied and never passed (docs/EFFORT.md).
 *
 * 2.3.0 (HARNESS H5b, additive): [HarnessEvent.Denial], a tool the harness refused to run because the run's tool approval did not
 * allow it. The host shows it as the same chat notice a built-in harness's denial gets. A 2.0 - 2.2 plugin never emits it and is unaffected.
 *
 * 2.4.0 (HARNESS H5c, additive): [Harness.usageSource], a plan/quota poll whose rows join the daemon's usage frame tagged by the harness id.
 * Needs module.json `harness.hooks` "usage" (and, for a community module, the `harness.hook.usage` grant).
 */
const val HARNESS_API_VERSION: String = "2.4.0"

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

    /**
     * 2.2.0: how this harness honours [HarnessRequest.effort]. Default [EffortCapability.UNSUPPORTED]: the daemon then never
     * passes an effort and reports it as not applied. Declare SUPPORTED only when [process] maps the level to a mechanism
     * the CLI applies (a flag, an env var).
     */
    val effort: EffortCapability get() = EffortCapability.UNSUPPORTED

    /** 2.4.0: a plan/quota poll for this harness, or null (default). The host calls [HarnessUsageSource.read] off its own threads, with a timeout; a throw or a timeout is isolated and attributed to this plugin. */
    fun usageSource(): HarnessUsageSource? = null
}

/** 2.4.0: one harness's plan limits. [read] may block on I/O (the host bounds it with a timeout) and may throw. */
fun interface HarnessUsageSource {
    fun read(nowMs: Long): HarnessUsageReading
}

/** [windows] empty = no signal (the host shows the harness as unknown, never 0%). [reason] is free text for logs only. */
data class HarnessUsageReading(val windows: List<HarnessUsageWindow>, val reason: String? = null)

/** One limit window: [percent] used (0-100, null = unknown), [resetsAt] epoch millis (null = unknown). */
data class HarnessUsageWindow(val label: String, val windowMinutes: Int, val percent: Double?, val resetsAt: Long? = null)

/**
 * 2.2.0 (EFFORT-1): how a harness honours a reasoning effort. Same states as [ContextLimitSupport].
 *  - [SUPPORTED]: the CLI takes the level through a flag or env var and applies it;
 *  - [BEST_EFFORT]: mapped, but the CLI may ignore or clamp it (e.g. model dependent);
 *  - [UNSUPPORTED]: no way to apply it; an effort set on such a session is recorded and reported, not applied.
 */
enum class EffortSupport { SUPPORTED, BEST_EFFORT, UNSUPPORTED }

/** [support] plus, in words, HOW the harness applies the level (or why it cannot), shown next to the option to the user. */
data class EffortCapability(val support: EffortSupport, val how: String = "") {
    companion object {
        val UNSUPPORTED = EffortCapability(EffortSupport.UNSUPPORTED, "this harness declares no effort mapping")
    }
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
    /** 2.3.0: [tool] was refused by the harness (approval did not allow it); [reason] is the harness's own words, for logs. Not terminal. */
    data class Denial(val tool: String, val reason: String = "") : HarnessEvent
}

/** The only place a harness's wire schema is known. One instance per run. */
interface HarnessTranslator {
    fun translate(line: String): List<HarnessEvent>
    fun terminal(exitCode: Int, stderrTail: String): HarnessEvent
}
