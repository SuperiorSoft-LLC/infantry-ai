package com.superiorsoftllc.infantry.harness.api

import java.io.File

/**
 * HARNESS-PLUGIN-3 H3 (docs/HARNESS-PLUGINS.md section 3.1): the harness plugin contract, version 2.
 *
 * A harness plugin is a jar in a module folder (module.json `harness` block). It compiles against THIS module only
 * (`compileOnly`), lists its [HarnessPlugin] in `META-INF/services/com.superiorsoftllc.infantry.harness.api.HarnessPlugin`,
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
 *
 * 2.5.0 (HARNESS H5d, additive): [Harness.stats], the harness's own session store read into the daemon's session stats.
 * Needs module.json `harness.hooks` "stats" (and, for a community module, the `harness.hook.stats` grant).
 *
 * 2.6.0 (HARNESS H5e, additive): [Harness.watch], the extra argv / env a harness needs so its tool calls run through the watched runner,
 * and files it writes in its own [HarnessHost.stateDir]. Needs module.json `harness.hooks` "watch" (and, for a community module, the `harness.hook.watch` grant).
 *
 * 2.7.0 (HARNESS H5f, additive): [Harness.sandbox], extra readable / writable dirs and env names for the session sandbox. The daemon still
 * builds and applies the sandbox and checks every path: a dir outside the module's own data folder and the session worktree is refused.
 * Needs module.json `harness.hooks` "sandbox" (and, for a community module, the `harness.hook.sandbox` grant).
 *
 * 2.8.0 (HARNESS H6a, additive): [Harness.session], a persistent line-protocol session (one process kept between turns, the daemon owns its lifecycle),
 * and [Harness.models], the harness's model listing. Need module.json `harness.hooks` "session" / "models" (and, for a community module, `harness.hook.session` / `harness.hook.models`).
 *
 * 2.9.0 (HARNESS H6c, additive): [HarnessEvent.Usage] carries the prompt-cache buckets ([HarnessEvent.Usage.cacheReadTokens] / [HarnessEvent.Usage.cacheWriteTokens],
 * default 0; the old two-argument constructor stays), so a plugin that reads the provider's cache split (pi) does not lose it. `resolveCli("node")` answers for a module that declares a CLI.
 *
 * 2.10.0 (HARNESS H7b-1, additive): three slots a harness fills so the daemon names no harness for them: [Harness.approvalArgs] (the argv a run's
 * [ToolApproval] adds), [Harness.commands] (the `/` command catalog, [HarnessCommandSpec]) and [Harness.mcpToolRule] (the allow rule for the
 * session's own MCP server). A 2.0 - 2.9 plugin keeps the defaults: no extra argv, no commands, no rule.
 *
 * 2.11.0 (HARNESS H8b-1, additive): [Harness.mergeConfig] (a harness that is configured by an inline config document merges the session's MCP
 * server, its tool-allow rule and the watch plugin into it) and [HarnessHost.queryDb] (a host-provided read-only query on a harness-local
 * sqlite file, behind the `stats` hook, so a plugin jar carries no database driver). A 2.0 - 2.10 plugin keeps the defaults.
 *
 * 2.12.0 (HARNESS H8b-2, additive): a persistent session whose protocol is not the process's own stdin/stdout (opencode serve: HTTP + server-sent
 * events). [SessionOutput.output] (a record as if the process had printed it: raw stream + translator), [SessionOutput.diagnostic],
 * [SessionOutput.requestApproval] (ask the owner about one tool call, blocking), [SessionConnection.onStderr] and [HarnessSession.connect] with the launched process. A 2.8 - 2.11 plugin keeps the defaults.
 */
