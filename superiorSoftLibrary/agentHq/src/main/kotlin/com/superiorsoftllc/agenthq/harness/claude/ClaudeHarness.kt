package com.superiorsoftllc.agenthq.harness.claude

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.agenthq.harness.HarnessCapability
import com.superiorsoftllc.agenthq.harness.HarnessCommand
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.harness.HarnessTranslator
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File

/**
 * Claude Code behind the HARNESS 1/4 [com.superiorsoftllc.agenthq.harness.Harness] interface (HARNESS 3/4).
 *
 * One run = one `claude -p --output-format stream-json --verbose` child. The process plumbing (prompt on stdin,
 * incremental delivery, exactly one terminal event, kill on cancel) is [CliHarness], shared with every other CLI
 * harness; what is Claude's own is the argv ([defaultSpec]) and [ClaudeStreamTranslator], the only code that knows
 * Claude's wire schema.
 *
 * [specFor] builds the process for a request; the default launches the real claude executable and resolves
 * it lazily, inside the run, so a missing install is reported as an Error event rather than failing construction.
 * `HarnessRequest.project` is an opaque label in the harness contract and is NOT interpreted here (in particular it
 * is not guessed to be a directory): a host that wants a working directory supplies one through [specFor].
 */
class ClaudeHarness(
    runner: ProcessRunner,
    specFor: (HarnessRequest) -> ProcessSpec = { defaultSpec(it) },
    translatorFactory: () -> HarnessTranslator = { ClaudeStreamTranslator() },
    /** BUG-M2-11c: the same adapter [com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider] returns; null = one-shot. */
    private val sessionAdapter: HarnessSessionAdapter? = null,
) : CliHarness(runner, specFor, translatorFactory, cliName = "claude") {
    override val id: String = HARNESS_ID

    override fun sessionAdapter(): HarnessSessionAdapter? = sessionAdapter

    // Written out on purpose: Harness.contractVersion has a DEFAULT getter evaluated in the daemon's copy of
    // the interface. Stating it here inlines the const when THIS plugin is compiled.
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override val capabilities: Set<HarnessCapability> = setOf(
        HarnessCapability.MODEL_SELECTION, // --model
        HarnessCapability.SESSION_RESUME, // --resume
        HarnessCapability.TOOL_CALLS,
        HarnessCapability.REASONING,
        HarnessCapability.USAGE,
        HarnessCapability.EFFORT_SELECTION, // --effort
    )

    override val commands: List<HarnessCommand> = ClaudeCommands.catalog

    /** H7b-1: the slots the harness API 2.10 gives every harness, filled in place from Claude's own mapping. */
    override fun approvalArgs(approval: com.superiorsoftllc.agenthq.harness.ToolApproval): List<String> =
        com.superiorsoftllc.agenthq.claude.ClaudePermissionMode.forApproval(approval)?.let { listOf("--permission-mode", it) } ?: emptyList()

    override fun mcpToolRule(serverName: String): String? = "mcp__${serverName}__*"

    override val effort: com.superiorsoftllc.agenthq.harness.EffortCapability = com.superiorsoftllc.agenthq.claude.ClaudeEffort.CAPABILITY

    override val contextLimit: com.superiorsoftllc.agenthq.harness.ContextLimitCapability =
        com.superiorsoftllc.agenthq.claude.ClaudeContextLimit.CAPABILITY

    companion object {
        const val HARNESS_ID = "claude"

        /** H7a: the provider-side argv, one place with [defaultSpec] (both end in [ClaudeCommand.spec]). */
        fun specFor(request: com.superiorsoftllc.agenthq.provider.RunRequest): ProcessSpec = ClaudeCommand.spec(
            workingDir = request.workingDir,
            resumeSessionId = request.resumeSessionId,
            model = request.model,
            effort = request.effort,
            toolApproval = request.toolApproval,
            contextLimit = request.contextLimit,
        )

        /** H7a: `AGENTHQ_CLAUDE_MODEL`, or null (the CLI's own configured model applies). */
        fun defaultModel(): String? = ClaudeCommand.model()

        /** H7a: the `--model` values the user's Claude configuration offers; a listing that cannot be built is thrown. */
        fun models(catalog: com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeCatalog): List<com.superiorsoftllc.agenthq.protocol.ProviderModels> {
            val note = (catalog.notes + listOfNotNull("limited by your availableModels setting".takeIf { catalog.restricted })).joinToString("; ")
            return listOf(com.superiorsoftllc.agenthq.protocol.ProviderModels(HARNESS_ID, "anthropic", "Anthropic", catalog.models, note.takeIf { it.isNotEmpty() }))
        }

        /** The real invocation for [request]: model and resume come from the request, never from a guess. */
        fun defaultSpec(
            request: HarnessRequest,
            executable: () -> String = ClaudeCommand::resolveExecutable,
            workingDir: File? = null,
        ): ProcessSpec = ClaudeCommand.spec(workingDir, request.sessionId, request.model, executable(), request.effort, toolApproval = request.toolApproval, contextLimit = request.contextLimit)
    }
}
