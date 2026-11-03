// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File

/**
 * One agent-CLI invocation, provider-neutral.
 *
 * Every field has a defined meaning for every provider CLI we support:
 *  - [prompt] is the user turn (both CLIs read it from stdin, so [deliver] is shared);
 *  - [workingDir] is the cwd the provider runs in;
 *  - [resumeSessionId], when set, continues a prior provider session
 *    (`opencode run --session <id>` / `claude --resume <id>`). Null means a fresh session.
 *  - [model], when set, is the model this run names on the CLI command line (`-m` / `--model`).
 *    Null means the provider's configured default ([AgentProvider.defaultModel]). Contract 1.3.0
 *    lets a client choose it per session; the CLI, not the daemon, decides whether it exists.
 */
data class RunRequest(
    val prompt: String,
    val workingDir: File? = null,
    val resumeSessionId: String? = null,
    val model: String? = null,
    /** CP-4 slice d: the reasoning effort this run names (`/effort`), or null for the CLI's own default. A provider with no verified flag ignores it. */
    val effort: String? = null,
    /**
     * BUG-PERM-1: the harness hook. The run's resolved Tool approval (session override, Settings, `INFANTRY_TOOL_APPROVAL`,
     * else deny; [com.superiorsoftllc.infantry.session.ToolApprovals]). Each provider / session adapter maps it to its own
     * mechanism; one that has no mapping yet keeps denying.
     */
    val toolApproval: com.superiorsoftllc.infantry.harness.ToolApproval = com.superiorsoftllc.infantry.harness.ToolApproval.DENY,
    /**
     * CONTEXT-LIMIT-1: the session's context limit in tokens, or null for the CLI's own window. Set only when the provider's
     * [AgentProvider.contextLimit] applies (the session manager drops it otherwise and reports it as not applied).
     */
    val contextLimit: Int? = null,
)

/**
 * The provider seam, deliberately ABOVE [com.superiorsoftllc.infantry.runner.ProcessRunner].
 *
 * The two layers answer different questions and Phase 2 confirmed they must not be merged:
 *  - [com.superiorsoftllc.infantry.runner.ProcessRunner] owns *how a child is driven* (pipes vs PTY,
 *    drain threads, tree kill). It is CLI-agnostic and was NOT touched to add a second CLI.
 *  - [AgentProvider] owns *which argv a given CLI needs* and *how that CLI delivers a prompt*.
 *
 * The optional hooks below exist so a provider can express a CLI-specific surface without forcing
 * that surface onto the other provider: a provider that has no session id simply leaves
 * [sessionIdFromLine] at its default `null`.
 */
interface AgentProvider {
    /** Stable id used in logs, `/health` and the `INFANTRY_PROVIDER` flag (`"opencode"`, `"claude"`). */
    val id: String

    /** Build the process command for one run. */
    fun spec(request: RunRequest): ProcessSpec

    /**
     * Hand [RunRequest.prompt] to the started child and signal end of input.
     *
     * Shared by BOTH CLIs on purpose: `opencode run` and `claude -p` each read the prompt from stdin
     * and emit nothing until stdin reaches EOF (Phase 0 finding, re-confirmed for Claude Code in
     * Phase 2). A provider that ever needs a different delivery (e.g. prompt as an argv element)
     * overrides this one method.
     */
    fun deliver(process: RunningProcess, request: RunRequest) {
        process.writeInput(request.prompt)
        process.closeInput()
    }

    /**
     * Extract this provider's session id from one streamed stdout line, if that line carries one.
     * Used by the daemon to remember a session so a later prompt can resume it. Default: no
     * session surface.
     */
    fun sessionIdFromLine(line: String): String? = null

    /**
     * The model a run uses when it names none ([RunRequest.model] null), as this provider resolves
     * it from its configuration right now. Null = no model flag at all, so the CLI's own default
     * applies. Reported to clients by the `providers` frame (contract 1.3.0) so a picker can show
     * what "default" means; never written onto a session.
     */
    fun defaultModel(): String? = null

