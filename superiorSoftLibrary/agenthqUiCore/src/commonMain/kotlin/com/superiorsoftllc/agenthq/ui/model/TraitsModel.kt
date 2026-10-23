package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.RoleTraits
import com.superiorsoftllc.agenthq.ui.protocol.TraitCheck
import com.superiorsoftllc.agenthq.ui.protocol.TraitSave
import com.superiorsoftllc.agenthq.ui.protocol.TraitView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * TRAITS-T8 (docs/AGENTHQ-ROLE-TRAITS.md section 6): the Roles tab trait editor, as pure state + functions.
 * Everything here is fed by the existing T1/T2 frames (`traits`, `trait_item`, `role_traits`, `role_traits_all`)
 * and produces the existing requests (`trait_save`, `trait_delete`, `role_traits_set`). No new wire.
 * The view (RolesAttention.kt) only renders [AppState] and calls these.
 */

private val traitJson = Json { ignoreUnknownKeys = true }

/** The daemon's own limits (section 2), mirrored so the editor can say what is wrong before sending. The daemon stays the authority. */
object TraitLimits {
    val ID_PATTERN = Regex("[a-z][a-z0-9-]{0,31}")
    val FIELD_PATTERN = Regex("[A-Za-z][A-Za-z0-9 _-]{0,31}")
    const val NAME_MAX = 80
    const val BODY_MAX_BYTES = 2048
    const val FIELDS_MAX = 12
    const val PER_ROLE_MAX = 8
}

/**
 * The trait frames, or null when [env] is not one (the caller then reduces it as usual).
 *
 * - `traits` is the whole library (a list answer or the push after a delete): it replaces, ordered by name.
 * - `trait_item` is one stored trait (pushed after a save): upsert by id.
 * - `role_traits` is one role's stored list (pushed after a set): an empty list removes the role (an unbind of the last trait).
 * - `role_traits_all` is every role's list: it replaces.
 * - A session-less `error` from a `trait_*` / `role_traits_*` request lands in [AppState.traitError] and nothing else
 *   changes: the daemon wrote nothing. An OLD daemon answers `unknown frame type 'trait_list'`: that ends the loading
 *   state silently and the library stays empty (no notice on every connect).
 */
fun reduceTraitFrame(s: AppState, env: Envelope, now: Long): AppState? = when (env.type) {
    Envelope.TRAITS -> {
        val rows = traitJson.decodeFromString(ListSerializer(TraitView.serializer()), env.data)
        s.copy(traits = rows.sortedBy { it.name.lowercase() }, traitsLoading = false, traitError = null)
    }
    Envelope.TRAIT_ITEM -> {
        val t = traitJson.decodeFromString(TraitView.serializer(), env.data)
        s.copy(traits = (s.traits.filterNot { it.id == t.id } + t).sortedBy { it.name.lowercase() }, traitError = null)
    }
    Envelope.ROLE_TRAITS -> {
        val r = traitJson.decodeFromString(RoleTraits.serializer(), env.data)
        // An unbind is the push of the shorter list; the last unbind is `[]`, which removes the role.
        val next = if (r.traitIds.isEmpty()) s.roleTraits - r.roleId else s.roleTraits + (r.roleId to r.traitIds)
        s.copy(roleTraits = next, traitError = null)
    }
    Envelope.ROLE_TRAITS_ALL -> {
        val rows = traitJson.decodeFromString(ListSerializer(RoleTraits.serializer()), env.data)
        s.copy(roleTraits = rows.filter { it.traitIds.isNotEmpty() }.associate { it.roleId to it.traitIds }, traitError = null)
    }
    Envelope.ERROR -> when {
        env.sessionId != null -> null
        env.data.startsWith("unknown frame type 'trait_") || env.data.startsWith("unknown frame type 'role_traits_") ->
            s.copy(traitsLoading = false)
        env.data.startsWith("trait_") || env.data.startsWith("role_traits_") ->
            s.copy(traitError = env.data, traitsLoading = false).withNotice(now, env.data, null, NoticeKind.ERROR)
        else -> null
    }
    else -> null
}

