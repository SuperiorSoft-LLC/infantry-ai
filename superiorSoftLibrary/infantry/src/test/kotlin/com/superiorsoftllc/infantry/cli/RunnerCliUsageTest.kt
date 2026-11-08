// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import kotlin.test.Test
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
}
