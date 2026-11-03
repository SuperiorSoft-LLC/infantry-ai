// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.model.chatStatusOf
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoStuckThinkingAndNotGitTest {
    private val entries = listOf(
        TranscriptEntry(1, LineKind.USER, "hi", at = 1_000),
        TranscriptEntry(2, LineKind.TEXT, "hello", at = 2_000),
    )

    @Test fun parkedSessionWithNoTurnInFlightShowsNoThinking() {
        assertNull(chatStatusOf(entries, 5_000, state = SessionState.WAITING_INPUT, turnActive = false))
    }

    @Test fun activeTurnStillThinks() {
        assertNotNull(chatStatusOf(entries, 5_000, state = SessionState.BUSY, turnActive = true))
        assertNotNull(chatStatusOf(entries, 5_000, state = SessionState.BUSY, turnActive = null))
    }
}
