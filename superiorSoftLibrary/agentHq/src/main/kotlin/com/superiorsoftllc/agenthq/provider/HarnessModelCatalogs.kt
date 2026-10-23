package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.harness.claude.ClaudeModelCatalog
import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.harness.pi.PiModelCatalog
import com.superiorsoftllc.agenthq.protocol.ProviderModels

/**
 * ROLE-FIRST S4: one harness's provider/model listing, for a harness that has NO [AgentProvider] of its own
 * (so `AgentProvider.models()` never covers it). Rows use the existing `models` frame shape: `provider` = the
 * harness id, `group`/`label` = the upstream vendor, `models` = the ids that harness's `--model` accepts.
 */
fun interface HarnessModelCatalog {
    fun models(): List<ProviderModels>
}

/**
 * ROLE-FIRST S4 (docs/AGENTHQ-ROLE-FIRST.md 5.3): the sidecar registry, keyed by harness id, that closes the
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
     */
    fun rows(
        harnessIds: Collection<String>,
        covered: Set<String>,
        lookup: (String) -> HarnessModelCatalog? = { null },
    ): List<ProviderModels> =
        harnessIds.distinct().filter { it !in covered }.flatMap { id ->
            // S5's lookup (a descriptor's own models section) wins over the noted row; the noted row is the fallback.
            val source = sources[id] ?: lookup(id) ?: return@flatMap listOf(ProviderModels(id, "", id, emptyList(), NO_CATALOG_NOTE))
            try {
                source.models().map { if (it.provider == id) it else it.copy(provider = id) }
            } catch (e: Exception) {
                listOf(ProviderModels(id, "", id, emptyList(), "could not list models: ${(e.message ?: e::class.simpleName.orEmpty()).take(200)}"))
            }
        }

    companion object {
        val EMPTY = HarnessModelCatalogs(emptyMap())

        /** The note on the row of a registered harness that has no model source: type the id its `--model` takes. */
        const val NO_CATALOG_NOTE = "this harness lists no models; type the model id its --model accepts"

        /** The built-in sources: Claude Code (static list) and Pi. Descriptor harnesses bring their own (S5), through `rows(lookup = ...)`. */
        fun builtIn(): HarnessModelCatalogs = HarnessModelCatalogs(
            mapOf(
                ClaudeHarness.HARNESS_ID to ClaudeModelCatalog(),
                PiHarness.HARNESS_ID to PiModelCatalog(),
            ),
        )
    }
}
