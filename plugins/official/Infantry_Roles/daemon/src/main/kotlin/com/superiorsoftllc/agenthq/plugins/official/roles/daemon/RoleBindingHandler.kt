package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.protocol.RoleBinding

import com.superiorsoftllc.agenthq.daemon.api.roles.RoleBindingRecord
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleBindings
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleEnvironment
import com.superiorsoftllc.agenthq.protocol.RoleBindingSave

/**
 * ROLE-1c: validates and stores a role's binding. The role must exist in the catalog and the provider must be
 * one the live registry lists ([providerIds]: code plugins and JSON descriptors alike), so a typo is refused
 * instead of silently pinning a role to a provider that can never spawn. Only future spawns see the change.
 */
class RoleBindingHandler(
    private val repository: RoleBindings,
    private val roles: RoleService,
    /** AR-4b-ii-a: providers, effort levels and the model allowlist, read through the port. */
    private val env: RoleEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun all(): List<RoleBinding> = repository.all().map { it.toWire() }

    /** The stored binding, or an unpinned one for a role that has none: a get never fails on "no binding yet". */
    fun get(roleId: String): RoleBinding = repository.get(roleId)?.toWire() ?: RoleBinding(roleId)

    /** Stores [save] and returns it as stored. Throws [IllegalArgumentException] naming the problem; nothing is written then. */
    fun set(save: RoleBindingSave): RoleBinding {
        val roleId = save.roleId.trim()
        require(roles.all().any { it.id == roleId }) { "unknown role '$roleId'" }
        val provider = save.provider?.trim()?.ifEmpty { null }
        require(provider == null || provider in env.providerIds()) { "unknown provider '$provider': the daemon lists ${env.providerIds().sorted()}" }
        val effort = save.effort?.trim()?.ifEmpty { null }
        require(effort == null || effort in env.effortLevels()) { "unknown effort '$effort': one of ${env.effortLevels().toList()}" }
        val model = save.model?.trim()?.ifEmpty { null }
        model?.let(env::checkModel)
        val binding = RoleBinding(roleId, provider, model, effort)
        val at = clock()
        repository.set(RoleBindingRecord(binding.roleId, binding.provider, binding.model, binding.effort), at)
        return binding.copy(updatedAt = at)
    }
}

private fun RoleBindingRecord.toWire() = RoleBinding(roleId, provider, model, effort, updatedAt)
