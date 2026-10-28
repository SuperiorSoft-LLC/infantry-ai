package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding

/**
 * UX slice 1: the state behind the New agent modal. Pure and immutable, so it is tested without a UI.
 * Flow: pick a [role] (searchable grid), then the [executor] (harness -> provider -> model, seeded from the
 * role's binding), type the first [task], press Add. Add is the existing spawn: `new` (with the role) + `prompt`.
 */
data class NewAgentModalModel(
    val roles: List<Role> = emptyList(),
    val bindings: Map<String, RoleBinding> = emptyMap(),
    val harnesses: List<ProviderInfo> = emptyList(),
    val catalog: List<ProviderModels> = emptyList(),
    val roleQuery: String = "",
    val roleId: String? = null,
    val executor: ExecutorPickerModel = ExecutorPickerModel(harnesses, catalog),
    val task: String = "",
) {
    /** The role grid, narrowed by what was typed (id and name), best match first. */
    val roleOptions: List<Role>
        get() = filterRanked(roles, roleQuery) { SearchText(id = it.id, label = it.name) }

    /** Nothing in the catalog yet (not connected, or the `roles` frame has not landed): the grid says so instead of looking empty. */
    val rolesLoading: Boolean get() = roles.isEmpty()

    /** The executor step stays closed until a role is chosen. */
    val executorOpen: Boolean get() = roleId != null

    /** The first required field still empty, top to bottom, or null when Add can go. The modal jumps to it. */
    val missingField: NewAgentField?
        get() = when {
            roleId == null -> NewAgentField.ROLE
            executor.harness == null -> NewAgentField.HARNESS
            executor.model == null -> NewAgentField.MODEL
            task.isBlank() -> NewAgentField.TASK
            else -> null
        }

    /** Why Add is disabled, or null when it is enabled. Shown beside the button, never silent. */
    val addBlockedReason: String?
        get() = when (missingField) {
            NewAgentField.ROLE -> "Pick a role first"
            NewAgentField.HARNESS -> "Pick a harness"
            NewAgentField.MODEL -> "Pick a model"
            NewAgentField.TASK -> "Type the agent's first task"
            null -> null
        }

    val canAdd: Boolean get() = addBlockedReason == null

    /**
     * Choose a role (an unknown id changes nothing); the executor re-seeds from that role's binding. PICKER-UX: a
     * harness that lists one model has it picked, so Add is not blocked on a choice there is none of.
     */
    fun withRole(id: String): NewAgentModalModel {
        if (roles.none { it.id == id }) return this
        return copy(roleId = id, executor = ExecutorPickerModel.seeded(harnesses, catalog, bindings[id]?.let { PickedModel.fromBinding(it) }).withSoleModel())
    }

    fun withRoleQuery(q: String): NewAgentModalModel = copy(roleQuery = q)

    fun withExecutor(next: ExecutorPickerModel): NewAgentModalModel = copy(executor = next.withSoleModel())

    fun withTask(text: String): NewAgentModalModel = copy(task = text)

    /** The `new` frame's data for Add: the picked harness+model (null = daemon defaults), the role, the role binding's effort. */
    fun spawnData(): String = spawnOptionsData(executor.picked, roleId?.let { bindings[it]?.effort }, roleId)
}

/** The New agent modal's required fields, in screen order. */
enum class NewAgentField { ROLE, HARNESS, MODEL, TASK }
