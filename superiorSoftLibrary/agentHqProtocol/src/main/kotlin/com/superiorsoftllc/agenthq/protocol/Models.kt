package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * One group of models a configured provider can run, as the `models` answer lists them.
 *
 * [provider] is the daemon provider id (as the `providers` frame lists it); [group] is the upstream
 * vendor inside it (opencode fronts many: `anthropic`, `openai`, ...), blank when there is none.
 * [models] are full ids, exactly what goes in a `new` frame's `model`. An empty [models] with a [note]
 * is a provider that has nothing to offer right now (the note says why); it is listed, never hidden.
 */
@Serializable
data class ProviderModels(
    val provider: String,
    val group: String,
    val label: String,
    val models: List<String>,
    val note: String? = null,
    /**
     * LIMITS: true = the harness cannot run anything right now (it lists no models, or the
     * model allowlist names none of them); [note] says why. Pickers show the harness dimmed and not selectable.
     */
    val unavailable: Boolean = false,
)
