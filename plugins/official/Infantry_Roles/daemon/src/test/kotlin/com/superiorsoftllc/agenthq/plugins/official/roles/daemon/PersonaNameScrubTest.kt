package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import kotlin.test.Test
import kotlin.test.assertFalse

/** RENAME-1: no built-in persona greets the agent with the product's old name. */
class PersonaNameScrubTest {
    @Test
    fun noBuiltInPersonaNamesTheOldProduct() {
        // contract names (`agenthq` CLI, agenthq_* tools) are lowercase and allowed
        val oldName = Regex("AgentHQ|Agent HQ|AgentHq|Agent hq")
        RoleBundle.load(RoleBundle.classpathReader()).forEach { role ->
            assertFalse(oldName.containsMatchIn(role.persona), "persona of '${role.id}' says the old product name")
        }
    }
}
