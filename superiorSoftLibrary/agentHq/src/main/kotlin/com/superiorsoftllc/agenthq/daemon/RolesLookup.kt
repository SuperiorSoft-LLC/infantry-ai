package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.roles.RoleRecord
import com.superiorsoftllc.agenthq.daemon.api.roles.Roles

/**
 * AR-4b: how core reads roles, through the AR-5 registry on every call. No provider = no roles: a session spawns with no persona
 * and a role id is unknown. The absence is logged once.
 */
class RolesLookup(private val source: () -> Roles?) {
    @Volatile private var logged = false

    private fun port(): Roles? = runCatching(source).getOrNull().also {
        if (it == null && !logged) { logged = true; System.err.println("[roles] no Roles provider loaded: sessions start with no persona") }
    }

    fun all(): List<RoleRecord> = port()?.all().orEmpty()
    fun role(id: String): RoleRecord? = port()?.role(id)
}

/** AR-4b-ii-a: how core reads a role's binding and trait list, through the ports on every call. No provider = none. */
class RoleLists(
    private val bindings: () -> com.superiorsoftllc.agenthq.daemon.api.roles.RoleBindings?,
    private val traits: () -> com.superiorsoftllc.agenthq.daemon.api.roles.RoleTraitLists?,
) {
    fun binding(roleId: String): com.superiorsoftllc.agenthq.protocol.RoleBinding? = runCatching { bindings()?.get(roleId) }.getOrNull()
    fun traits(roleId: String): com.superiorsoftllc.agenthq.protocol.RoleTraits? = runCatching { traits()?.get(roleId) }.getOrNull()
}
