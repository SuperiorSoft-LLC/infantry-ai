// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PROV-3: a malformed provider file is REFUSED by name, never silently skipped.
 *
 * PROV-1 proved a dropped file becomes a provider; PROV-2 proved its argv is a template. PROV-3 is
 * the failure contract: a file that is not valid JSON, or that is missing a required field, must
 * surface as an error naming the FILE, not vanish. A directory can hold several files, so a bad one
 * must not take a good one down with it -- every valid file still loads, and the load then fails
 * loudly naming the offender(s). No silent fallback.
 */
class ConfigProviderMalformedTest {

    @Test
    fun malformedProviderFileIsRefusedByName() {
        val dir: File = Files.createTempDirectory("infantry-prov3-").toFile()
        File(dir, "good.json").writeText(
            """{"id":"prov3-good","command":"good.exe"}""",
        )
        File(dir, "broken.json").writeText(
            """{"id":"prov3-broken","command":"broken.exe",""",
        )
        File(dir, "incomplete.json").writeText(
            """{"id":"prov3-incomplete"}""",
        )

        val refusal = assertFailsWith<IllegalArgumentException> { ConfigProviderLoader(dir).load() }
        val message = refusal.message.orEmpty()

        assertTrue(
            message.contains("broken.json"),
            "refusal did not name the invalid-JSON file: $message",
        )
        assertTrue(
            message.contains("incomplete.json"),
            "refusal did not name the missing-field file: $message",
        )

        // A valid file in the same directory still loads: no good provider is collateral damage.
        assertEquals("prov3-good", ProviderFactory.fromFlag("prov3-good").id)
    }
}
