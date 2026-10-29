package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels

/** One vendor group of the chosen harness, as the Provider list shows it. */
data class ProviderOption(val group: String, val label: String, val modelCount: Int)

/** One model of the chosen harness, with the vendor group it was listed under. [label] is the id without its vendor prefix. */
data class ModelOption(val id: String, val group: String, val label: String)

/**
 * ROLE-FIRST S6: the state behind the Harness -> Provider -> Model picker (docs/AGENTHQ-ROLE-FIRST.md section 7).
 * Pure and immutable: every choice returns a new model, so the composable (a later slice) just renders it.
 *
 * Vocabulary: [harness] is the wire's `provider` (a `providers` frame id), [group] is the vendor inside it
 * (a `models` row's `group`, null = "All"), [model] is the id exactly as the row lists it. Nothing is
 * maintained here: every list is derived from the two frames, and the picker never builds an id.
 */
data class ExecutorPickerModel(
    /** The `providers` frame: the harnesses on offer, in the daemon's order. */
    val harnesses: List<ProviderInfo> = emptyList(),
    /** The `models` frame: one row per (harness, vendor group). */
    val catalog: List<ProviderModels> = emptyList(),
    val harness: String? = null,
    /** The vendor the user narrowed to; null = All. Read [selectedGroup] for the effective value. */
    val group: String? = null,
    val model: String? = null,
    val providerQuery: String = "",
    val modelQuery: String = "",
) {
    /** The chosen harness's rows that actually list models. A failed or empty row is not selectable. */
    private val listedRows: List<ProviderModels>
        get() = if (harness == null) emptyList() else catalog.filter { it.provider == harness && it.models.isNotEmpty() }

    private val groups: List<String> get() = listedRows.map { it.group }.distinct()

    /** The chosen harness lists no models at all (no row, or only failed rows): the model stays a free-text field. */
    val freeText: Boolean get() = harness != null && listedRows.isEmpty()

    /** The harness's own default model, shown beside the free-text field. */
    val freeTextDefault: String? get() = if (freeText) harnesses.firstOrNull { it.id == harness }?.defaultModel else null

    /** Every note the chosen harness's rows carry ("could not list models: ..."), so an empty list explains itself. */
    val notes: List<String> get() = catalog.filter { it.provider == harness }.mapNotNull { it.note }

    /** The vendor in force: the one chosen, else the harness's only one, else null (All). */
    val selectedGroup: String? get() = group?.takeIf { it in groups } ?: groups.singleOrNull()

    /** The harness has exactly one vendor, so the Provider step is just a label. */
    val providerCollapsed: Boolean get() = groups.size == 1

    /** The Provider list, narrowed by what was typed, best match first. */
    val providerOptions: List<ProviderOption>
        get() = filterRanked(
            groups.map { g ->
                ProviderOption(g, g.ifEmpty { harness.orEmpty() }, listedRows.filter { it.group == g }.sumOf { it.models.size })
            },
            providerQuery,
        ) { SearchText(id = it.group, label = it.label) }

    private val vendorModels: List<ModelOption>
        get() = listedRows
            .filter { row -> selectedGroup?.let { row.group == it } ?: true }
            .flatMap { row -> row.models.map { ModelOption(it, row.group, it.substringAfterLast('/')) } }

    /** The Model list: inside the chosen vendor, narrowed by what was typed, best match first. */
    val modelOptions: List<ModelOption>
        get() = filterRanked(vendorModels, modelQuery) { SearchText(id = it.id, label = it.label, group = it.group) }

    /** The stored model is not in the catalog (a stale role default): kept verbatim, never rewritten, but flagged. */
    val modelMissing: Boolean
        get() = model != null && listedRows.isNotEmpty() && listedRows.none { model in it.models }

    /** The value a spawn / binding carries: the harness as the wire provider, the model or null for the harness default. */
    val picked: PickedModel? get() = harness?.let { PickedModel(it, model?.takeIf { m -> m.isNotBlank() }) }

    /**
     * DEMO-RESTRICT (ticket f9e181af): why harness [id] cannot be chosen, or null when it can: its CLI is missing
     * (`providers` says unavailable), or the `models` frame marks it unavailable (it lists no models, or the daemon's
     * model allowlist names none of them). A harness the frames say nothing about stays choosable.
     */
    fun unavailableReason(id: String): String? {
        val info = harnesses.firstOrNull { it.id == id } ?: return null
        if (!info.available) return info.unavailableReason?.takeIf { it.isNotBlank() } ?: "the daemon cannot find its CLI"
        val rows = catalog.filter { it.provider == id }
        if (rows.isEmpty() || rows.any { it.models.isNotEmpty() } || rows.none { it.unavailable }) return null
        return rows.filter { it.unavailable }.mapNotNull { it.note }.joinToString("; ").ifBlank { "it lists no models" }
    }

    /** DEMO-RESTRICT: [id] can be picked (see [unavailableReason]). */
    fun selectable(id: String): Boolean = unavailableReason(id) == null

    /**
     * Choose a harness. A vendor or model the new harness does not list is cleared (a model id belongs to one
     * harness); one it also lists is kept. The open search boxes close. An unknown id changes nothing, and so does
     * (DEMO-RESTRICT) an unavailable one unless [force] (a stored choice being shown, see [seeded]).
     */
    fun withHarness(id: String, force: Boolean = false): ExecutorPickerModel {
        if (id == harness || harnesses.none { it.id == id }) return this
        if (!force && !selectable(id)) return this
        val next = copy(harness = id, providerQuery = "", modelQuery = "")
        val keptGroup = group?.takeIf { it in next.groups }
        val keptModel = model?.takeIf { m -> next.listedRows.any { m in it.models && (keptGroup == null || it.group == keptGroup) } }
        return next.copy(group = keptGroup, model = keptModel)
    }

    /** Narrow to a vendor (null = All). The model survives only while the new list still offers it. */
    fun withGroup(picked: String?): ExecutorPickerModel {
        if (freeText || (picked != null && picked !in groups)) return this
        val next = copy(group = picked, providerQuery = "")
        return next.copy(model = model?.takeIf { m -> next.vendorModels.any { it.id == m } })
    }

    /**
     * Choose a model. With a vendor in force the model must be one of its; on All it must be listed anywhere and
     * the vendor follows it (the reverse arrow), [group] settling an id two vendors list. A model the catalog does
     * not list is refused; a free-text harness takes any text, and blank means the harness default.
     */
    fun withModel(id: String, group: String? = null): ExecutorPickerModel {
        if (harness == null) return this
        val text = id.trim()
        if (text.isEmpty()) return copy(model = null, modelQuery = "")
        if (freeText) return copy(model = text, modelQuery = "")
        val wanted = selectedGroup ?: group
        val row = listedRows.firstOrNull { text in it.models && (wanted == null || it.group == wanted) } ?: return this
        return copy(group = row.group, model = text, providerQuery = "", modelQuery = "")
    }

    fun withProviderQuery(q: String): ExecutorPickerModel = copy(providerQuery = q)

    fun withModelQuery(q: String): ExecutorPickerModel = copy(modelQuery = q)

    /**
     * PICKER-UX: the chosen harness's catalog lists exactly one model across every vendor (a deployment that allows
     * one model): that model, else null. Not narrowed by the vendor or the search.
     */
    val soleModel: ModelOption?
        get() = listedRows.flatMap { row -> row.models.map { ModelOption(it, row.group, it.substringAfterLast('/')) } }
            .distinctBy { it.id }.singleOrNull()

    /** PICKER-UX: with nothing picked and a [soleModel], pick it (there is no other choice to make). */
    fun withSoleModel(): ExecutorPickerModel {
        val sole = soleModel ?: return this
        return if (model == null) withModel(sole.id, sole.group) else this
    }

    companion object {
        /**
         * PICKER-ALL-2: the `models` frame, plus a row per vendor for any harness the frame does not list but whose
         * `providers` entry carries its own model list (`knownModels()`), grouped by the id's prefix. So a profile form
         * opened before the `models` frame landed still offers a searchable list instead of a blind text box.
         */
        fun catalogWithProviderLists(harnesses: List<ProviderInfo>, catalog: List<ProviderModels>): List<ProviderModels> {
            val listed = catalog.filter { it.models.isNotEmpty() }.map { it.provider }.toSet()
            val extra = harnesses.filter { it.id !in listed && !it.models.isNullOrEmpty() }.flatMap { h ->
                groupModels(h.models.orEmpty()).map { (g, ids) -> ProviderModels(h.id, g, g.ifEmpty { h.id }, ids) }
            }
            return catalog + extra
        }

        /**
         * The picker opened on an existing choice (a role default, an agent's override). A harness the daemon does not
         * report selects nothing; a stored model the catalog no longer lists is kept verbatim (see [modelMissing]).
         */
        fun seeded(harnesses: List<ProviderInfo>, catalog: List<ProviderModels>, picked: PickedModel?): ExecutorPickerModel {
            val base = ExecutorPickerModel(harnesses, catalog)
            if (picked == null) return base
            // DEMO-RESTRICT: a stored choice is shown as it is, even on a harness that cannot be picked now.
            val chosen = base.withHarness(picked.provider, force = true)
            if (chosen.harness == null || picked.model.isNullOrBlank()) return chosen
            val withModel = chosen.withModel(picked.model)
            return if (withModel.model == picked.model) withModel else chosen.copy(model = picked.model)
        }
    }
}
