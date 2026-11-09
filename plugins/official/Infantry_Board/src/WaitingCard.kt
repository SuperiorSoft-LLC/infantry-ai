// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.protocol.WaitingView
import kotlinx.coroutines.delay

/**
 * WAITING-FLAG-1: how a card words its worker's waiting flag. The ticket stays In Progress: "active but waiting" is a state
 * of the card, not a lane (a lane would move the ticket out of In Progress, where the done/blocked report flow and every
 * watcher expect a worked ticket to be; the flag is the worker's own and clears itself when its next turn starts).
 */
object WaitingCard {
    /** `active, waiting 3m: <reason>`, or `wait over 2m ago? <reason>` (expired = true) once the flag stopped counting. */
    fun label(w: WaitingView, now: Long): Pair<String, Boolean> {
        val expired = now > w.expiresAt
        val text = if (expired) "wait over ${minutes(now - w.untilAt)}m ago? ${w.reason}" else "active, waiting ${minutes(now - w.since)}m: ${w.reason}"
        return text to expired
    }

    private fun minutes(ms: Long): Long = ms.coerceAtLeast(0) / 60_000
}

/** The chip a flagged In Progress card wears; re-reads the clock every 30 s so its age and its expiry stay true. */
@Composable
fun WaitingChip(ticketId: String, w: WaitingView) {
    val now by produceState(kotlin.time.Clock.System.now().toEpochMilliseconds(), w) {
        while (true) {
            value = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(30_000)
        }
    }
    val (text, expired) = WaitingCard.label(w, now)
    Chip(text, if (expired) Palette.Danger else Palette.Accent, Modifier.testTag("ticket-waiting-$ticketId"))
}
