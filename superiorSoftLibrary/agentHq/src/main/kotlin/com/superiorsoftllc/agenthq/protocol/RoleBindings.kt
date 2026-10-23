package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.40.0 (ROLE-1c): the payload of `role_binding_set` -- what a role runs on.
 *
 * A null or blank [provider] / [model] / [effort] means "no pin" for that field (the daemon default applies at
 * spawn time). [provider] must be an id the live `providers` frame lists; [effort] one of the harness effort
 * levels. Decoded strictly, like `role_save`: a misspelt key is refused, not silently dropped. The answer is
 * the stored `role_binding` frame (the `RoleBinding` type), pushed to every client.
 */
@Serializable
data class RoleBindingSave(
    val roleId: String,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
) {
    companion object {
        fun decode(text: String): RoleBindingSave = StrictJson.decode(serializer(), text, "role_binding_set payload")
    }
}
