// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.12.0: the payload of `role_save` -- a custom role definition for the daemon's catalog.
 *
 * [id] follows the role-id contract (`[a-z][a-z0-9-]{0,31}`); a canonical id overrides that built-in
 * role on this daemon, any other id adds a role. [color] is `#rrggbb` (omitted = keep the role's current
 * colour, or a generated one); [persona] is the markdown a spawned agent of this role is meant to receive.
 * There is deliberately no `source`: where a role came from is the daemon's to say, never the client's.
 *
 * Decoded strictly, like `new` and `agent_save`: a misspelt key is refused, not silently dropped.
 */
@Serializable
data class RoleSave(
    val id: String,
    val name: String,
    val color: String? = null,
    val persona: String = "",
    /** ROLE-ICON-1 (contract 1.93.0): the icon key. Absent/null = keep the role's current icon; "" = clear it (client default). */
    val icon: String? = null,
    /** ROLE-EDIT-1 (contract 1.108.0): one-line description. Absent/null = keep the stored one; "" = clear it. */
    val description: String? = null,
) {
    companion object {
        fun decode(text: String): RoleSave = StrictJson.decode(serializer(), text, "role_save payload")
    }
}
