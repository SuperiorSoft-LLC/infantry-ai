package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PICKER 2/2: the provider+model a spawn should run on, exactly as a `new` frame carries them. */
data class PickedModel(val provider: String, val model: String?) {
    companion object {
        /** ROLE-1d: the selection a role's binding stands for; a provider alone seeds the provider (model null = the provider's default), null when it pins no provider. */
        fun fromBinding(b: com.superiorsoftllc.agenthq.ui.protocol.RoleBinding): PickedModel? =
            b.provider?.let { PickedModel(it, b.model) }
    }
}

/** One provider of the `models` frame with its groups, in the frame's order. */
data class CatalogSection(val provider: String, val groups: List<ProviderModels>)

/**
 * The `models` frame as the picker shows it: one section per provider (first appearance wins the order),
 * its groups kept as the daemon listed them. Nothing is added, dropped or named here -- an empty provider
 * stays, carrying its note.
 */
fun catalogSections(catalog: List<ProviderModels>): List<CatalogSection> =
    catalog.groupBy { it.provider }.map { (provider, groups) -> CatalogSection(provider, groups) }

/** The `new` frame's data for the next spawn: the picked provider+model, or "" (daemon defaults) when none. */
fun spawnOptionsData(picked: PickedModel?, effort: String? = null, role: String? = null, projectId: String? = null, contextLimit: Int? = null): String =
    if (picked == null && effort == null && role == null && projectId == null && contextLimit == null) ""
    else buildJsonObject {
        if (picked != null) { put("provider", picked.provider); picked.model?.let { put("model", it) } }
        if (effort != null) put("effort", effort)
        if (role != null) put("role", role)
        // BUG-M2-15: the session is bound to the project it was spawned under (SpawnOptions.projectId), so scoping can hide it elsewhere.
        if (projectId != null) put("projectId", projectId)
        // CONTEXT-LIMIT-1: the session's context limit in tokens; absent = the harness's own window.
        if (contextLimit != null) put("contextLimit", contextLimit)
    }.toString()
