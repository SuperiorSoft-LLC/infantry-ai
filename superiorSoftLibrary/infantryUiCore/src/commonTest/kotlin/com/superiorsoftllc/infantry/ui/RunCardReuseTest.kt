// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.commandPills
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals

/** RUN-TAB-1: one Run card per saved command, and only the shown project's commands. */
class RunCardReuseTest {
    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }
    private fun run(id: String, cmd: String, at: Long) =
        Envelope(Envelope.RUN, """{"runId":"$id","commandId":"$cmd","state":"RUNNING","startedAt":$at,"name":"$cmd"}""")

    @Test fun `starting the same command twice shows one card, the newest run`() {
        val s = feed(run("r1", "site", 1), run("r2", "site", 2))
        assertEquals(listOf("r2"), commandPills(s.runs, 10, null).map { it.runId })
    }

    @Test fun `only the shown project's commands have cards`() {
        val s = feed(run("a", "mine", 1), run("b", "theirs", 2))
        assertEquals(listOf("a"), commandPills(s.runs, 10, setOf("mine")).map { it.runId })
        assertEquals(listOf("b", "a"), commandPills(s.runs, 10, null).map { it.runId })
    }
}
