package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.protocol.RoleIds
import com.superiorsoftllc.agenthq.role.LocalRoleBindings
import com.superiorsoftllc.agenthq.role.LocalRoleEnvironment
import com.superiorsoftllc.agenthq.role.LocalRoleTraitLists
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState

/** AR-4b-ii-a: core's local providers of the role binding / trait / environment ports, built once over the daemon's own parts. */
class RolePorts(val bindings: LocalRoleBindings, val traits: LocalRoleTraitLists, val environment: LocalRoleEnvironment) {
    companion object {
        fun of(store: AgentHqStore, manager: SessionManager, harnesses: HarnessRegistry?) = RolePorts(
            LocalRoleBindings(store.roleBindings),
            LocalRoleTraitLists(store.roleTraits),
            LocalRoleEnvironment(
                providerIds = { providerInfos(manager, harnesses).map { it.id }.toSet() },
                effortLevels = com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.EFFORT_LEVELS.toSet(),
                modelCheck = { manager.requireAllowedModel(it, null) },
                traitExists = { store.traits.get(it) != null },
                liveRoleIds = {
                    manager.list().filter { it.state != SessionState.EXITED && it.state != SessionState.FAILED && it.state != SessionState.ORPHANED }
                        .mapNotNull { it.role?.let(RoleIds::normalize) }.toSet()
                },
            ),
        )
    }
}
