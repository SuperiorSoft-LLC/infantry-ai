// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PROV-4: a JSON provider's `sessionIdRegex` extracts a session id from a streamed stdout line.
 *
 * Built on PROV-1 (data-driven provider) + PROV-2 (argv template): a dropped JSON file can now also
 * declare how its CLI prints a session id, so the daemon can resume that session later. The regex is
 * validated at LOAD time -- a bad regex fails loudly naming the file, never at first stdout line.
 * When the field is absent the provider keeps the default (no session surface), exactly like a
 * built-in that has none.
 */
class ConfigProviderSessionRegexTest {

    @Test
    fun jsonProviderExtractsSessionIdByRegex() {
        val dir: File = Files.createTempDirectory("infantry-prov4-").toFile()
        File(dir, "regex.json").writeText(
            """{"id":"prov4-regex","command":"fake.exe","sessionIdRegex":"session: (\\w+)"}""",
        )
        File(dir, "noregex.json").writeText(
            """{"id":"prov4-noregex","command":"fake.exe"}""",
        )

        ConfigProviderLoader(dir).load()

        val withRegex = ProviderFactory.fromFlag("prov4-regex")
        assertEquals("abc123", withRegex.sessionIdFromLine("session: abc123"))
        assertNull(withRegex.sessionIdFromLine("no match here"))

        val withoutRegex = ProviderFactory.fromFlag("prov4-noregex")
        assertNull(withoutRegex.sessionIdFromLine("session: abc123"))

        // A bad regex config fails at load, naming the offending file.
        val badDir: File = Files.createTempDirectory("infantry-prov4-bad-").toFile()
        File(badDir, "badregex.json").writeText(
            """{"id":"prov4-badregex","command":"fake.exe","sessionIdRegex":"["}""",
        )
        val refusal = assertFailsWith<IllegalArgumentException> { ConfigProviderLoader(badDir).load() }
        assertTrue(
            refusal.message.orEmpty().contains("badregex.json"),
            "refusal did not name the bad-regex file: ${refusal.message}",
        )
    }
}
