// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import kotlin.test.Test

/**
 * CHAT-SCROLL-1: the before/after numbers for the report, over the seeded 3000+ item chat ([LongChatFixture]). Prints, asserts
 * nothing: initial load time, semantics nodes composed, the mean frame time of one appended item, and whether a scrolled-up
 * view stays where it was when new items arrive.
 */
@OptIn(ExperimentalTestApi::class)
class LongChatMeasureUiTest {
    @Test
    fun measureLongChat() {
        val turns = 800 // 4000 entries, 3200 chat rows
        val agent = mutableStateOf(LongChatFixture.agent(turns))
        runDesktopComposeUiTest(900, 700) {
            val t0 = System.nanoTime()
            setContent { AgentChat(agent.value) }
            waitForIdle()
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val nodes = onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().size

            // Append 50 items while at the bottom: mean wall time per frame.
            var seq = 100_000L
            val t1 = System.nanoTime()
            repeat(50) { i ->
                agent.value = agent.value.copy(entries = agent.value.entries + TranscriptEntry(seq++, LineKind.LOG, "live tool line $i", 1_800_000_000_000L + i))
                waitForIdle()
            }
            val appendMs = (System.nanoTime() - t1) / 1_000_000.0 / 50

            // Scroll well up, then let new items arrive: does the row we were reading stay on screen?
            onNode(hasScrollToIndexAction()).performScrollToIndex(20)
            waitForIdle()
            val reading = onAllNodes(hasText("owner message", substring = true)).fetchSemanticsNodes()
                .firstNotNullOfOrNull { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.firstOrNull()?.text }
            repeat(5) { i ->
                agent.value = agent.value.copy(entries = agent.value.entries + TranscriptEntry(seq++, LineKind.TEXT, "late answer $i", 1_900_000_000_000L + i, closed = true))
                waitForIdle()
            }
            val stayed = reading != null && onAllNodes(hasText(reading)).fetchSemanticsNodes().isNotEmpty()
            println("CHAT-SCROLL-1 MEASURE entries=${agent.value.entries.size} loadMs=$loadMs nodesComposed=$nodes appendFrameMs=${"%.1f".format(appendMs)} scrolledUpRow='$reading' stayedOnScreen=$stayed")
        }
    }
}
