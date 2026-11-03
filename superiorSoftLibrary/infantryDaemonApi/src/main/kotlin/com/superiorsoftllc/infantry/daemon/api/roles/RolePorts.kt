// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.roles

import kotlinx.serialization.Serializable

/**
 * AR-4b-ii-a (DaemonApi 1.33.0, additive; docs/DAEMON-CORE.md section 8): the ports the role binding / trait handlers and the spawn
 * path read through, so the role store can move into Infantry_Roles. Services in the AR-5 registry (`services.find(...)`); core
 * registers local providers over its own store until the plugin takes them over. Consumers own the absent-provider case: no
 * provider = no bindings / no trait lists (a session runs on the daemon defaults).
 */
interface RoleBindings {
    fun get(roleId: String): RoleBindingRecord?
    fun all(): List<RoleBindingRecord>
    fun set(binding: RoleBindingRecord, at: Long)
}

interface RoleTraitLists {
    fun get(roleId: String): RoleTraitsRecord?
    fun all(): List<RoleTraitsRecord>

    /** Stores [traitIds]; [beforeWrite] runs first inside the same transaction and throws to refuse. */
    fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit = {}): RoleTraitsRecord
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

/**
 * AR-4b-ii-b (DaemonApi 1.34.0): the port DTOs are the API's own, never the wire module's, because a plugin's class loader sees
 * the daemon API but not `com.superiorsoftllc.infantry.protocol`, so a protocol type crossing a port would not link. Field for
 * field the wire `RoleBinding` / `RoleTraits`.
 */
@Serializable
data class RoleBindingRecord(
    val roleId: String,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class RoleTraitsRecord(
    val roleId: String,
    val traitIds: List<String> = emptyList(),
    val updatedAt: Long = 0,
)
