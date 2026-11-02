package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.protocol.RoleBinding

import com.superiorsoftllc.agenthq.persistence.RoleBindingRepository
import com.superiorsoftllc.agenthq.protocol.RoleBindingSave

/**
 * ROLE-1c: validates and stores a role's binding. The role must exist in the catalog and the provider must be
 * one the live registry lists ([providerIds]: code plugins and JSON descriptors alike), so a typo is refused
 * instead of silently pinning a role to a provider that can never spawn. Only future spawns see the change.
 */
class RoleBindingHandler(
    private val repository: RoleBindingRepository,
    private val roles: RoleService,
    private val providerIds: () -> Set<String>,
    private val effortLevels: Set<String>,
    /** LIMITS: refuses a model the allowlist does not name (throws [IllegalArgumentException]); default = any. */
    private val modelCheck: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun all(): List<RoleBinding> = repository.all()

    /** The stored binding, or an unpinned one for a role that has none: a get never fails on "no binding yet". */
    fun get(roleId: String): RoleBinding = repository.get(roleId) ?: RoleBinding(roleId)

    /** Stores [save] and returns it as stored. Throws [IllegalArgumentException] naming the problem; nothing is written then. */
    fun set(save: RoleBindingSave): RoleBinding {
        val roleId = save.roleId.trim()
        require(roles.all().any { it.id == roleId }) { "unknown role '$roleId'" }
        val provider = save.provider?.trim()?.ifEmpty { null }
        require(provider == null || provider in providerIds()) { "unknown provider '$provider': the daemon lists ${providerIds().sorted()}" }
        val effort = save.effort?.trim()?.ifEmpty { null }
        require(effort == null || effort in effortLevels) { "unknown effort '$effort': one of ${effortLevels.toList()}" }
        val model = save.model?.trim()?.ifEmpty { null }
        model?.let(modelCheck)
        val binding = RoleBinding(roleId, provider, model, effort)
        val at = clock()
        repository.set(binding, at)
        return binding.copy(updatedAt = at)
    }
}
