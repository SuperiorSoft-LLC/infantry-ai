package com.superiorsoftllc.infantry.cli

import kotlin.test.Test
import kotlin.test.assertFalse

/** SCRIPT-HOOK-1: every command the dispatcher answers must pass the COMMANDS guard ('script' was rejected as unknown). */
class CliScriptCommandKnownTest {
    @Test
    fun dispatchedCommandsAreNotRejectedAsUnknown() {
        for (cmd in listOf("script", "tool", "event", "tests")) {
            val out = StringBuilder()
            val err = StringBuilder()
            Cli.run(arrayOf(cmd, "--port", "1"), out, err, CliTestEnv.getenv)
            assertFalse(err.contains("unknown command '$cmd'"), "$cmd rejected: $err")
        }
    }
}