const val HARNESS_API_VERSION: String = "2.12.0"

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

    /** 2.5.0: reads this harness's own session store for the daemon's session stats, or null (default). A throw is isolated and attributed. */
    fun stats(): HarnessStats? = null

    /**
     * 2.6.0: how this harness's tool calls are routed through the watched runner [launcher] for a session whose environment is [env]
     * (called only when watch is ON for that session), or null (default). A throw is isolated and attributed.
     */
    fun watch(env: Map<String, String>, launcher: String): WatchContribution? = null

    /**
     * 2.7.0: extra dirs / env names this harness needs inside the session sandbox, or null (default). [dirs] names the only roots a dir may sit under.
     * Anything outside them is refused by the daemon and never applied. A throw is isolated and attributed.
     */
    fun sandbox(dirs: SandboxRoots): SandboxContribution? = null

    /**
     * 2.8.0: a persistent mode, or null (default = the host runs [process] one-shot per turn). When set, the host keeps ONE process per session
     * between turns (it owns start, reuse, restart after a crash, idle stop and shutdown); the plugin owns only the line protocol.
     * Needs module.json `harness.hooks` "session".
     */
    fun session(): HarnessSession? = null

    /**
     * 2.8.0: the models this harness's `model` accepts, grouped for a picker, or null (default = a free-text model field). May block on I/O
     * (bounded by the host) and may throw (attributed). Needs module.json `harness.hooks` "models".
     */
    fun models(): List<HarnessModels>? = null

    /** 2.10.0: the argv [approval] adds to [process] (a permission-mode flag), or empty (default). The host appends it to every run and session launch. */
    fun approvalArgs(approval: ToolApproval): List<String> = emptyList()

    /** 2.10.0: the `/` commands this harness offers a user (model, effort, its own compact...), or empty (default). Declarative: serving it executes nothing. */
    fun commands(): List<HarnessCommandSpec> = emptyList()

    /**
     * 2.11.0: [existing] (the inline config document the harness's process would inherit, or null) with [parts] merged in, or null (default =
     * leave the config alone). Merge, never overwrite: every key the user set survives. Needs module.json `harness.hooks` "config". A throw is isolated and attributed.
     */
    fun mergeConfig(existing: String?, parts: ConfigParts): String? = null

    /** 2.10.0: the tool-allow rule that covers every tool of the session's own MCP server named [serverName], or null (default = none needed). */
    fun mcpToolRule(serverName: String): String? = null
}

/**
 * 2.11.0: what the daemon offers a harness to merge into its config: the session's own MCP server ([mcp], from [HarnessHost.mcpLaunch]; null =
 * none) and the installed watch plugin file ([watchPlugin]; null = watch is off or the harness has none).
 */
data class ConfigParts(val mcp: HarnessProcess? = null, val watchPlugin: File? = null)

/** 2.10.0: what the daemon does with a [HarnessCommandSpec]: set the next run's model, set its effort, or hand [HarnessCommandSpec.native] to the harness as the next turn. */
enum class HarnessCommandAction { SET_MODEL, SET_EFFORT, NATIVE }

/** 2.10.0: one `/` command: [name] is a bare word, [argument] a hint, [values] the accepted (or suggested) values, [native] the text for [HarnessCommandAction.NATIVE] only. */
data class HarnessCommandSpec(
    val name: String,
    val description: String,
    val action: HarnessCommandAction,
    val argument: String? = null,
    val values: List<String> = emptyList(),
    val native: String? = null,
)

/** 2.8.0: one picker group of model ids. [unavailable] = the harness cannot run anything right now ([note] says why). */
data class HarnessModels(
    val group: String,
    val label: String,
    val models: List<String>,
    val note: String? = null,
    val unavailable: Boolean = false,
)

/** 2.8.0: how a persistent turn ended. A crash or a failed launch is FAILED; only the host's own abort makes INTERRUPTED. */
enum class SessionTurnEnd { COMPLETED, FAILED, INTERRUPTED }

/** 2.8.0: the plugin's persistent-mode protocol for one harness. Stateless; per-process state lives in [SessionConnection]. */
interface HarnessSession {
    /** Named in the one chat line. */
    val label: String

    val bootTimeoutMs: Long get() = 60_000

    /** What a live process is bound to. A different value on the next turn = the host restarts the process and resumes. */
    fun fingerprint(request: HarnessRequest, env: Map<String, String>): String

    /** The persistent process (its stdin stays open); [HarnessRequest.sessionId] is the harness session to resume. */
    fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess

    /** A fresh connection for one launched process; [out] writes to it and reports. */
    fun connect(out: SessionOutput): SessionConnection

    /**
     * 2.12.0: like [connect], also given the [launched] process [launch] returned for this connection (its env carries what the plugin put there,
     * e.g. a per-process password). Default: [connect]. The host always calls this one.
     */
    fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection = connect(out)
}

/** 2.8.0: the plugin's handle on one launched process. Thread-safe. */
interface SessionOutput {
    /** Write [text] to the process's stdin; false = it could not be written. */
    fun write(text: String): Boolean
    fun closeInput()

    /** Plumbing for the Activity view; never a chat line. */
    fun detail(text: String)

    /** The harness's own session id once known. */
    fun providerSession(id: String)

    /** Exactly once per [SessionConnection.sendTurn]. */
    fun turnEnded(end: SessionTurnEnd, exitCode: Int? = null, reason: String? = null)

    /** 2.12.0: [line] as if the process had printed it to stdout: the host forwards it to the raw stream and reads it with [Harness.newTranslator]. Default: dropped. */
    fun output(line: String) {}

