// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** G13: interactive claude's transcript gives the meter a compaction and an estimated run cost. */
class TranscriptCostCompactionTest {
    private fun asst(id: String, inp: Int, out: Int) =
        """{"type":"assistant","uuid":"u$id","message":{"id":"$id","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":$inp,"output_tokens":$out,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}"""

    @Test
    fun compactionAndCostReachTheMeter() {
        val m = TranscriptMapper("sid")
        val lines = listOf(
            asst("a", 1_000_000, 0),
            """{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto","preTokens":90000}}""",
            asst("b", 0, 1_000_000),
        ).flatMap { m.map(it) }.filterIsInstance<TranscriptSignal.Line>().map { Json.parseToJsonElement(it.json).jsonObject }
        val compactions = lines.filter { it["subtype"]?.jsonPrimitive?.content == "compact_boundary" }
        assertEquals(1, compactions.size)
        assertTrue(compactions[0]["compact_metadata"] is JsonObject)
        val result = Json.parseToJsonElement(m.resultLine()).jsonObject
        assertEquals(18.0, result["total_cost_usd"]!!.jsonPrimitive.content.toDouble(), 1e-9) // 1M in @3 + 1M out @15
    }
}
