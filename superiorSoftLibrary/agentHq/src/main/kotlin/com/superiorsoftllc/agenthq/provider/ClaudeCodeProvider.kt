package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeCatalog
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeConfigLocations
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeConfigReader
import com.superiorsoftllc.agenthq.runner.ProcessSpec

/**
 * The Phase 2 provider: Claude Code behind the SAME [AgentProvider] / ProcessRunner seam.
 *
 * Behaviour mirror of [OpenCodeProvider]: non-interactive run, prompt on stdin, one JSON event per
 * line on stdout. The CLI-specific surface (stream-json flags, `--resume`, MCP config, hook noise on
 * stderr) is contained entirely in [ClaudeCommand]; nothing above this class changed to admit it.
 */
class ClaudeCodeProvider(
    /**
     * The model ids/aliases on offer. Injectable so tests need no home directory; the default reads
     * the user's real Claude settings ([ClaudeConfigReader]).
     */
    private val catalog: () -> ClaudeCatalog = ClaudeConfigReader(ClaudeConfigLocations.fromEnvironment())::read,
    /**
     * BUG-M2-11c: claude's persistent mode ([com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter]), or null when it
     * has none. [ProviderFactory] passes one; a bare construction (tests) has none, so the host runs it one-shot.
     */
    private val sessionAdapter: com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter?,
) : AgentProvider {
    /** No persistent mode; keeps `ClaudeCodeProvider { catalog }` and `ClaudeCodeProvider(reader::read)` working. */
    constructor(
        catalog: () -> ClaudeCatalog = ClaudeConfigReader(ClaudeConfigLocations.fromEnvironment())::read,
    ) : this(catalog, null)

    override val id: String = "claude"

    override fun sessionAdapter(): com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter? = sessionAdapter

    override val commands: List<com.superiorsoftllc.agenthq.harness.HarnessCommand> =
        com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.catalog

    // H7a: argv, default model and model listing live in ClaudeHarness (one layer); this class only adapts them to AgentProvider.
    override fun spec(request: RunRequest): ProcessSpec = com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness.specFor(request)

    override fun contextLimit(): com.superiorsoftllc.agenthq.harness.ContextLimitCapability =
        com.superiorsoftllc.agenthq.claude.ClaudeContextLimit.CAPABILITY

    override fun effort(): com.superiorsoftllc.agenthq.harness.EffortCapability =
        com.superiorsoftllc.agenthq.claude.ClaudeEffort.CAPABILITY

    override fun sessionIdFromLine(line: String): String? = SessionId.fromLine(line)

    override fun defaultModel(): String? = com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness.defaultModel()

    override fun models(): List<ProviderModels> = com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness.models(catalog())
}
