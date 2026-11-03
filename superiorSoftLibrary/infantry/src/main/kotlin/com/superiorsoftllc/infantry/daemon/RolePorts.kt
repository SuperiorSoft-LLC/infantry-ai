// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.roles.RoleEnvironment
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState

/**
 * AR-4b-ii-b: core's one role port, [RoleEnvironment]: what validating a role binding / trait list / role delete needs from core
 * (providers, models, the trait library, live sessions). The roles, bindings and trait lists are the Infantry_Roles plugin's.
 */
object RolePorts {
    fun environment(store: InfantryStore, manager: SessionManager, harnesses: HarnessRegistry?): RoleEnvironment = object : RoleEnvironment {
        override fun providerIds() = providerInfos(manager, harnesses).map { it.id }.toSet()
        override fun effortLevels() = com.superiorsoftllc.infantry.session.EffortLevels.ids.toSet()
        override fun checkModel(model: String) = manager.requireAllowedModel(model, null)
        override fun traitExists(traitId: String) = store.traits.get(traitId) != null
        override fun liveRoleIds(): Set<String> =
            manager.list().filter { it.state != SessionState.EXITED && it.state != SessionState.FAILED && it.state != SessionState.ORPHANED }
                .mapNotNull { it.role?.let(RoleIds::normalize) }.toSet()
    }
}
