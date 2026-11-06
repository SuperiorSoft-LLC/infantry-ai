// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ContextLimitCapability
import com.superiorsoftllc.infantry.harness.api.EffortCapability
import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.Harness
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessCommandSpec
import com.superiorsoftllc.infantry.harness.api.HarnessHost
import com.superiorsoftllc.infantry.harness.api.HarnessModels
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.HarnessStats
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator
import com.superiorsoftllc.infantry.harness.api.HarnessUsageSource
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import com.superiorsoftllc.infantry.harness.api.WatchContribution
import java.io.File

/**
 * HARNESS H7b-2: Claude Code as a harness module, on the harness API 2.10: the argv ([ClaudeCommand]), the stream reader
 * ([ClaudeStreamTranslator]), the persistent session ([ClaudeSession]), the permission-mode / MCP-rule / `/` command slots, the model list,
 * the watch hook settings, and the session-store stats and plan-limit usage contributions. Construction and [register] have no side effects
 * (the claude executable is resolved only when a run starts). NOT used in production yet: the daemon's built-in `claude` harness still runs.
 */
class ClaudeHarnessPlugin : HarnessPlugin {
    override val pluginId: String = "Infantry_Claude"

    // Must be written out here, NOT inherited (see HarnessPlugin.contractVersion).
    override val contractVersion: String = HARNESS_API_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(ClaudeHarness(registrar.host))
    }
}

class ClaudeHarness internal constructor(
    private val host: HarnessHost,
    private val executable: () -> String,
    private val home: () -> File,
) : Harness {
    constructor(host: HarnessHost) : this(
        host,
        { host.resolveCli(ClaudeCommand.CLI_ID) ?: error("the claude CLI was not found (set its location in Settings, INFANTRY_CLAUDE_EXE, or install Claude Code)") },
        { File(System.getProperty("user.home")) },
    )

    override val id: String = ClaudeCommand.HARNESS_ID

    override val capabilities: Set<HarnessCapability> = setOf(
        HarnessCapability.MODEL_SELECTION, // --model
        HarnessCapability.SESSION_RESUME, // --resume
        HarnessCapability.TOOL_CALLS,
        HarnessCapability.REASONING,
        HarnessCapability.USAGE,
        HarnessCapability.EFFORT_SELECTION, // --effort
        HarnessCapability.LIVE_SESSION, // one process per session, every wake a message into it (CLI-LIVE-1); stream-json or the interactive CLI (CLI-PTY-1)
        HarnessCapability.MID_TURN_INPUT, // CHAT-QUEUE-1: a message written into the running turn (stream-json user line / typed into the busy CLI)
    )

    private fun mcp(env: Map<String, String>): Pair<List<String>?, Map<String, String>> {
        val launch = host.mcpLaunch(env) ?: return null to emptyMap()
        return launch.command to launch.env
    }

    /** One-shot run. The permission-mode argv is NOT added here: the host appends [approvalArgs] to every launch. */
    override fun process(request: HarnessRequest): HarnessProcess {
        val sessionEnv = emptyMap<String, String>()
        val (mcpCommand, mcpEnv) = mcp(sessionEnv)
        return HarnessProcess(
            command = ClaudeCommand.command(
                executable = executable(),
                model = ClaudeCommand.effectiveModel(request.model),
                permissionMode = null,
                mcpConfigs = ClaudeCommand.mcpConfigs(ClaudeCommand.userMcpConfigs(), mcpCommand, mcpEnv),
                strictMcp = ClaudeCommand.strictMcp(),
                resumeSessionId = request.sessionId,
                effort = request.effort,
            ),
            env = ClaudeCommand.contextLimitEnv(request.contextLimit),
        )
    }

    override fun newTranslator(): HarnessTranslator = ClaudeStreamTranslator()

    override val effort: EffortCapability = ClaudeCommand.EFFORT

    override val contextLimit: ContextLimitCapability = ClaudeCommand.CONTEXT_LIMIT

    override fun approvalArgs(approval: ToolApproval): List<String> = ClaudeCommand.approvalArgs(approval)

    override fun mcpToolRule(serverName: String): String? = ClaudeCommand.mcpToolRule(serverName)

    override fun commands(): List<HarnessCommandSpec> = ClaudeCommand.COMMANDS

    override fun models(): List<HarnessModels> = ClaudeCommand.models()

    private fun liveIdle(): Int = host.setting(ClaudeCommand.LIVE_IDLE_SETTING)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: 0

    private fun interactive(): Boolean = ClaudeCommand.isInteractive(host.setting(ClaudeCommand.MODE_SETTING))

    /** the interactive CLI in a terminal (CLI-PTY-1, the default), or headless stream-json when `harness.claude.mode = headless`; decided at every launch. */
    override fun session(): HarnessSession = ClaudeModeSession(
        headless = ClaudeSession(executable, ::mcp, { env -> ClaudeCommand.watchSettings(env) }, idleMinutes = ::liveIdle, stateDir = { host.stateDir }),
        interactive = ClaudeTtySession(executable, ::mcp, { env -> ClaudeCommand.watchSettings(env) }, stateDir = { host.stateDir }, idleMinutes = ::liveIdle,
            approvalHookSeconds = { ClaudePermissionHook.timeoutSec(host.setting(ClaudePermissionHook.TIMEOUT_SETTING)) },
            signInProblem = { env -> ClaudeSignIn.problem(System.getenv() + env, home()) }),
        mode = { host.setting(ClaudeCommand.MODE_SETTING) },
    )

    /**
     * The PreToolUse / PostToolUse hook, as a settings file in the module's state dir named by `--settings`. In interactive mode the session's own
     * settings file carries these hooks (claude keeps only the last `--settings`), so no second flag is added.
     */
    override fun watch(env: Map<String, String>, launcher: String): WatchContribution? {
        val settings = ClaudeCommand.watchSettings(env) ?: return null
        return WatchContribution(
            extraArgs = if (interactive()) emptyList() else listOf("--settings", File(host.stateDir, ClaudeCommand.WATCH_SETTINGS_FILE).absolutePath),
            files = mapOf(ClaudeCommand.WATCH_SETTINGS_FILE to settings),
        )
    }

    override fun stats(): HarnessStats = HarnessStats { psid -> ClaudeStats.read(ClaudeStats.projectsDir(home(), System.getenv("CLAUDE_CONFIG_DIR")), psid) }

    override fun usageSource(): HarnessUsageSource = ClaudeUsage(home)
}
