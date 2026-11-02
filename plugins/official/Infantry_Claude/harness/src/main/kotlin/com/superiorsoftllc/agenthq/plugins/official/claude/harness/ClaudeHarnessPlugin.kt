package com.superiorsoftllc.agenthq.plugins.official.claude.harness

import com.superiorsoftllc.agenthq.harness.api.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.api.EffortCapability
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.Harness
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessCommandSpec
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessModels
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessSession
import com.superiorsoftllc.agenthq.harness.api.HarnessStats
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import com.superiorsoftllc.agenthq.harness.api.HarnessUsageSource
import com.superiorsoftllc.agenthq.harness.api.ToolApproval
import com.superiorsoftllc.agenthq.harness.api.WatchContribution
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
        { host.resolveCli(ClaudeCommand.CLI_ID) ?: error("the claude CLI was not found (set its location in Settings, AGENTHQ_CLAUDE_EXE, or install Claude Code)") },
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

    override fun session(): HarnessSession = ClaudeSession(executable, ::mcp, { env -> ClaudeCommand.watchSettings(env) })

    /** The PreToolUse / PostToolUse hook, as a settings file in the module's state dir named by `--settings`. */
    override fun watch(env: Map<String, String>, launcher: String): WatchContribution? {
        val settings = ClaudeCommand.watchSettings(env) ?: return null
        return WatchContribution(
            extraArgs = listOf("--settings", File(host.stateDir, ClaudeCommand.WATCH_SETTINGS_FILE).absolutePath),
            files = mapOf(ClaudeCommand.WATCH_SETTINGS_FILE to settings),
        )
    }

    override fun stats(): HarnessStats = HarnessStats { psid -> ClaudeStats.read(File(home(), ".claude/projects"), psid) }

    override fun usageSource(): HarnessUsageSource = ClaudeUsage(home)
}
