// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.ChatItem
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import com.superiorsoftllc.infantry.ui.model.ReasoningPlacement
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.model.chatItems
import com.superiorsoftllc.infantry.ui.model.chatRows
import com.superiorsoftllc.infantry.ui.model.reasoningCopyText
import com.superiorsoftllc.infantry.ui.model.reasoningLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningPanelTest {
    private val output = listOf(
        OutputLine(LineKind.REASONING, "first thought", 1),
        OutputLine(LineKind.REASONING, "second thought", 2),
        OutputLine(LineKind.TEXT, "answer", 3),
        OutputLine(LineKind.REASONING, "third thought", 4),
    )

    @Test fun defaultsToInlineAsToday() {
        assertEquals(ReasoningPlacement.INLINE, UiFlags().reasoningPlacement)
        assertEquals(ReasoningPlacement.INLINE, ReasoningPlacement.fromId(null))
        assertEquals(ReasoningPlacement.INLINE, ReasoningPlacement.fromId("nonsense"))
        assertEquals(ReasoningPlacement.PANEL_ONLY, ReasoningPlacement.fromId("PANEL_ONLY"))
    }

    @Test fun inlineKeepsReasoningBlocksInTheChat() {
        val items = chatItems(output)
        assertEquals(items, ReasoningPlacement.INLINE.chatRows(items))
    }

    @Test fun panelOnlyDropsReasoningFromChatButNotFromThePanel() {
        val rows = ReasoningPlacement.PANEL_ONLY.chatRows(chatItems(output))
        assertTrue(rows.none { it is ChatItem.Reasoning })
        assertEquals(1, rows.size)
        // Nothing lost: every reasoning line is still listed for the panel, oldest first.
        assertEquals(listOf("first thought", "second thought", "third thought"), reasoningLines(output).map { it.text })
    }

    @Test fun copyJoinsAllLines() {
        assertEquals("first thought\nsecond thought\nthird thought", reasoningCopyText(reasoningLines(output)))
    }

    @Test fun noReasoningMeansEmptyPanelList() {
        assertTrue(reasoningLines(listOf(OutputLine(LineKind.TEXT, "x", 1))).isEmpty())
    }
}
