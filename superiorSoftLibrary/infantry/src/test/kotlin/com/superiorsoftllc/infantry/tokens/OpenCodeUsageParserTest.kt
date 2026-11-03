// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenCodeUsageParserTest {

    private fun fixtureLines(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/tokens/opencode-step-finish.jsonl")) { "fixture missing" }
            .bufferedReader().readLines()

    @Test
    fun `real fixture sums input and output over every step_finish`() {
        // step 1: input 30903 / output 61; step 2: input 31445 / output 5 (reasoning excluded)
        assertEquals(TokenCounts(62_348L, 66L), OpenCodeUsageParser.parse(fixtureLines().asSequence()))
    }

    @Test
    fun `no step_finish line yields null not zero`() {
        val lines = fixtureLines().filterNot { it.contains("\"step_finish\"") }
        assertNull(OpenCodeUsageParser.parse(lines.asSequence()))
        assertNull(OpenCodeUsageParser.parse(emptySequence()))
    }

    @Test
    fun `non-JSON lines are skipped`() {
        val lines = listOf("starting opencode...", "") + fixtureLines() + listOf("{not json", "done")
        assertEquals(TokenCounts(62_348L, 66L), OpenCodeUsageParser.parse(lines.asSequence()))
    }
}
