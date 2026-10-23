package com.superiorsoftllc.agenthq.harness.claude

import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalog

/**
 * PICKER-ALL-1: the built-in model list for the Claude Code harness when no `claude` [com.superiorsoftllc.agenthq.provider.AgentProvider]
 * is configured (the daemon then only has the plugin, so `AgentProvider.models()` never covers it and the picker
 * was a bare text box). A configured `claude` provider still wins: its row reads the user's own settings.
 *
 * The `claude` CLI has no command that lists models, so this is a static list of values its `--model` takes,
 * passed through verbatim by [ClaudeHarness] -> `ClaudeCommand.spec`. Extend it by adding one line to [MODELS].
 */
class ClaudeModelCatalog(private val models: List<ClaudeModel> = MODELS) : HarnessModelCatalog {

    /** One `--model` value: [id] is what the CLI receives, [label] the human name (kept here; the `models` frame carries ids only). */
    data class ClaudeModel(val id: String, val label: String)

    override fun models(): List<ProviderModels> =
        listOf(ProviderModels(ClaudeHarness.HARNESS_ID, PROVIDER, "Claude", models.map { it.id }.distinct()))

    companion object {
        /** The single provider (vendor group) the Claude Code harness lists. */
        const val PROVIDER = "claude"

        /** Aliases first (they track the newest model of each family), then pinned full ids. */
        val MODELS: List<ClaudeModel> = listOf(
            ClaudeModel("opus", "Opus (latest)"),
            ClaudeModel("sonnet", "Sonnet (latest)"),
            ClaudeModel("haiku", "Haiku (latest)"),
            ClaudeModel("opus[1m]", "Opus, 1M context"),
            ClaudeModel("sonnet[1m]", "Sonnet, 1M context"),
            ClaudeModel("opusplan", "Opus plans, Sonnet executes"),
            ClaudeModel("claude-opus-5-5", "Claude Opus 5.5"),
            ClaudeModel("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            ClaudeModel("claude-haiku-5-5", "Claude Haiku 5.5"),
            ClaudeModel("claude-fable-5-1", "Claude Fable 5.1"),
        )
    }
}
