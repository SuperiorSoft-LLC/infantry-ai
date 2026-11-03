// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import kotlin.test.Test
import kotlin.test.assertFalse

/** RENAME-1: no built-in persona greets the agent with the product's old name. */
class PersonaNameScrubTest {
    @Test
    fun noBuiltInPersonaNamesTheOldProduct() {
        // contract names (`infantry` CLI, infantry_* tools) are lowercase and allowed
        val oldName = Regex("Agent" + "HQ|Agent HQ|Agent hq")
        RoleBundle.load(RoleBundle.classpathReader()).forEach { role ->
            assertFalse(oldName.containsMatchIn(role.persona), "persona of '${role.id}' says the old product name")
        }
    }
}
