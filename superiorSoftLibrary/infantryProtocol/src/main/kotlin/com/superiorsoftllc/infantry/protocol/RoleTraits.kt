package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.49.0 (TRAITS-T2, docs/INFANTRY-ROLE-TRAITS.md section 2): the payload of `role_traits_set` -- the ordered
 * trait ids a role binds. [traitIds] replaces the role's whole list; `[]` clears it. The role must exist in the catalog,
 * every id must name a stored trait, at most 8, no duplicates. Decoded strictly, like `role_binding_set`.
 */
@Serializable
data class RoleTraitsSave(
    val roleId: String,
    val traitIds: List<String>,
) {
    companion object {
        fun decode(text: String): RoleTraitsSave = StrictJson.decode(serializer(), text, "role_traits_set payload")
    }
}

/**
 * Contract 1.49.0: a role's stored trait list, as the `role_traits` frame (one) and `role_traits_all` (every role with a
 * row, by role id) carry it. A role with no row answers `traitIds = []`, `updatedAt = 0`.
 */
@Serializable
data class RoleTraits(
    val roleId: String,
    val traitIds: List<String> = emptyList(),
    val updatedAt: Long = 0,
)
