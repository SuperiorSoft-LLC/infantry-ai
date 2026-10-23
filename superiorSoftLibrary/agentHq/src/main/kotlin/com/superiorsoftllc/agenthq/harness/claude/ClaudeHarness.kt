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

    companion object {
        const val HARNESS_ID = "claude"

        /** The real invocation for [request]: model and resume come from the request, never from a guess. */
        fun defaultSpec(
            request: HarnessRequest,
            executable: () -> String = ClaudeCommand::resolveExecutable,
            workingDir: File? = null,
        ): ProcessSpec = ClaudeCommand.spec(workingDir, request.sessionId, request.model, executable(), request.effort, toolApproval = request.toolApproval)
    }
}
