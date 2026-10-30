package com.superiorsoftllc.agenthq.daemon.api.harness

import kotlinx.serialization.Serializable

/**
 * RUNTIME-LOAD-2: the `providers` / `models` answers as a daemon plugin reads them (same fields and defaults as `ProviderInfo` and
 * `ProviderModels` in `agentHqProtocol`, which stay the wire's definition), and the one place the two are merged into the list of
 * harnesses a ticket can pin.
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val default: Boolean = false,
    val defaultModel: String? = null,
    val models: List<String>? = null,
    val modelsStale: Boolean = false,
    val available: Boolean = true,
    val unavailableReason: String? = null,
    val contextLimit: String? = null,
    val contextLimitHow: String? = null,
)

@Serializable
data class ProviderModels(
    val provider: String,
    val group: String,
    val label: String,
    val models: List<String>,
    val note: String? = null,
    val unavailable: Boolean = false,
)

data class PinnableHarness(
    val id: String,
    val default: Boolean,
    val defaultModel: String?,
    /** The models to pin; null = none listed (the harness takes any model id its CLI accepts). */
    val models: List<String>?,
    val available: Boolean,
    val unavailableReason: String?,
    val note: String?,
    /** CONTEXT-LIMIT-1: `supported` / `best_effort` / `unsupported`, and how; null from an older daemon. */
    val contextLimit: String? = null,
    val contextLimitHow: String? = null,
)

object HarnessCatalogText {
    /** How a listed harness is used, said the same way by the CLI and the MCP tool. */
    const val HOW_TO_PIN =
        "Pin a harness id as a ticket's provider and one of its models as its model (ticket create / update), then " +
            "start the ticket (ticket start). A harness that lists models refuses any other; one with no list takes " +
            "any model id its CLI accepts."

    /** [catalogError] is why the `models` frame could not be read (then [catalog] is empty), or null. */
    fun merge(providers: List<ProviderInfo>, catalog: List<ProviderModels>, catalogError: String?): List<PinnableHarness> =
        providers.map { info ->
            val rows = catalog.filter { it.provider == info.id }
            val models = info.models?.takeIf { it.isNotEmpty() }
                ?: rows.flatMap { it.models }.distinct().takeIf { it.isNotEmpty() }
            val notes = buildList {
                if (info.modelsStale) add("model list is last-known: the latest refresh failed")
                rows.mapNotNullTo(this) { it.note }
                if (models == null && catalogError != null) add("model catalog unavailable: $catalogError")
            }.distinct()
            PinnableHarness(info.id, info.default, info.defaultModel, models, info.available, info.unavailableReason, notes.joinToString("; ").ifEmpty { null }, info.contextLimit, info.contextLimitHow)
        }
}
