package com.superiorsoftllc.agenthq.role

/**
 * ROLE-1a: what a [Role] runs on. The role owns its [provider], [model] and [effort]; a null field means
 * "no pin" (the daemon default applies at spawn time, never a stored copy of today's default).
 *
 * Deliberately a separate small value keyed by [roleId], not a field of [Role]: the binding changes at
 * runtime while the role definition is catalog data. Future per-role definition parts (traits, skills,
 * ticket 12f20467) join the same role id the same way. Not a wire type in this slice.
 */
@kotlinx.serialization.Serializable
data class RoleBinding(
    val roleId: String,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val updatedAt: Long = 0,
)
