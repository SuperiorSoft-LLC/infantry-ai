// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.shell.blocks.isPrimaryHarnessKey
import com.superiorsoftllc.infantry.ui.shell.blocks.missingHarnessKeys
import com.superiorsoftllc.infantry.ui.shell.blocks.hasAuthProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessKeysPromptTest {
    private val rows = listOf(
        SecretRow("update-token", "Update token", set = false),
        SecretRow("harness.opencode.key", "OpenCode API key", set = false, help = "h"),
        SecretRow("harness.commandcode.key", "Command Code API key", set = true),
        SecretRow("harness.pi.key", "Pi API key", set = false),
    )
    private val modes = listOf("api-key", "subscription-login")
    private val claude = SecretRow("harness.claude.key", "Claude Code API key", authModes = modes, loginCommand = "claude auth login")

    @Test
    fun `only unset harness keys that were not skipped are asked for, built-ins never`() {
        assertEquals(listOf("harness.opencode.key", "harness.pi.key"), missingHarnessKeys(rows, emptySet()).map { it.name })
        assertEquals(listOf("harness.pi.key"), missingHarnessKeys(rows, setOf("harness.opencode.key")).map { it.name })
        assertEquals(emptyList(), missingHarnessKeys(rows.map { it.copy(set = true) }, emptySet()))
    }

    @Test
    fun `a harness set to subscription login is not asked for a key`() {
        val sub = rows.map { if (it.name == "harness.opencode.key") it.copy(authModes = modes, authMode = "subscription-login") else it }
        assertEquals(listOf("harness.pi.key"), missingHarnessKeys(sub, emptySet()).map { it.name })
    }

    @Test
    fun `an optional extra credential an update adds is never asked for at start (the owner's every-update prompt)`() {
        // RUNNER-CRED-1 added harness.claude.oauth.key, HARNESS-MULTI-CREDENTIAL-1 harness.opencode.cmd.key: each was a new prompt.
        val skippedBefore = setOf("harness.claude.key", "harness.opencode.key")
        val after = listOf(
            claude, SecretRow("harness.claude.oauth.key", "Claude Code subscription token", authModes = modes),
            SecretRow("harness.opencode.key", "OpenCode API key"), SecretRow("harness.opencode.cmd.key", "Command Code"),
        )
        assertEquals(emptyList(), missingHarnessKeys(after, skippedBefore))
        assertFalse(isPrimaryHarnessKey("harness.claude.oauth.key"))
        assertTrue(isPrimaryHarnessKey("harness.claude.key"))
        assertFalse(isPrimaryHarnessKey("harness..key"))
    }

    @Test
    fun `a machine already signed in with the CLI is not asked, and a harness with a probe is probed first`() {
        assertTrue(hasAuthProbe(claude))
        assertEquals(emptyList(), missingHarnessKeys(listOf(claude.copy(signedIn = "true", lastProbe = 1L)), emptySet()))
        val signedOut = claude.copy(signedIn = "false", lastProbe = 1L)
        assertEquals(listOf("harness.claude.key"), missingHarnessKeys(listOf(signedOut), emptySet()).map { it.name })
        assertFalse(hasAuthProbe(SecretRow("harness.pi.key", "Pi API key")))
    }

    @Test
    fun `a key read from a shared secret is not asked for`() {
        assertEquals(emptyList(), missingHarnessKeys(listOf(SecretRow("harness.pi.key", "Pi API key", usesSecret = "shared")), emptySet()))
    }
}

class MoreHarnessKeysTest {
    @kotlin.test.Test
    fun moreKeysAreTheNonPrimaryUnsetOnes() {
        val rows = listOf(
            com.superiorsoftllc.infantry.ui.protocol.SecretRow("harness.pi.key", "Pi API key"),
            com.superiorsoftllc.infantry.ui.protocol.SecretRow("harness.opencode.commandcode.key", "Command Code key"),
            com.superiorsoftllc.infantry.ui.protocol.SecretRow("harness.claude.oauth.key", "Claude token", set = true),
        )
        kotlin.test.assertEquals(listOf("harness.opencode.commandcode.key"), com.superiorsoftllc.infantry.ui.shell.blocks.moreHarnessKeys(rows).map { it.name })
    }
}
