// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUNNER-PAIR-1: on `runner pair|up|connect`, `--port` (like `--host`) is the runner's link port, not the daemon's. Before, the
 * global parser took every `--port` as the daemon's, so `runner pair --port 18921` asked a daemon on 18921 and never paired.
 * A bad link port is now the runner command's own usage error; nothing is dialled.
 */
class RunnerLinkFlagsCliTest {
    private fun run(vararg args: String): Pair<Int, String> {
        val out = StringBuilder(); val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args), out, err, CliTestEnv.getenv)
        return exit to (out.toString() + err.toString())
    }

    @Test
    fun `--port on runner pair, up and connect is the link port, never the daemon address`() {
        for (words in listOf(listOf("runner", "pair", "--name", "box"), listOf("runner", "up", "--name", "box"), listOf("runner", "connect", "--name", "box", "--via", "x"))) {
            val (exit, text) = run(*words.toTypedArray(), "--port", "99999")
            assertEquals(2, exit, "$words: $text")
            assertFalse("daemon not running" in text, "$words must not dial a daemon on the link port: $text")
            assertTrue("port" in text, text)
        }
    }

    @Test
    fun `connect needs a name and the command that reaches the runner`() {
        assertEquals(2, run("runner", "connect", "--name", "box").first)
        assertEquals(2, run("runner", "connect", "--via", "docker exec x").first)
    }
}
