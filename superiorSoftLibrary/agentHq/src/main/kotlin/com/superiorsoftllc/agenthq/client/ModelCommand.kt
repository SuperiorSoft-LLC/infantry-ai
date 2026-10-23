package com.superiorsoftllc.agenthq.client

import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.protocol.SpawnOptions

/** The outcome of `/model <id>`: the options for the next `new` frame, or why nothing was switched. */
sealed interface ModelPick {
    data class Switch(val options: SpawnOptions) : ModelPick
    data class Refused(val reason: String) : ModelPick
}

/** The last `models` frame this client received; `/model <id>` resolves against it (no frame of its own). */
@Volatile
internal var lastModels: List<ProviderModels> = emptyList()

/**
 * `/model <id>`: find the provider whose `models` frame lists [id] and return the `new` options for it.
 * Refused (nothing switched) when no frame arrived yet, no provider lists it, or several do -- then the
 * explicit `/new <provider> <model>` is the way. No provider is named here; the frame decides.
 */
fun resolveModelPick(id: String, catalog: List<ProviderModels>): ModelPick {
    if (catalog.isEmpty()) return ModelPick.Refused("no model list yet: run /models first")
    val providers = catalog.filter { id in it.models }.map { it.provider }.distinct()
    return when (providers.size) {
        0 -> ModelPick.Refused("no provider lists model '$id' (see /models)")
        1 -> ModelPick.Switch(SpawnOptions(provider = providers.single(), model = id))
        else -> ModelPick.Refused("model '$id' is listed by ${providers.joinToString(", ")}: use /new <provider> $id")
    }
}
