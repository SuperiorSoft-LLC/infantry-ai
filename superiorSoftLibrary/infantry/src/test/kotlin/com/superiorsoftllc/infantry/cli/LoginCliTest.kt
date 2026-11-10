// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-LOGIN-1: the prompt flow against a fake daemon (the probe), the saved connection and its use. */
class LoginCliTest {
    private fun env(dir: File): (String) -> String? = { if (it == "INFANTRY_STATE_DIR") dir.path else null }

    @Test
    fun promptsForUrlAndTokenChecksAndSavesWithoutPrintingTheToken() {
        val dir = kotlin.io.path.createTempDirectory("login").toFile()
        val out = StringBuilder(); val err = StringBuilder()
        var probed: Target? = null
        val code = LoginCli.run(
            "login", emptyList(), out, err, env(dir), input = { StringReader("100.64.0.5:8797\n") },
            probe = { probed = it; ReadyInfo("1.2.3", "abc") }, readSecret = { "s3cret-token" },
        )
        assertEquals(0, code, err.toString())
        assertEquals("ws://100.64.0.5:8797/ws", probed?.url)
        assertEquals("s3cret-token", probed?.token)
        assertTrue(out.contains("now talking to ws://100.64.0.5:8797/ws"))
        assertFalse(out.contains("s3cret-token") || err.contains("s3cret-token"))
        assertFalse(File(dir, "login.json").readText().contains("s3cret-token"))
        val saved = assertNotNull(LoginStore.load(env(dir)))
        assertEquals("s3cret-token", LoginStore.token(saved))
        val who = StringBuilder()
        assertEquals(0, LoginCli.run("whoami", emptyList(), who, err, env(dir), { StringReader("") }))
        assertTrue(who.contains("ws://100.64.0.5:8797/ws") && !who.contains("s3cret-token"))
    }

    @Test
    fun flagsSkipPromptsAndAFailedCheckSavesNothing() {
        val dir = kotlin.io.path.createTempDirectory("login").toFile()
        val out = StringBuilder(); val err = StringBuilder()
        val bad = LoginCli.run("login", listOf("--url", "ws://h:1/ws", "--token", "t"), out, err, env(dir), { StringReader("") }, probe = { null })
        assertEquals(1, bad)
        assertNull(LoginStore.load(env(dir)))
        val ok = LoginCli.run("login", listOf("--url", "ws://h:1/ws", "--token", "t"), out, err, env(dir), { StringReader("") }, probe = { ReadyInfo("1", null) })
        assertEquals(0, ok)
        assertEquals("ws://h:1/ws", LoginStore.load(env(dir))?.url)
    }

    @Test
    fun logoutClearsAndTargetUsesSavedLogin() {
        val dir = kotlin.io.path.createTempDirectory("login").toFile()
        val e = env(dir)
        LoginStore.save(e, "ws://saved:9/ws", "tok")
        val out = StringBuilder()
        assertEquals(0, LoginCli.run("logout", emptyList(), out, out, e, { StringReader("") }))
        assertNull(LoginStore.load(e))
        assertEquals(1, LoginCli.run("whoami", emptyList(), out, out, e, { StringReader("") }))
    }
}
