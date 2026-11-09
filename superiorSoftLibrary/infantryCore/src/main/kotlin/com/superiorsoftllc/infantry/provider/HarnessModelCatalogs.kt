// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.harness.AgentProvider

import com.superiorsoftllc.infantry.protocol.ProviderModels

/**
 * ROLE-FIRST S4: one harness's provider/model listing, for a harness that has NO [AgentProvider] of its own
 * (so `AgentProvider.models()` never covers it). Rows use the existing `models` frame shape: `provider` = the
 * harness id, `group`/`label` = the upstream vendor, `models` = the ids that harness's `--model` accepts.
 */
fun interface HarnessModelCatalog {
    fun models(): List<ProviderModels>
}

/**
 * ROLE-FIRST S4 (docs/INFANTRY-ROLE-FIRST.md 5.3): the sidecar registry, keyed by harness id, that closes the
 * Pi/descriptor gap in the `models` frame WITHOUT a HARNESS contract bump (no `Harness.models()`).
 *
 * Rules, the same as `modelCatalog` already applies to configured providers:
 *  - only harnesses actually registered on this daemon get rows, and a harness id a configured provider already
 *    covers is skipped (the provider wins, so a harness never appears twice);
 *  - a source that throws yields ONE noted empty row; one bad source never hides the others;
 *  - every row is stamped with its harness id, so a source cannot claim another harness's slot;
 *  - PICKER-ALL-1: a registered harness with NO source still gets one noted empty row ([NO_CATALOG_NOTE]), so the
 *    picker's free-text field explains itself instead of being a bare box.
 */
class HarnessModelCatalogs(private val sources: Map<String, HarnessModelCatalog>) {

    /**
     * The rows for [harnessIds] (registry order) that are not in [covered]. A harness with no built-in source is
     * asked of [lookup] (ROLE-FIRST S5: a descriptor harness's own `models` section); a built-in source wins.
     *
     * MODEL-CATALOG-1: every listing goes through [cache], so a harness whose CLI is slow never delays the answer for
     * the others: its last good rows (with their age) are served at once, or one noted row says it is still listing.
     */
    fun rows(
        harnessIds: Collection<String>,
        covered: Set<String>,
        cache: ModelCatalogCache = ModelCatalogCache(),
        lookup: (String) -> HarnessModelCatalog? = { null },
    ): List<ProviderModels> =
        harnessIds.distinct().filter { it !in covered }.flatMap { id ->
            // S5's lookup (a descriptor's own models section) wins over the noted row; the noted row is the fallback.
            val source = sources[id] ?: lookup(id) ?: return@flatMap listOf(ProviderModels(id, "", id, emptyList(), NO_CATALOG_NOTE))
            // every row is stamped with its harness id, so a source cannot claim another harness's slot
            val cached = cache.read(SOURCE_PREFIX + id) { source.models().map { if (it.provider == id) it else it.copy(provider = id) } }
            val note = cache.noteFor(id, cached)
            val rows = cached.rows.orEmpty()
            if (note == null) rows else rows + ProviderModels(id, NOTE_GROUP, id, emptyList(), note)
        }

    companion object {
        val EMPTY = HarnessModelCatalogs(emptyMap())

        /** MODEL-CATALOG-1: the keys the shared [ModelCatalogCache] stores these rows under (a provider's own list uses `provider:`). */
        internal const val SOURCE_PREFIX = "harness:"

        /** The group of the row that carries a note about a harness's list (it holds no models). */
        const val NOTE_GROUP = "notes"

        /** The note on the row of a registered harness that has no model source: type the id its `--model` takes. */
        const val NO_CATALOG_NOTE = "this harness lists no models; type the model id its --model accepts"

        /** The built-in sources: none (H7b-3: Claude Code and Pi list their own models through their modules). Descriptor harnesses bring their own (S5), through `rows(lookup = ...)`. */
        fun builtIn(): HarnessModelCatalogs = EMPTY
    }
}
