package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.daemon.api.roles.RoleBindings
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleEnvironment
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleTraitLists
import com.superiorsoftllc.agenthq.persistence.RoleBindingRepository
import com.superiorsoftllc.agenthq.persistence.RoleTraitRepository
import com.superiorsoftllc.agenthq.protocol.RoleBinding
import com.superiorsoftllc.agenthq.protocol.RoleTraits

/** AR-4b-ii-a: core's local providers of the role binding / trait / environment ports, over its own store. */
class LocalRoleBindings(private val repository: RoleBindingRepository) : RoleBindings {
    override fun get(roleId: String): RoleBinding? = repository.get(roleId)
    override fun all(): List<RoleBinding> = repository.all()
    override fun set(binding: RoleBinding, at: Long) = repository.set(binding, at)
}

class LocalRoleTraitLists(private val repository: RoleTraitRepository) : RoleTraitLists {
    override fun get(roleId: String): RoleTraits? = repository.get(roleId)
    override fun all(): List<RoleTraits> = repository.all()
    override fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit): RoleTraits =
        repository.set(roleId, traitIds, at, beforeWrite)
}

class LocalRoleEnvironment(
    private val providerIds: () -> Set<String>,
    private val effortLevels: Set<String>,
    private val modelCheck: (String) -> Unit,
    private val traitExists: (String) -> Boolean,
    private val liveRoleIds: () -> Set<String>,
) : RoleEnvironment {
    override fun providerIds() = providerIds.invoke()
    override fun effortLevels() = effortLevels
    override fun checkModel(model: String) = modelCheck(model)
    override fun traitExists(traitId: String) = traitExists.invoke(traitId)
    override fun liveRoleIds() = liveRoleIds.invoke()
}
