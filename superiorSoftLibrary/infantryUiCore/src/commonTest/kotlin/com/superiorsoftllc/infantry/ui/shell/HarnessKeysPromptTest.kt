// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.shell.blocks.missingHarnessKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessKeysPromptTest {
    private val rows = listOf(
        SecretRow("update-token", "Update token", set = false),
        SecretRow("harness.opencode.key", "OpenCode API key", set = false, help = "h"),
        SecretRow("harness.commandcode.key", "Command Code API key", set = true),
        SecretRow("harness.pi.key", "Pi API key", set = false),
    )

    @Test
    fun `only unset harness keys that were not skipped are asked for, built-ins never`() {
        assertEquals(listOf("harness.opencode.key", "harness.pi.key"), missingHarnessKeys(rows, emptySet()).map { it.name })
        assertEquals(listOf("harness.pi.key"), missingHarnessKeys(rows, setOf("harness.opencode.key")).map { it.name })
        assertEquals(emptyList(), missingHarnessKeys(rows.map { it.copy(set = true) }, emptySet()))
    }

    @Test
    fun `a harness set to subscription login is not asked for a key`() {
        val sub = rows.map { if (it.name == "harness.opencode.key") it.copy(authModes = listOf("api-key", "subscription-login"), authMode = "subscription-login") else it }
        assertEquals(listOf("harness.pi.key"), missingHarnessKeys(sub, emptySet()).map { it.name })
    }
}