    /**
     * The transport this provider's [spec] must be run on, when it is NOT the configured one.
     *
     * Default `null` = "run my spec through whatever [com.superiorsoftllc.infantry.runner.RunnerFactory]
     * selects", which is every real CLI (both are one-shot subprocesses driven over pipes).
     *
     * A provider that has no child process at all -- a scripted replay, an HTTP-backed provider --
     * returns its own [com.superiorsoftllc.infantry.runner.ProcessRunner] here, and the daemon uses
     * it INSTEAD of the configured runner. This exists so such a provider never has to fake an
     * argv for a runner that would then try to spawn it: the Phase 9 stub returns an in-memory
     * transport, and `INFANTRY_PROVIDER=stub` therefore boots a daemon with no CLI installed.
     *
     * The narrow, documented limit: this is an ESCAPE HATCH, not an invitation. A real CLI returns
     * null, because argv + stdin delivery is the right seam for it and the pipes-vs-PTY question
     * belongs to [com.superiorsoftllc.infantry.runner.RunnerFactory], not to a provider.
     */
    fun transport(): com.superiorsoftllc.infantry.runner.ProcessRunner? = null

    /**
     * BUG-M2-11b-1 (docs/INFANTRY-SESSION-HOST-PLAN.md section 4): this harness's persistent-mode protocol, which the
     * daemon's [com.superiorsoftllc.infantry.session.host.SessionHost] drives to keep ONE process per session across
     * turns. Null (the default) = no persistent mode: the host runs this provider one-shot
     * ([com.superiorsoftllc.infantry.session.host.OneShotAdapter]), behind the same lifecycle and chat rules.
     */
    fun sessionAdapter(): com.superiorsoftllc.infantry.session.host.HarnessSessionAdapter? = null

    /**
     * HARNESS-CARRY-1: how this provider's first run after a provider switch takes over the conversation (the earlier
     * turns from the Infantry transcript, then the new prompt). Default: [com.superiorsoftllc.infantry.harness.ConversationCarry.REPLAY].
     */
    fun conversationCarry(): com.superiorsoftllc.infantry.harness.ConversationCarry =
        com.superiorsoftllc.infantry.harness.ConversationCarry.REPLAY

    /**
     * The models this provider knows about, or null if the list cannot be determined.
     *
     * Called by [AgentProfiles.save] to validate that a profile's model exists before storing it.
     * Null means "unknown" and the save will accept any model id (current behaviour).
     * A non-null list is the source of truth: a model not in it is refused with a suggestion
     * for the closest match (smallest Levenshtein distance).
     *
     * Implementations should cache the result after the first call. If the underlying command
     * fails, return null so the save falls back to accepting any model.
     */
    fun knownModels(): List<String>? = null

    /**
     * The models this provider can run, grouped by upstream vendor. Reported by the `models` frame so a
     * picker can offer them. Default: one empty entry saying the provider does not enumerate models.
     */
    fun models(): List<com.superiorsoftllc.infantry.protocol.ProviderModels> = listOf(
        com.superiorsoftllc.infantry.protocol.ProviderModels(id, "", id, emptyList(), "this provider does not list its models"),
    )

    /**
     * The `/` commands a client may offer for sessions on this provider (model, effort, compact...). Default: none.
     * A provider that is also registered as a harness plugin is served from the plugin's catalog instead.
     */
    val commands: List<com.superiorsoftllc.infantry.harness.HarnessCommand> get() = emptyList()

    /**
     * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): how this provider honours [RunRequest.contextLimit]. Default unsupported: a
     * limit set on its session is recorded and reported as not applied, never passed on.
     */
    fun contextLimit(): com.superiorsoftllc.infantry.harness.ContextLimitCapability =
        com.superiorsoftllc.infantry.harness.ContextLimitCapability.UNSUPPORTED

    /**
     * EFFORT-1 (docs/EFFORT.md): how this provider honours [RunRequest.effort]. Default unsupported: an effort set on its
     * session is recorded and reported as not applied, never passed on.
     */
    fun effort(): com.superiorsoftllc.infantry.harness.EffortCapability =
        com.superiorsoftllc.infantry.harness.EffortCapability.UNSUPPORTED
}