/** `trait_list` was sent: show the loading state and clear the last refusal. */
fun AppState.withTraitsRequested(): AppState = copy(traitsLoading = true, traitError = null)

/** [roleId]'s ordered trait list as the daemon last stored it (`[]` when none or an old daemon). */
fun AppState.traitIdsFor(roleId: String): List<String> = roleTraits[roleId].orEmpty()

/** The stored traits bound to [roleId], in binding order; an id the library no longer has is skipped. */
fun AppState.traitsFor(roleId: String): List<TraitView> = traitIdsFor(roleId).mapNotNull { id -> traits.firstOrNull { it.id == id } }

/** The roles that bind [traitId] (the daemon refuses its delete while this is not empty). */
fun AppState.rolesBinding(traitId: String): List<String> = roleTraits.filterValues { traitId in it }.keys.sorted()

/** The list `role_traits_set` should carry to bind [traitId] at the end of [current]; unchanged when already bound. */
fun bindTrait(current: List<String>, traitId: String): List<String> = if (traitId in current) current else current + traitId

/** The list `role_traits_set` should carry to unbind [traitId] (an empty result clears the role). */
fun unbindTrait(current: List<String>, traitId: String): List<String> = current.filterNot { it == traitId }

/** The list with [traitId] moved one place up ([delta] = -1) or down (+1); unchanged at an edge or when absent. */
fun moveTrait(current: List<String>, traitId: String, delta: Int): List<String> {
    val i = current.indexOf(traitId)
    val j = i + delta
    if (i < 0 || j !in current.indices) return current
    return current.toMutableList().apply { removeAt(i); add(j, traitId) }
}

/**
 * The trait editor's form, check sub-form included. [fieldsText] is the comma separated label list.
 * [isNew] = the id is still editable (an existing trait keeps its id: save-as-update is by id).
 */
data class TraitDraft(
    val id: String = "",
    val name: String = "",
    val body: String = "",
    val checkOn: Boolean = false,
    val reportTo: String = TraitCheck.DEFAULT_REPORT_TO,
    val fieldsText: String = "",
    val reprompt: Boolean = true,
    val isNew: Boolean = true,
) {
    val fields: List<String> get() = fieldsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The first problem the daemon would refuse, or null when the draft can be sent. */
    fun problem(): String? = when {
        !TraitLimits.ID_PATTERN.matches(id.trim()) -> "id must be a slug: a-z, 0-9 and '-', starting with a letter, at most 32"
        name.isBlank() -> "name is required"
        name.trim().length > TraitLimits.NAME_MAX -> "name is at most ${TraitLimits.NAME_MAX} characters"
        body.isBlank() -> "body is required"
        body.encodeToByteArray().size > TraitLimits.BODY_MAX_BYTES -> "body is at most ${TraitLimits.BODY_MAX_BYTES} bytes; longer text belongs in a skill"
        checkOn && reportTo.isBlank() -> "report to needs a role id"
        checkOn && fields.isEmpty() -> "a report check needs at least one field"
        checkOn && fields.size > TraitLimits.FIELDS_MAX -> "a report check has at most ${TraitLimits.FIELDS_MAX} fields"
        checkOn && fields.any { !TraitLimits.FIELD_PATTERN.matches(it) } -> "a field label is a letter then letters, digits, space, '_' or '-'"
        checkOn && fields.map { it.lowercase() }.toSet().size != fields.size -> "field labels must be unique ignoring case"
        else -> null
    }

    /** The `trait_save` payload. */
    fun toSave(): TraitSave = TraitSave(
        id = id.trim(),
        name = name.trim(),
        body = body,
        check = if (checkOn) TraitCheck(TraitCheck.KIND_REPORT, reportTo.trim(), fields, reprompt) else null,
    )

    companion object {
        /** The form for editing a stored trait. */
        fun of(t: TraitView): TraitDraft = TraitDraft(
            id = t.id,
            name = t.name,
            body = t.body,
            checkOn = t.check != null,
            reportTo = t.check?.reportTo ?: TraitCheck.DEFAULT_REPORT_TO,
            fieldsText = t.check?.fields.orEmpty().joinToString(", "),
            reprompt = t.check?.reprompt ?: true,
            isNew = false,
        )
    }
}
