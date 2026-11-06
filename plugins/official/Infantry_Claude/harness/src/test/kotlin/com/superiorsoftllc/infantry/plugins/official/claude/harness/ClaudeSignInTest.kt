// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FIRST-RUN-F1 (ticket 771ccf07): the not-signed-in check reads claude's own config file and blocks only on proof. */
class ClaudeSignInTest {
    private val root: File = Files.createTempDirectory("claude-signin-test").toFile()
    private val home = File(root, "home").apply { mkdirs() }
    private val custom = File(root, "custom config").apply { mkdirs() }

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun env(dir: File? = null) = dir?.let { mapOf("CLAUDE_CONFIG_DIR" to it.absolutePath) }.orEmpty()

    @Test fun `a signed-in setup passes`() {
        File(home, ".claude.json").writeText("""{"numStartups": 40, "hasCompletedOnboarding": true, "oauthAccount": {"emailAddress": "x"}}""")
        assertNull(ClaudeSignIn.problem(env(), home))
    }

    @Test fun `a config claude wrote before its setup was finished is not signed in`() {
        // What FIRST-RUN-1 found in an empty CLAUDE_CONFIG_DIR after the CLI sat on its setup screen.
        File(custom, ".claude.json").writeText("""{"firstStartTime": "2026-11-06T08:19:56.480Z", "migrationVersion": 14, "userID": "d9"}""")
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(env(custom), home))
        File(custom, ".claude.json").writeText("""{"hasCompletedOnboarding": false}""")
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(env(custom), home))
    }

    @Test fun `no config at all is not signed in`() {
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(env(), home))
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(env(custom), home))
    }

    @Test fun `CLAUDE_CONFIG_DIR picks the file, and a home config does not count for it`() {
        File(home, ".claude.json").writeText("""{"hasCompletedOnboarding": true}""")
        assertEquals(File(custom, ".claude.json"), ClaudeSignIn.configFile(env(custom), home))
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(env(custom), home))
        File(custom, ".claude.json").writeText("""{"hasCompletedOnboarding": true}""")
        assertNull(ClaudeSignIn.problem(env(custom), home))
    }

    @Test fun `a non-empty legacy config file is the one read`() {
        File(home, ".claude").mkdirs()
        File(home, ".claude/.config.json").writeText("""{"hasCompletedOnboarding": true}""")
        assertEquals(File(home, ".claude/.config.json"), ClaudeSignIn.configFile(env(), home))
        assertNull(ClaudeSignIn.problem(env(), home))
        File(home, ".claude/.config.json").writeText("")
        assertEquals(File(home, ".claude.json"), ClaudeSignIn.configFile(env(), home), "an empty legacy file is skipped")
    }

    @Test fun `doubt never blocks`() {
        File(home, ".claude.json").writeText("{ not json")
        assertNull(ClaudeSignIn.problem(env(), home), "an unreadable config")
        assertNull(ClaudeSignIn.problem(mapOf("CLAUDE_CODE_CUSTOM_OAUTH_URL" to "https://x"), home), "a setup that names another file")
    }
}
