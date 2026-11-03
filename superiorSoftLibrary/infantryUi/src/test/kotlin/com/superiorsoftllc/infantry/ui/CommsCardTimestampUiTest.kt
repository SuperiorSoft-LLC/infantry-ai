// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The comms card's time sits at the header's right edge (minus its padding), for both card variants and a narrow width. */
@OptIn(ExperimentalTestApi::class)
class CommsCardTimestampUiTest {
    private fun check(style: CommsCardStyle, width: Int) = runComposeUiTest {
        setContent { Box(Modifier.width(width.dp)) { CommsCard(style, at = 1_700_000_000_000L) { Text("body") } } }
        val time = onNodeWithTag(CARD_TIMESTAMP_TAG).getBoundsInRoot()
        val title = onNodeWithTag(CARD_TITLE_TAG).getBoundsInRoot()
        // 8dp header end padding.
        assertEquals((width - 8).toFloat(), time.right.value, 1f, "timestamp right edge at header end")
        assertTrue(title.right <= time.left, "title must not overlap the timestamp")
    }

    @Test fun ownerCardWide() = check(commsCardStyle(com.superiorsoftllc.infantry.ui.model.MessageOrigin.Owner), 400)
    @Test fun agentReplyCardWide() = check(AgentReplyCardStyle, 400)
    @Test fun ownerCardNarrow() = check(AgentReplyCardStyle, 160)
}
