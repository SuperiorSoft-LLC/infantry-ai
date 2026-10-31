package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript

/**
 * Phase 9: the stub provider -- a scripted session with NO provider CLI behind it.
 *
 * It exists so the UI track can be built and tested against the daemon's REAL machinery
 * (sessions, persistence, the WebSocket protocol, the normalised event vocabulary, the mailbox)
 * on a machine with no OpenCode and no Claude installed. It is selected the same way a real
 * provider is -- `AGENTHQ_PROVIDER=stub`, or `ProviderFactory.fromFlag("stub")` -- and it goes
 * through every layer a real provider goes through, including
 * [com.superiorsoftllc.agenthq.protocol.EventNormalizer]. Nothing above it knows it is a stub.
 *
 * WHY IT IS A PROVIDER AND NOT A MOCK DAEMON: a mock daemon would freeze a second, divergent copy
 * of the protocol and the UI would then be built against a fiction. This reuses the real protocol,
 * the real session lifecycle and the real stream path, so a UI that renders a scripted session
 * correctly renders a real one with no change.
 *
 * @param script what to replay. Override in a test to script a specific conversation.
 * @param delayMs pause before each scripted line, so a client sees a stream.
 */
class StubProvider(
    private val script: StubScript = StubScript.default(),
    private val delayMs: Long = 25L,
    /** Model ids `/model` suggests; empty (the default) keeps the stub's catalog exactly Claude's. */
    private val modelIds: List<String> = emptyList(),
) : AgentProvider {
    override val id: String = "stub"

    /** The stub echoes `--effort <level>` into its output, so an effort is observable in tests. */
    override fun effort(): com.superiorsoftllc.agenthq.harness.EffortCapability =
        com.superiorsoftllc.agenthq.harness.EffortCapability(com.superiorsoftllc.agenthq.harness.EffortSupport.SUPPORTED, "the stub echoes the level")

    /** The stub honours the whole standard catalog, so a client can be exercised end to end with no CLI installed. */
    override val commands: List<com.superiorsoftllc.agenthq.harness.HarnessCommand> =
        if (modelIds.isEmpty()) com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.catalog
        else com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.catalog.map {
            if (it.action == com.superiorsoftllc.agenthq.harness.CommandAction.SET_MODEL) it.copy(values = modelIds) else it
        }

    /**
     * The spec is a LABEL, not a command: nothing is ever spawned. It exists so the daemon's
     * provider-neutral path (`spec` -> `transport.start`) is exercised unchanged, and so a log line
     * naming the argv still makes sense.
     */
    override fun spec(request: RunRequest): ProcessSpec = ProcessSpec(
        // Echoing the prompt keeps the spec observably correct if a line is ever printed, without
        // pretending there is an executable to find. A model a session chose (contract 1.3.0) is
        // echoed the same way, so a test can see it reached the provider; the script ignores it.
        command = listOf("stub", "--script", script.name, "--prompt-bytes", request.prompt.length.toString()) +
            (request.model?.takeIf { it.isNotBlank() }?.let { listOf("--model", it) } ?: emptyList()) +
            (request.effort?.takeIf { it.isNotBlank() }?.let { listOf("--effort", it) } ?: emptyList()) +
            // A resumed turn names the provider session it continues, as `--session` / `--resume` do on a
            // real CLI, so a test can see a follow-up reached the SAME conversation (contract 1.11.0).
            (request.resumeSessionId?.takeIf { it.isNotBlank() }?.let { listOf("--resume", it) } ?: emptyList()),
        workingDir = request.workingDir,
    )

    /** No subprocess, so no configured runner: this provider brings its own in-memory transport. */
    override fun transport(): ProcessRunner = ScriptedRunner(script, delayMs)

    /** The script emits a session id the same way a real CLI does, so resume behaves identically. */
    override fun sessionIdFromLine(line: String): String? = SessionId.fromLine(line)
}
