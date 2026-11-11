// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    // RUNNER-LOGIN-1 (ticket 27c0a4ec): `claude auth login` writes the login but not hasCompletedOnboarding.
    private fun login(dir: File) { dir.mkdirs(); File(dir, ".credentials.json").writeText("""{"claudeAiOauth": {}}""") }

    @Test fun `a login without a finished setup is signed in`() {
        File(home, ".claude.json").writeText("""{"firstStartTime": "2026-11-11T14:50:00Z", "userID": "d9"}""")
        login(File(home, ".claude"))
        assertNull(ClaudeSignIn.problem(env(), home))
        File(home, ".claude.json").delete()
        assertNull(ClaudeSignIn.problem(env(), home), "a login with no config file yet")
        login(custom)
        assertNull(ClaudeSignIn.problem(env(custom), home), "the login in CLAUDE_CONFIG_DIR")
        assertNull(ClaudeSignIn.problem(mapOf(ClaudeSignIn.OAUTH_TOKEN_ENV to "t"), File(root, "empty")), "a subscription token in the env")
        assertEquals(ClaudeSignIn.NOT_SIGNED_IN, ClaudeSignIn.problem(mapOf(ClaudeSignIn.OAUTH_TOKEN_ENV to " "), File(root, "empty")))
    }

    @Test fun `prepare marks the setup done for a login and keeps the rest of the config`() {
        File(home, ".claude.json").writeText("""{"userID": "d9", "projects": {"/work": {}}}""")
        login(File(home, ".claude"))
        assertTrue(ClaudeSignIn.prepare(env(), home))
        val text = File(home, ".claude.json").readText()
        assertTrue("\"hasCompletedOnboarding\":true" in text.replace(" ", ""), text)
        assertTrue("\"userID\":\"d9\"" in text.replace(" ", "") && "/work" in text, text)
        assertFalse(ClaudeSignIn.prepare(env(), home), "a finished setup is left alone")
    }

    @Test fun `prepare makes the config file when the login has none`() {
        login(custom)
        assertTrue(ClaudeSignIn.prepare(env(custom), home))
        assertNull(ClaudeSignIn.problem(env(custom), home))
        assertTrue("hasCompletedOnboarding" in File(custom, ".claude.json").readText())
    }

    @Test fun `prepare touches nothing without a login or on doubt`() {
        File(home, ".claude.json").writeText("""{"userID": "d9"}""")
        assertFalse(ClaudeSignIn.prepare(env(), home))
        assertEquals("""{"userID": "d9"}""", File(home, ".claude.json").readText())
        login(File(home, ".claude"))
        File(home, ".claude.json").writeText("{ not json")
        assertFalse(ClaudeSignIn.prepare(env(), home))
        assertEquals("{ not json", File(home, ".claude.json").readText())
    }
}
