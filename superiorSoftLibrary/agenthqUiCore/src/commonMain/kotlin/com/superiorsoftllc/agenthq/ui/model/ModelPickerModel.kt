package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo

/**
 * AGENT-UX-10: what the profile editor's model picker shows, derived ONLY from the `providers` frame.
 *
 * Nothing here is a hand-maintained catalogue: [options] is the list the daemon reported for the chosen
 * provider (read from the provider CLI itself), so the picker reflects opencode the day it changes. When
 * the daemon reports no list for a provider, the picker degrades to free text ([freeText]) instead of
 * disabling the form.
 */
data class ModelChoices(
    /** Model ids to offer, in the daemon's order, with [selected] surfaced first when the daemon no longer lists it. */
    val options: List<String>,
    /** The chosen provider's list is last-known data served after a failed refresh (the daemon said so). */
    val stale: Boolean,
    /** The stored model id is not among the reported options; it is kept verbatim, never rewritten. */
    val selectedMissing: Boolean,
    /** The chosen provider reported no model list at all, so the model stays a free-text field. */
    val freeText: Boolean,
)

/**
 * The model choices for the profile editor: the [providers] frame, the chosen [providerId] (or the
 * default provider when it names none) and the profile's stored [selected] model.
 *
 * The stored id is the VALUE, never a label: an option is the exact id the daemon expects, slash and
 * all. When the stored id is absent from the reported list it is surfaced (first, so it is not lost)
 * and flagged, because a stale id is information -- the editor never silently rewrites it.
 */
fun modelChoices(providers: List<ProviderInfo>, providerId: String?, selected: String?): ModelChoices {
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull { it.default }
    val stored = selected?.takeIf { it.isNotBlank() }
    // What the daemon reported for this provider: its real catalogue, or the single default model it
    // names when it has no catalogue. This is the ONLY source -- nothing here is a static list.
    val reported = provider?.models?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(provider?.defaultModel?.takeIf { it.isNotBlank() })
    val stale = provider?.modelsStale == true
    if (reported.isEmpty()) {
        // The provider reported no list at all: keep the form usable as free text rather than empty.
        return ModelChoices(options = listOfNotNull(stored), stale = stale, selectedMissing = false, freeText = true)
    }
    val missing = stored != null && stored !in reported
    return ModelChoices(
        // Surface a stale stored id FIRST so it is not lost; never rewrite it.
        options = if (missing) listOf(stored) + reported else reported,
        stale = stale,
        selectedMissing = missing,
        freeText = false,
    )
}

// ---------------------------------------------------------------------------------------------------
// UI-PROV-1: opencode answers ONE flat list of ~100 ids from many sub-providers (`opencode/...`,
// `opencode-go/...`, `deepseek/...`). The prefix is the thing a human chooses between, so the picker
// gets a second level and the model list is narrowed to the chosen sub-provider. Everything below is
// derived from the reported ids; nothing is a maintained catalogue.
// ---------------------------------------------------------------------------------------------------

/**
 * UI-PROV-1: the sub-providers a flat model list spans, keyed by the text before the first "/".
 *
 * An id that names no sub-provider (no slash at all) belongs to the "" group rather than being dropped,
 * and both the keys and the ids inside each group are sorted, so the picker never depends on the order
 * the CLI happened to print.
 */
fun groupModels(ids: List<String>): Map<String, List<String>> =
    ids.groupBy { subProviderOf(it) }
        .toSortedMap()
        .mapValues { (_, models) -> models.sorted() }

/** The sub-provider of ONE model id: the text before its FIRST "/", else "" when the id names none. */
fun subProviderOf(id: String): String = if ('/' in id) id.substringBefore('/') else ""

/**
 * UI-PROV-1: the two-level picker's choices -- the sub-providers on offer, the one shown, and the model
 * ids inside it. [model] is the value the form submits: the full wire id, or "" for "the provider's
 * default", and it is "" the moment it leaves [group].
 */
data class ModelGroupChoices(
    /** Every id the flat picker would offer, in the daemon's order (a stale stored id surfaced first). */
    val all: List<String>,
    /** The sub-providers and their ids, as [groupModels] returns them. */
    val byGroup: Map<String, List<String>>,
    /** The chosen sub-provider key; "" for ids that name none. */
    val group: String,
    /** The chosen model id, cleared when it left [group]. */
    val model: String,
) {
    /** The offered sub-providers, sorted; "" holds ids that name none. */
    val groups: List<String> get() = byGroup.keys.toList()

    /** The model ids of the chosen sub-provider, sorted. */
    val models: List<String> get() = byGroup[group].orEmpty()

    /** More than one sub-provider exists: the picker needs its group row. */
    val twoLevel: Boolean get() = byGroup.size > 1

    /**
     * Choosing another sub-provider: the model survives only while it is one of the new group's ids.
     * Keeping one the new list does not offer would leave the form holding a value no dropdown can
     * produce, so it is cleared back to "the provider's default". An unknown key changes nothing -- the UI
     * only ever passes a key it was offered.
     */
    fun withGroup(picked: String): ModelGroupChoices =
        if (picked !in byGroup) this
        else {
            val models = byGroup.getValue(picked)
            copy(group = picked, model = model.takeIf { it in models } ?: "")
        }
}

/**
 * UI-PROV-1: [modelChoices] narrowed to ONE sub-provider, for the two-level picker.
 *
 * Nothing is stored: the offered keys come from the ids the daemon reported -- plus a stale stored id,
 * which [modelChoices] surfaces first and which keeps a group of its own so it stays visible -- and the
 * shown group is the one the user last picked ([group]), else the stored model's own (so editing a
 * profile opens on the sub-provider its model belongs to), else the first key. [ModelGroupChoices.twoLevel]
 * is false when there is nothing to choose -- one sub-provider, or no catalogue at all -- and the caller
 * then keeps the flat picker (or the free-text field AGENT-UX-10 shipped).
 */
fun modelGroupChoices(
    providers: List<ProviderInfo>,
    providerId: String?,
    selected: String?,
    group: String? = null,
): ModelGroupChoices {
    val flat = modelChoices(providers, providerId, selected)
    val byGroup = groupModels(flat.options)
    val stored = selected?.takeIf { it.isNotBlank() }
    val chosen = group?.takeIf { it in byGroup }
        ?: stored?.let { subProviderOf(it) }?.takeIf { it in byGroup }
        ?: byGroup.keys.firstOrNull()
        ?: ""
    return ModelGroupChoices(
        all = flat.options,
        byGroup = byGroup,
        group = chosen,
        model = stored?.takeIf { it in byGroup[chosen].orEmpty() } ?: "",
    )
}
