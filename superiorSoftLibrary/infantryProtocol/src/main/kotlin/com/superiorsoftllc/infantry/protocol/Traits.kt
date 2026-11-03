package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.48.0 (TRAITS-T1, docs/INFANTRY-ROLE-TRAITS.md section 2): the optional structured check of a trait.
 * v1 knows one [kind], `report`: the session must mail [reportTo] (a role id that exists in the catalog) one report
 * carrying every label in [fields]; [reprompt] = one re-prompt before an attention item. Enforcement is TRAITS-T6;
 * T1 only stores and validates it. Any other kind is refused at save.
 */
@Serializable
data class TraitCheck(
    val kind: String,
    val reportTo: String = DEFAULT_REPORT_TO,
    val fields: List<String> = emptyList(),
    val reprompt: Boolean = true,
) {
    companion object {
        const val KIND_REPORT = "report"
        const val DEFAULT_REPORT_TO = "pm"
    }
}

/**
 * Contract 1.48.0: the payload of `trait_save`. Save-as-update is by [id] (a slug chosen by the author), NOT by name:
 * an unknown id creates the trait at version 1, a known id updates it, and its version moves +1 only when name, body
 * or check actually changed (an identical save changes nothing). Decoded strictly, nested [TraitCheck] included.
 */
@Serializable
data class TraitSave(
    val id: String,
    val name: String,
    val body: String,
    val check: TraitCheck? = null,
) {
    companion object {
        fun decode(text: String): TraitSave = StrictJson.decode(serializer(), text, "trait_save payload")
    }
}

/** Contract 1.48.0: one stored trait, as the `trait_item` and `traits` frames carry it. */
@Serializable
data class TraitView(
    val id: String,
    val name: String,
    val version: Int,
    val body: String,
    val check: TraitCheck? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
