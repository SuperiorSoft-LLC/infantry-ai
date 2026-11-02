package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.RoleTraits

/**
 * TRAITS-T3 (docs/AGENTHQ-ROLE-TRAITS.md section 2): the ONE place the effective trait set is computed. Pure: no store,
 * no clock. The effective set is the role's list in its order, then the agent's additions in theirs, duplicates dropped
 * (first wins). An agent may only ADD: it has no way to remove a role trait in v1, because the Owner binds every rule
 * (REPORT BACK included) to the role and removal would let any agent opt out of it.
 */
object TraitBindings {
    fun effective(roleTraitIds: List<String>, agentTraitIds: List<String>): List<String> =
        LinkedHashSet<String>().apply { addAll(roleTraitIds); addAll(agentTraitIds) }.toList()

    /** [effective] for a stored [profile] (null = an anonymous spawn, no additions) and its [role]'s list (null = none). */
    fun effective(profile: AgentProfile?, role: RoleTraits?): List<String> =
        effective(role?.traitIds.orEmpty(), profile?.traitIds.orEmpty())
}
