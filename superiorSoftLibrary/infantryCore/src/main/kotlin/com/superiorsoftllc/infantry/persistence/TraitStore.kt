// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.protocol.TraitSave
import com.superiorsoftllc.infantry.protocol.TraitView
import com.superiorsoftllc.infantry.protocol.RoleIds

/**
 * TRAITS-T1 (docs/INFANTRY-ROLE-TRAITS.md section 2, D1): the trait library. Owns every rule -- the slug id, the
 * limits, the `report` check validation and the versioning; the [TraitRepository] only stores rows. A refusal is an
 * [IllegalArgumentException] naming the problem and nothing is written.
 *
 * Versioning: a new id is created at version 1; saving a known id moves the version +1 only when name, body or check
 * differs from what is stored. An identical save changes nothing (same version, same updatedAt). [roleExists] answers
 * whether a role id is in the catalog (`reportTo` must name one). [boundBy] names what still binds a trait id (`role 'x'`,
 * `agent 'y'`); it is asked inside the delete's transaction, and a non-empty answer refuses the delete (TRAITS-T2/T3).
 */
class TraitStore(
    private val repository: TraitRepository,
    private val roleExists: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val boundBy: (String) -> List<String> = { emptyList() },
) {
    /** Creates the trait [TraitSave.id] at version 1, or updates it (a new version only when something changed). */
    fun save(save: TraitSave): TraitView {
        val id = save.id.trim()
        require(RoleIds.isValid(id)) { "id '${id.take(40)}' must match ${RoleIds.PATTERN.pattern} (a slug: lowercase letter first, at most 32 chars)" }
        val name = save.name.trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        require(name.length <= MAX_NAME_CHARS) { "name is ${name.length} chars; the limit is $MAX_NAME_CHARS" }
        require(save.body.isNotBlank()) { "body must not be blank" }
        val bodyBytes = save.body.toByteArray(Charsets.UTF_8).size
        require(bodyBytes <= MAX_BODY_BYTES) { "body is $bodyBytes bytes; the limit is $MAX_BODY_BYTES (2 KB): anything longer belongs in a skill" }
        val check = save.check?.let(::validCheck)
        val at = clock()
        return repository.saveWith(id) { current ->
            when {
                current == null -> TraitView(id, name, 1, save.body, check, at, at)
                current.name == name && current.body == save.body && current.check == check -> current
                else -> current.copy(name = name, version = current.version + 1, body = save.body, check = check, updatedAt = at)
            }
        }
    }

    /** Every trait, ordered by id. */
    fun list(): List<TraitView> = repository.all()

    fun get(id: String): TraitView? = repository.get(id)

    /**
     * Deletes the trait; false when there was none with that id. Refused with [IllegalArgumentException], nothing deleted,
     * while a role or an agent still binds it, so no id dangles.
     */
    fun delete(id: String): Boolean = repository.delete(id) {
        val refs = boundBy(id)
        require(refs.isEmpty()) { "trait '$id' is still bound to ${refs.joinToString()}: unbind it first" }
    }

    private fun validCheck(check: TraitCheck): TraitCheck {
        require(check.kind == TraitCheck.KIND_REPORT) { "check kind '${check.kind.take(40)}' is unknown; v1 knows only '${TraitCheck.KIND_REPORT}'" }
        val reportTo = RoleIds.normalize(check.reportTo.trim())
        require(RoleIds.isValid(reportTo) && roleExists(reportTo)) { "check reportTo '${reportTo.take(40)}' is not a role in the catalog" }
        val fields = check.fields.map { it.trim() }
        require(fields.size in 1..MAX_FIELDS) { "check fields has ${fields.size} labels; it needs 1..$MAX_FIELDS" }
        fields.firstOrNull { !FIELD_PATTERN.matches(it) }?.let {
            throw IllegalArgumentException("check field '${it.take(40)}' must match ${FIELD_PATTERN.pattern}")
        }
        fields.groupBy { it.lowercase() }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("check fields has a duplicate label '${it.first()}' (labels are unique ignoring case)")
        }
        return check.copy(reportTo = reportTo, fields = fields)
    }

    companion object {
        const val MAX_NAME_CHARS = 80
        const val MAX_BODY_BYTES = 2048
        const val MAX_FIELDS = 12
        val FIELD_PATTERN = Regex("[A-Za-z][A-Za-z0-9 _-]{0,31}")
    }
}

/** TRAITS-T2/T3: what still binds trait [id] -- `role 'x'` for each role row listing it, then `agent 'y'` for each profile adding it. */
fun InfantryStore.traitReferences(id: String, roleTraits: List<com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitsRecord> = emptyList()): List<String> =
    roleTraits.filter { id in it.traitIds }.map { "role '${it.roleId}'" } +
        agents.all().filter { id in it.traitIds.orEmpty() }.map { "agent '${it.id}'" }
