// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState

/**
 * CHAT-SCROLL-1: a seeded long transcript, generated in code: [turns] rounds of an owner message, a run of thinking, a tool
 * log line, the agent's answer and a status line, so 5 entries and 5 chat rows per round.
 * Text is numbered ("owner message 17", "answer 17 ...") so a test can find a row; every 50th answer names a ticket id.
 */
object LongChatFixture {
    fun entries(turns: Int, firstSeq: Long = 1): List<TranscriptEntry> {
        val out = ArrayList<TranscriptEntry>(turns * 5)
        var seq = firstSeq
        for (t in 0 until turns) {
            val at = 1_700_000_000_000L + t * 60_000L
            out += TranscriptEntry(seq++, LineKind.USER, "owner message $t: please look at item $t", at)
            out += TranscriptEntry(seq++, LineKind.REASONING, "thinking about item $t, weighing the options", at + 1)
            out += TranscriptEntry(seq++, LineKind.LOG, "tool: bash ./gradlew test --tests Item$t", at + 2)
            val ticket = if (t % 50 == 0) " (see ticket-${(1000 + t).toString().padStart(8, '0')})" else ""
            out += TranscriptEntry(seq++, LineKind.TEXT, "answer $t: done, item $t is fixed and **verified**$ticket", at + 3, closed = true)
            out += TranscriptEntry(seq++, LineKind.STATUS, "turn $t finished", at + 4)
        }
        return out
    }

    fun agent(turns: Int, state: SessionState = SessionState.IDLE): AgentView = AgentView(
        session = SessionRecord(id = "long-chat", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        role = "pm",
        entries = entries(turns),
    )
}
