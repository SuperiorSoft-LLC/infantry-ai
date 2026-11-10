// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RUNNER-ADD-LINK-1: `infantry runner add '<link>' [--name <n>]` and `add --link <link|->` parse; the older form still does. */
class RunnerCliUsageTest {
    private val link = "infantry://runner?v=1&name=t1&host=127.0.0.1&port=8790&code=ABCD-EFGH-JKLM-NPQR&fp=AB:CD:EF:01"

    private fun args(vararg words: String, name: String? = null, code: String? = null, fingerprint: String? = null, link: String? = null) =
        RunnerCli.Args(listOf("add") + words, name = name, code = code, fingerprint = fingerprint, link = link)

    @Test
    fun `the one-paste link forms are valid`() {
        assertNull(RunnerCli.usageError(args(link)))
        assertNull(RunnerCli.usageError(args(link, name = "other")))
        assertNull(RunnerCli.usageError(args(link = "-")))
        assertNull(RunnerCli.usageError(args(link = link, name = "other")))
    }

    @Test
    fun `the older name url code fingerprint form is still valid`() {
        assertNull(RunnerCli.usageError(args("t1", "wss://h:8790", code = "ABCD-EFGH-JKLM-NPQR", fingerprint = "AB:CD")))
    }

    @Test
    fun `a link form does not take the old flags`() {
        assertNotNull(RunnerCli.usageError(args(link, code = "ABCD-EFGH-JKLM-NPQR")))
        assertNotNull(RunnerCli.usageError(args(link = link, code = "ABCD-EFGH-JKLM-NPQR")))
        assertNotNull(RunnerCli.usageError(args(link = link, fingerprint = "AB:CD")))
        assertNotNull(RunnerCli.usageError(args("t1", "wss://h:8790")))
        assertNotNull(RunnerCli.usageError(args("not-a-link")))
    }

    /** RUNNER-PAIR-1: `runner bearer [...]` on the runner and `runner add <endpoint> --bearer <token|->` on the orchestrator. */
    @Test
    fun `the bearer forms are valid and take nothing else`() {
        val bearer = "irb1.t1.plain.0123456789abcdefghjkmnpqrstvwxyz"
        assertNull(RunnerCli.usageError(RunnerCli.Args(listOf("add", "wss://203.0.113.5:8790"), bearer = bearer)))
        assertNull(RunnerCli.usageError(RunnerCli.Args(listOf("add", "localhost:18921"), bearer = "-", name = "other")))
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("add", "wss://h:8790"), bearer = bearer, code = "ABCD-EFGH-JKLM-NPQR")))
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("add", "wss://h:8790", "extra"), bearer = bearer)))
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("add", link), bearer = bearer)))
        assertNull(RunnerCli.usageError(RunnerCli.Args(listOf("bearer"))))
        assertNull(RunnerCli.usageError(RunnerCli.Args(listOf("bearer"), name = "box", rotate = true)))
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("bearer", "extra"))))
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("bearer"), code = "ABCD-EFGH-JKLM-NPQR")))
    }

    /** RUNNER-PAIR-1: the parser takes `--bearer -` (read stdin); it once refused it like a missing value, found on the live nightly. */
    @Test
    fun `the parser takes --bearer - and still refuses a missing value`() {
        val dash = StringBuilder()
        Cli.run(arrayOf("--port", "1", "runner", "add", "wss://h:1", "--bearer", "-"), StringBuilder(), dash, CliTestEnv.getenv)
        assertFalse(dash.contains("--bearer expects"), dash.toString())
        val missing = StringBuilder()
        Cli.run(arrayOf("--port", "1", "runner", "add", "wss://h:1", "--bearer", "--name", "x"), StringBuilder(), missing, CliTestEnv.getenv)
        assertTrue(missing.contains("--bearer expects"), missing.toString())
    }
}
