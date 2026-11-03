package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import kotlin.test.Test
import kotlin.test.assertFalse

/** RENAME-1: no built-in persona greets the agent with the product's old name. */
class PersonaNameScrubTest {
    @Test
    fun noBuiltInPersonaNamesTheOldProduct() {
        val oldName = Regex("agent\s*hq", RegexOption.IGNORE_CASE)
        RoleBundle.load(RoleBundle.classpathReader()).forEach { role ->
            assertFalse(oldName.containsMatchIn(role.persona), "persona of '${role.id}' says the old product name")
        }
    }
}