    /** 2.12.0: [line] as if the process had printed it to stderr. Default: dropped. */
    fun diagnostic(line: String) {}

    /**
     * 2.12.0: ask the session's owner whether one [tool] call ([input] = a short summary of it) may run (Tool approval [ToolApproval.ASK]). BLOCKS until the owner
     * answers, the prompt times out or the session ends (all of which but an approval are [ApprovalReply.DENY]); call it off the protocol's event thread.
     * Default: nobody can prompt, so DENY.
     */
    fun requestApproval(tool: String, input: String): ApprovalReply = ApprovalReply.DENY
}

/** 2.12.0: the owner's answer to [SessionOutput.requestApproval]. */
enum class ApprovalReply { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY }

/** 2.8.0: one launched process's protocol state. */
interface SessionConnection {
    /** Every stdout line of the process (the host also forwards it verbatim to the raw stream). */
    fun onStdout(line: String)

    /** 2.12.0: every stderr line of the process (the host also forwards it as a diagnostic). Default: ignored. */
    fun onStderr(line: String) {}

    /** Send one turn; its end comes back through [SessionOutput.turnEnded]. */
    fun sendTurn(request: HarnessRequest)

    /** Stop the current turn and KEEP the process. false = it cannot; the host kills. */
    fun abortTurn(): Boolean

    /** Ask the process to exit (usually [SessionOutput.closeInput]). false (default) = no graceful exit; the host kills at once. */
    fun close(): Boolean = false
}

/** 2.7.0: the only roots a contributed sandbox dir may sit under: the module's own data folder and the session worktree. */
data class SandboxRoots(val dataDir: File, val worktree: File)

/** 2.7.0: dirs added to the sandbox (read-only / read-write) and names of environment variables to let through. */
data class SandboxContribution(
    val readDirs: List<File> = emptyList(),
    val writeDirs: List<File> = emptyList(),
    val envNames: Set<String> = emptySet(),
)

/**
 * 2.6.0: [extraArgs] are appended to the harness argv, [env] is added to its environment, [files] are written by the host under the
 * module's own [HarnessHost.stateDir] (relative path -> text; a path that leaves that folder is refused and not written).
 */
data class WatchContribution(
    val extraArgs: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
)

/** 2.4.0: one harness's plan limits. [read] may block on I/O (the host bounds it with a timeout) and may throw. */
fun interface HarnessUsageSource {
    fun read(nowMs: Long): HarnessUsageReading
}

/** [windows] empty = no signal (the host shows the harness as unknown, never 0%). [reason] is free text for logs only. */
data class HarnessUsageReading(val windows: List<HarnessUsageWindow>, val reason: String? = null)

/** One limit window: [percent] used (0-100, null = unknown), [resetsAt] epoch millis (null = unknown). */
data class HarnessUsageWindow(val label: String, val windowMinutes: Int, val percent: Double?, val resetsAt: Long? = null)

/** 2.5.0: reads one session of this harness from its own store. */
fun interface HarnessStats {
    /** The row for the harness's own session id, or null when its store has none. May throw. */
    fun read(providerSessionId: String): HarnessStatsRow?
}

/** Every field optional; null = the store does not say (never guessed). Times are epoch millis. */
data class HarnessStatsRow(
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val userPrompts: Int? = null,
    val assistantTurns: Int? = null,
    val toolUses: Int? = null,
    val models: List<String>? = null,
    val lastContextTokens: Long? = null,
    val billedTokens: Long? = null,
    val costTotal: Double? = null,
    /** Ordered record of the session (null = only the totals above); the host folds it into requests, context growth and tool sources. */
    val events: List<HarnessStatEvent>? = null,
)

/** One step of a session's record, in order. [Request.cost] only when the harness itself reports one. */
sealed interface HarnessStatEvent {
    data class Seen(val at: Long?) : HarnessStatEvent
    data object Prompt : HarnessStatEvent
    data class Turn(val model: String?) : HarnessStatEvent
    data class Request(val at: Long?, val model: String?, val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val cost: Double? = null) : HarnessStatEvent
    data class AssistantAt(val at: Long?) : HarnessStatEvent
    data class Tool(val name: String, val at: Long?) : HarnessStatEvent
    data class ToolResult(val chars: Int, val at: Long?) : HarnessStatEvent
}

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
    data class Usage @JvmOverloads constructor(
        val inputTokens: Long,
        val outputTokens: Long,
        /** 2.9.0: prompt tokens read from the provider's cache (0 = none or not reported). */
        val cacheReadTokens: Long = 0,
        /** 2.9.0: prompt tokens written to the provider's cache (0 = none or not reported). */
        val cacheWriteTokens: Long = 0,
    ) : HarnessEvent
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
