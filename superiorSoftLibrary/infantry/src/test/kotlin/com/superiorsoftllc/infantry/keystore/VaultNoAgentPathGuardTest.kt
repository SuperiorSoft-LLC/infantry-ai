// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAULT-REVEAL-1 guard: no agent path returns a stored value in clear. The reveal / backup frames are owner-only (an agent
 * connection holds no owner credential), and no CLI command, MCP tool, script API, plugin or SDK surface names them or the
 * vault. Agents keep `{{secret:...}}` rendering only. If this fails, the new path is the bug: do not widen the scan's exclusions.
 */
class VaultNoAgentPathGuardTest {
    private val repo = TestDaemonPlugins.repo
    private val needles = listOf("secret_reveal", "secret_backup_export", "secret_backup_import", "SECRET_REVEAL", "SECRET_BACKUP_", "SecretVault", "SecretRevealRequest")

    @Test
    fun `the vault frames need the owner credential`() {
        assertEquals(setOf(Envelope.SECRET_REVEAL, Envelope.SECRET_BACKUP_EXPORT, Envelope.SECRET_BACKUP_IMPORT), SecretVault.FRAMES)
        for (type in SecretVault.FRAMES) {
            assertTrue(type in FrameGate.OWNER_ONLY, "$type must be owner-only")
            assertNotNull(FrameGate.refusal(type, owner = false), "$type must be refused to an agent connection")
            assertNull(FrameGate.refusal(type, owner = true))
        }
    }

    @Test
    fun `no CLI, MCP, script, plugin or SDK source names the vault`() {
        val roots = listOf(
            "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli",
            "superiorSoftLibrary/infantryDaemonApi/src",
            "superiorSoftLibrary/infantryPlugins/src",
            "superiorSoftLibrary/infantryHarnessApi/src",
            "plugins",
        ).map { File(repo, it) }
        roots.forEach { assertTrue(it.isDirectory, "missing ${it.path}") }
        val hits = roots.flatMap { root ->
            root.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
                .filter { it.isFile && it.extension in setOf("kt", "kts", "lua", "json", "java") }
                .filter { f -> f.readText().let { text -> needles.any { it in text } } }
                .map { it.relativeTo(repo).invariantSeparatorsPath }
                .toList()
        }
        assertEquals(emptyList(), hits, "only the owner's window (infantryUiCore) and the daemon's keystore wiring may name the vault frames")
    }
}
