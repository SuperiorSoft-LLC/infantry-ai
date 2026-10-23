package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File

/**
 * Builds the Claude Code invocation for one non-interactive run.
 *
 * Shape (Phase 2, verified against claude 2.1.293):
 *
 *   claude.exe -p --output-format stream-json --verbose
 *              [--model <m>] [--mcp-config <f>...] [--strict-mcp-config]
 *              [--permission-mode <mode>] --allowedTools Bash(agenthq:*),PowerShell(agenthq:*)
 *              [--resume <session-id>]
 *
 * and the prompt is written to **stdin** then stdin is closed — identical to `opencode run`, which
 * is why `AgentProvider.deliver` is shared rather than duplicated.
 *
 * Facts encoded here and not to be "simplified":
 *  - the NATIVE `bin/claude.exe` is spawned, never the npm shim. Same rule as opencode
 *    (`CreateProcess error=193` on the extensionless / `.ps1` launcher). Claude Code 2.x now ships a
 *    real `bin/claude.exe`, so no cmd.exe hop is needed.
 *  - `--verbose` is required alongside `--output-format stream-json` in `--print` mode; without it
 *    the CLI refuses the combination.
 *  - `--print` never prompts for a tool permission (there is no TTY), so it does not hang, but a tool outside
 *    the allowlist is DENIED silently; the mode is the run's harness-neutral Tool approval, mapped by
 *    [ClaudePermissionMode] (BUG-PERM-1); `AGENTHQ_CLAUDE_PERMISSION_MODE` still overrides it.
 */
object ClaudeCommand {
    /**
     * No default model on purpose: unlike opencode there is no free Claude tier, so the daemon
     * inherits whatever model the CLI/user configured rather than hard-coding one. Set
     * `AGENTHQ_CLAUDE_MODEL` (an alias like `haiku` or a full id) to pin it.
     */
    /**
     * CLIPATH-1: the one resolver ([com.superiorsoftllc.agenthq.executables.CliExecutables]): the Settings override, then
     * `AGENTHQ_CLAUDE_EXE`, then the known install locations ([com.superiorsoftllc.agenthq.executables.CliTools.CLAUDE]),
     * then PATH. Not found throws with the reason and every path tried.
     */
    fun resolveExecutable(): String =
        com.superiorsoftllc.agenthq.executables.CliExecutables.require(com.superiorsoftllc.agenthq.executables.CliTools.CLAUDE)

    /**
     * BUG-PERM-5: the `--allowedTools` rules passed in EVERY Tool approval mode, deny included. The `agenthq` CLI is
     * AgentHQ's own control plane (tickets, mail), not an arbitrary tool, so a deny session can still reach its board.
     * Nothing else is widened: Claude matches compound commands per sub-command, so `agenthq x && git push` still
     * needs git. Rule syntax verified live against claude 2.1.304 (`-p --allowedTools "Bash(agenthq:*),PowerShell(agenthq:*)"`:
     * `agenthq ...` ran through both tools, `git init` stayed denied).
     */
    val AGENTHQ_ALLOWED_TOOLS: List<String> = listOf(
        "Bash(agenthq:*)",
        "PowerShell(agenthq:*)",
        // MCP-1: every tool of the session's own `agenthq mcp serve` ([sessionMcpConfigs]), in every mode too.
        com.superiorsoftllc.agenthq.daemon.AgentHqMcp.CLAUDE_TOOL_RULE,
    )

    /**
     * PROMPT-3: the `--permission-prompt-tool` value that routes Claude's permission checks over the stream-json control
     * channel (the Agent SDK protocol). Verified live against claude 2.1.304: a `control_request` `can_use_tool` on stdout,
     * answered by a `control_response` on stdin (`allow` + `updatedInput`, or `deny` + `message`).
     */
    const val PERMISSION_PROMPT_STDIO = "stdio"

    fun model(): String? =
        InfantryEnv.get("AGENTHQ_CLAUDE_MODEL")?.takeIf { it.isNotBlank() }

    /**
     * BUG-PERM-1: the `--permission-mode` for a run whose resolved Tool approval is [approval] ([ClaudePermissionMode]:
     * deny = no flag, allowEdits = acceptEdits, allowAll = bypassPermissions; the legacy env var still wins).
     */
    fun permissionMode(approval: com.superiorsoftllc.agenthq.harness.ToolApproval = com.superiorsoftllc.agenthq.harness.ToolApproval.DENY): String? =
        ClaudePermissionMode.forApproval(approval)

    /**
     * MCP configs the CLI should load: `AGENTHQ_CLAUDE_MCP_CONFIG` holds one or more file paths or
     * inline JSON objects, separated by commas or newlines (paths may contain spaces, so a comma or a
     * newline is the separator, never a bare space).
     */
    fun mcpConfigs(): List<String> =
        parseMcpConfigs(InfantryEnv.get("AGENTHQ_CLAUDE_MCP_CONFIG"))

