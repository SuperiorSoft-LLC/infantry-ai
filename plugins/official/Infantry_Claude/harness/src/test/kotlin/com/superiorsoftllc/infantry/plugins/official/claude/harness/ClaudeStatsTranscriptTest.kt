// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessStatEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** SESSION-STATS-CLAUDE-1: a recorded claude transcript through the plugin's stats contribution yields the request/tool record the panel folds. */
class ClaudeStatsTranscriptTest {
    @Test fun `transcript yields prompts, requests with tokens, tools and results`() {
        val dir = kotlin.io.path.createTempDirectory("claude-stats").toFile()
        File(dir, "p").mkdirs()
        File(dir, "p/s1.jsonl").writeText(
            """
            {"type":"user","timestamp":"2026-11-02T10:00:00Z","message":{"role":"user","content":"hi"}}
            {"type":"assistant","timestamp":"2026-11-02T10:00:02Z","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":100,"cache_creation_input_tokens":20},"content":[{"type":"tool_use","id":"t1","name":"Read"}]}}
            {"type":"user","timestamp":"2026-11-02T10:00:03Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"abcd"}]}}
            {"type":"assistant","timestamp":"2026-11-02T10:00:05Z","message":{"id":"m2","model":"claude-opus-5-5","usage":{"input_tokens":12,"output_tokens":7,"cache_read_input_tokens":130,"cache_creation_input_tokens":0},"content":[{"type":"text","text":"ok"}]}}
            """.trimIndent(),
        )
        val row = ClaudeStats.read(dir, "s1")!!
        val ev = row.events!!
        assertEquals(1, row.userPrompts)
        assertEquals(2, row.assistantTurns)
        assertEquals(1, row.toolUses)
        val reqs = ev.filterIsInstance<HarnessStatEvent.Request>()
        assertEquals(listOf(130L to 5L, 142L to 7L), reqs.map { (it.input + it.cacheRead + it.cacheWrite) to it.output })
        assertEquals(listOf("Read"), ev.filterIsInstance<HarnessStatEvent.Tool>().map { it.name })
        assertEquals(listOf(4), ev.filterIsInstance<HarnessStatEvent.ToolResult>().map { it.chars })
    }
}
