package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.daemon.api.roles.RoleEnvironment
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleTraitLists
import com.superiorsoftllc.agenthq.protocol.RoleTraits
import com.superiorsoftllc.agenthq.protocol.RoleTraitsSave

/**
 * TRAITS-T2 (docs/AGENTHQ-ROLE-TRAITS.md section 2, D2): validates and stores a role's ordered trait list, the
 * [RoleBindingHandler] pattern. The role must exist in the catalog; every id must name a stored trait (checked inside the
 * write's transaction, so a concurrent `trait_delete` cannot leave a dangling id); at most [MAX_TRAITS]; no duplicates.
 * `[]` clears the list. A refusal is an [IllegalArgumentException] naming the problem, nothing written.
 */
class RoleTraitHandler(
    private val repository: RoleTraitLists,
    private val env: RoleEnvironment,
    private val roleExists: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun all(): List<RoleTraits> = repository.all().map { it.toWire() }

    /** The stored list, or an empty one for a role that has none: a get never fails on "no traits yet". */
    fun get(roleId: String): RoleTraits = repository.get(roleId)?.toWire() ?: RoleTraits(roleId)

    fun set(save: RoleTraitsSave): RoleTraits {
        val roleId = save.roleId.trim()
        require(roleExists(roleId)) { "unknown role '${roleId.take(40)}'" }
        val ids = save.traitIds.map { it.trim() }
        require(ids.size <= MAX_TRAITS) { "a role binds at most $MAX_TRAITS traits; got ${ids.size}" }
        ids.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("trait '${it.first().take(40)}' is listed twice")
        }
        return repository.set(roleId, ids, clock()) {
            ids.firstOrNull { !env.traitExists(it) }?.let { throw IllegalArgumentException("unknown trait '${it.take(40)}' (trait_list shows the library)") }
        }.toWire()
    }

    companion object {
        const val MAX_TRAITS = 8
    }
}

private fun com.superiorsoftllc.agenthq.daemon.api.roles.RoleTraitsRecord.toWire() = RoleTraits(roleId, traitIds, updatedAt)
