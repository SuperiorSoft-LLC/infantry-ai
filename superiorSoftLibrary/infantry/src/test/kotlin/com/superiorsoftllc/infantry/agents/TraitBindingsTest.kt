// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.RoleTraits
import kotlin.test.Test
import kotlin.test.assertEquals

/** TRAITS-T3: the effective trait set -- role traits first in their order, then agent additions, duplicates dropped (first wins). */
class TraitBindingsTest {
    private val agent = AgentProfile(id = "a1", name = "A", role = "backend")

    @Test
    fun roleTraitsComeFirstThenAgentAdditionsInOrder() {
        val role = RoleTraits("backend", listOf("report-back", "no-force-push"))
        assertEquals(
            listOf("report-back", "no-force-push", "tdd", "small-diffs"),
            TraitBindings.effective(agent.copy(traitIds = listOf("tdd", "small-diffs")), role),
        )
    }

    @Test
    fun duplicatesAreDroppedFirstWinsSoAnAgentCannotReorderOrRemoveARoleTrait() {
        val role = RoleTraits("backend", listOf("report-back", "no-force-push"))
        assertEquals(
            listOf("report-back", "no-force-push", "tdd"),
            TraitBindings.effective(agent.copy(traitIds = listOf("no-force-push", "tdd", "report-back", "tdd")), role),
        )
    }

    @Test
    fun missingRoleListOrProfileOrAdditionsAreEmpty() {
        assertEquals(emptyList(), TraitBindings.effective(null, null))
        assertEquals(listOf("tdd"), TraitBindings.effective(agent.copy(traitIds = listOf("tdd")), null))
        assertEquals(listOf("report-back"), TraitBindings.effective(agent, RoleTraits("backend", listOf("report-back"))))
    }
}