    /**
     * MCP-1: the `--mcp-config` values for a session whose env is [sessionEnv]: [userConfigs] (`AGENTHQ_CLAUDE_MCP_CONFIG`)
     * unchanged, plus the session's own `agenthq mcp serve` entry ([com.superiorsoftllc.agenthq.daemon.AgentHqMcp]),
     * merged, never replacing a user config.
     */
    fun sessionMcpConfigs(sessionEnv: Map<String, String>, userConfigs: List<String> = mcpConfigs()): List<String> =
        com.superiorsoftllc.agenthq.daemon.AgentHqMcp.claudeMcpConfigs(
            userConfigs,
            com.superiorsoftllc.agenthq.daemon.AgentHqMcp.launch(sessionEnv),
        )

    /** Pure split, so the separator rules can be asserted without touching the environment. */
    internal fun parseMcpConfigs(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun strictMcp(): Boolean =
        InfantryEnv.get("AGENTHQ_CLAUDE_STRICT_MCP")?.trim()?.lowercase() in setOf("1", "true", "yes")

    /**
     * [model] is the session's own choice (contract 1.3.0); see [effectiveModel]. [executable] defaults
     * to the resolved native claude.exe; a caller (the HARNESS 3/4 plugin's tests) may name another so the
     * argv composition is assertable on a machine with no Claude installed.
     */
    fun spec(
        workingDir: File? = null,
        resumeSessionId: String? = null,
        model: String? = null,
        executable: String = resolveExecutable(),
        effort: String? = null,
        streamInput: Boolean = false,
        /** BUG-PERM-1: the run's resolved Tool approval (the harness hook); mapped to `--permission-mode`. */
        toolApproval: com.superiorsoftllc.agenthq.harness.ToolApproval = com.superiorsoftllc.agenthq.harness.ToolApproval.DENY,
        /** MCP-1: the session env the `agenthq mcp serve` entry names (the child inherits it either way). */
        sessionEnv: Map<String, String> = emptyMap(),
    ): ProcessSpec =
        ProcessSpec(
            command = command(
                executable = executable,
                model = effectiveModel(model),
                permissionMode = permissionMode(toolApproval),
                mcpConfigs = sessionMcpConfigs(sessionEnv), // MCP-1
                strictMcp = strictMcp(),
                resumeSessionId = resumeSessionId,
                effort = effort,
                streamInput = streamInput,
            ),
            workingDir = workingDir,
        )

    /**
     * The model a run names: [requested] when non-blank, else `AGENTHQ_CLAUDE_MODEL` ([model]), which
     * may itself be null -- then no `--model` flag is passed and the CLI's own model applies.
     */
    internal fun effectiveModel(requested: String?): String? = requested?.takeIf { it.isNotBlank() } ?: model()

    /**
     * Pure argv composition, separate from environment resolution so it can be asserted in tests
     * without a real CLI installed.
     */
    internal fun command(
        executable: String,
        model: String?,
        permissionMode: String?,
        mcpConfigs: List<String>,
        strictMcp: Boolean,
        resumeSessionId: String?,
        effort: String? = null,
        /**
         * BUG-M2-11c: the persistent mode (`--input-format stream-json`): turns arrive as JSON lines on a stdin that stays
         * open, so one process serves every turn of a session (ClaudeSessionAdapter).
         */
        streamInput: Boolean = false,
        /** BUG-PERM-5: always [AGENTHQ_ALLOWED_TOOLS]; ONE comma-joined value, since the flag is variadic. */
        allowedTools: List<String> = AGENTHQ_ALLOWED_TOOLS,
        /**
         * PROMPT-3: `--permission-prompt-tool` ([PERMISSION_PROMPT_STDIO] in Tool approval `ask`, persistent mode only):
         * a call outside the allowlist arrives on stdout as a `can_use_tool` control_request instead of being denied.
         */
        permissionPromptTool: String? = null,
    ): List<String> = buildList {
        add(executable)
        add("-p")
        if (streamInput) { add("--input-format"); add("stream-json") }
        add("--output-format"); add("stream-json")
        add("--verbose")
        model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--effort"); add(it) } // CP-4 slice d: `/effort`
        permissionMode?.takeIf { it.isNotBlank() }?.let { add("--permission-mode"); add(it) }
        allowedTools.takeIf { it.isNotEmpty() }?.let { add("--allowedTools"); add(it.joinToString(",")) }
        permissionPromptTool?.takeIf { it.isNotBlank() }?.let { add("--permission-prompt-tool"); add(it) }
        mcpConfigs.takeIf { it.isNotEmpty() }?.let {
            add("--mcp-config"); addAll(it)
            if (strictMcp) add("--strict-mcp-config")
        }
        resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--resume"); add(it) }
    }
}
