package com.superiorsoftllc.agenthq.daemon.api.roles

import com.superiorsoftllc.agenthq.protocol.RoleBinding
import com.superiorsoftllc.agenthq.protocol.RoleTraits

/**
 * AR-4b-ii-a (DaemonApi 1.33.0, additive; docs/DAEMON-CORE.md section 8): the ports the role binding / trait handlers and the spawn
 * path read through, so the role store can move into Infantry_Roles. Services in the AR-5 registry (`services.find(...)`); core
 * registers local providers over its own store until the plugin takes them over. Consumers own the absent-provider case: no
 * provider = no bindings / no trait lists (a session runs on the daemon defaults).
 */
interface RoleBindings {
    fun get(roleId: String): RoleBinding?
    fun all(): List<RoleBinding>
    fun set(binding: RoleBinding, at: Long)
}

interface RoleTraitLists {
    fun get(roleId: String): RoleTraits?
    fun all(): List<RoleTraits>

    /** Stores [traitIds]; [beforeWrite] runs first inside the same transaction and throws to refuse. */
    fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit = {}): RoleTraits
}

/** What validating a binding / trait list / role delete needs from core: providers, models, traits and live sessions. */
interface RoleEnvironment {
    /** Ids of the providers the daemon lists. */
    fun providerIds(): Set<String>

    /** The reasoning-effort levels a binding may name. */
    fun effortLevels(): Set<String>

    /** Throws [IllegalArgumentException] when the model allowlist refuses [model]. */
    fun checkModel(model: String)

    /** True when the trait library holds [traitId]. */
    fun traitExists(traitId: String): Boolean

    /** Normalised role ids of the sessions that are not ended. */
    fun liveRoleIds(): Set<String>
}
