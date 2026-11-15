// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-POLISH-1: the owner-port rule is data; the installed app prints no owner-port text. */
class HealthOwnerPortsTest {
    @Test
    fun `installed profile has no owner ports by default`() {
        assertEquals(emptyList(), HealthPorts.owner { if (it == "INFANTRY_OWN_URL_ONLY") "1" else null })
    }

    @Test
    fun `a setting names the owner ports and wins`() {
        assertEquals(listOf(1, 2), HealthPorts.owner { if (it == "INFANTRY_OWNER_PORTS") "1, 2" else "1" })
        assertEquals(emptyList(), HealthPorts.owner { if (it == "INFANTRY_OWNER_PORTS") "" else null })
    }

    @Test
    fun `dev default keeps the dev list`() {
        assertEquals(HealthPorts.DEV_OWNER, HealthPorts.owner { null })
    }

    @Test
    fun `brief line omits ports when none`() {
        val e = EnvironmentFacts(os = "x", arch = "a", shells = "s", projectPath = null, worktreePath = null, gitBranch = null, jdk = "1",
            buildTools = emptyMap(), ownerPorts = emptyList(), testPortRange = "1-2", harnesses = emptyList(), stateDir = "d", cliPath = null)
        assertFalse(EnvironmentProbe.briefLine(e).contains("off-limits"))
        assertTrue(EnvironmentProbe.briefLine(e).contains("infantry health"))
    }
}
