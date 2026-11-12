// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HARNESS-ALL-1: the runner Check says pi cannot answer only on proof (no key, no login, no extra provider). */
class PiSignInTest {
    private val home: File = Files.createTempDirectory("pi-signin").toFile()
    private val agent = File(home, ".pi/agent").apply { mkdirs() }

    @AfterTest fun cleanup() { home.deleteRecursively() }

    @Test fun `a clean machine with no key and no login is a problem`() {
        assertEquals(PiSignIn.NO_PROVIDER, PiSignIn.problem(emptyMap(), home))
        File(agent, "auth.json").writeText("{}")
        assertEquals(PiSignIn.NO_PROVIDER, PiSignIn.problem(mapOf("PATH" to "/usr/bin", "EMPTY_API_KEY" to " "), home))
    }

    @Test fun `a provider key in the env, an auth entry, models json or an extension is fine`() {
        assertNull(PiSignIn.problem(mapOf("DEEPSEEK_API_KEY" to "x"), home))
        assertNull(PiSignIn.problem(mapOf("ANTHROPIC_OAUTH_TOKEN" to "x"), home))
        File(agent, "auth.json").writeText("""{"anthropic":{"type":"oauth"}}""")
        assertNull(PiSignIn.problem(emptyMap(), home))
        File(agent, "auth.json").delete()
        File(agent, "models.json").writeText("{}")
        assertNull(PiSignIn.problem(emptyMap(), home))
        File(agent, "models.json").delete()
        File(agent, "extensions").mkdirs(); File(agent, "extensions/bridge.ts").writeText("")
        assertNull(PiSignIn.problem(emptyMap(), home))
    }

    @Test fun `an unreadable auth json is doubt, so no problem`() {
        File(agent, "auth.json").writeText("not json")
        assertNull(PiSignIn.problem(emptyMap(), home))
    }

    @Test fun `PI_CODING_AGENT_DIR moves the agent dir`() {
        val other = File(home, "data/pi").apply { mkdirs() }
        File(other, "auth.json").writeText("""{"google":{"type":"api_key"}}""")
        assertNull(PiSignIn.problem(mapOf(PiSignIn.AGENT_DIR_ENV to other.path), home))
        assertEquals(PiSignIn.NO_PROVIDER, PiSignIn.problem(mapOf(PiSignIn.AGENT_DIR_ENV to File(home, "none").path), home))
    }
}
