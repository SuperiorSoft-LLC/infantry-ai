// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.MAX_RUN_LINES
import com.superiorsoftllc.infantry.ui.model.RunGap
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** IN-APP BUILD T5: the pure run model. */
class RunModelTest {
    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }
    private fun run(id: String = "r1") =
        Envelope(Envelope.RUN, """{"runId":"$id","commandId":"c1","state":"RUNNING"}""")
    private fun out(seq: Long, line: String = "l$seq", id: String = "r1") =
        Envelope(Envelope.RUN_OUTPUT, """{"runId":"$id","sequence":$seq,"stream":"stdout","line":"$line","truncated":false}""")
    private fun finished(id: String = "r1") =
        Envelope(Envelope.RUN_FINISHED, """{"runId":"$id","exitCode":0,"outcome":"SUCCEEDED"}""")

    @Test fun inOrderLinesAreApplied() {
        val r = feed(run(), out(1), out(2), out(3)).runs.current!!
        assertEquals(listOf("l1", "l2", "l3"), r.lines.map { it.text })
        assertEquals(3, r.lastSequence)
        assertNull(r.gap)
    }

    /** RED if a skipped sequence is applied silently: line 3 must not land and a gap must be reported. */
    @Test fun aSkippedSequenceIsAGapAndIsNotApplied() {
        val r = feed(run(), out(1), out(3)).runs.current!!
        assertEquals(listOf("l1"), r.lines.map { it.text }, "sequence 3 must not be applied after 1")
        assertEquals(RunGap(expected = 2, got = 3), r.gap)
        assertEquals(1, r.lastSequence)
    }

    /** Named mutation: accepting sequence == last turns this red. */
    @Test fun aDuplicateSequenceIsDropped() {
        val r = feed(run(), out(1), out(2), out(2, "dup")).runs.current!!
        assertEquals(listOf("l1", "l2"), r.lines.map { it.text })
        assertNull(r.gap)
    }

    @Test fun linesAreCappedAtTwoThousandOldestFirst() {
        val frames = (1L..(MAX_RUN_LINES + 5L)).map { out(it) }.toTypedArray()
        val r = feed(run(), *frames).runs.current!!
        assertEquals(2000, r.lines.size)
        assertEquals(6L, r.lines.first().sequence)
        assertEquals(5, r.droppedLines)
    }

    @Test fun runsAreKeyedByRunIdAndFinishedIsRecorded() {
        val s = feed(run("a"), out(1, id = "a"), run("b"), out(1, "x", "b"), finished("a")).runs
        assertEquals("b", s.currentRunId)
        assertEquals(listOf("l1"), s.runs["a"]!!.lines.map { it.text })
        assertEquals(listOf("x"), s.runs["b"]!!.lines.map { it.text })
        assertEquals("FINISHED", s.runs["a"]!!.state)
        assertNotNull(s.runs["a"]!!.finished)
    }

    @Test fun commandsAreStoredAndRequestsEncode() {
        val s = feed(Envelope(Envelope.COMMANDS, """[{"id":"c1","name":"Echo","cwd":"."}]""")).runs
        assertEquals(listOf("c1"), s.commands.map { it.id })
        assertEquals("""{"commandId":"c1"}""", WireRequests.runStart("c1").data)
        assertEquals("r1", WireRequests.runStop("r1").data)
        assertEquals("r1 force", WireRequests.runStop("r1", true).data)
        assertEquals(Envelope.COMMAND_LIST, WireRequests.commandList().type)
    }

    @Test fun aSecondGapKeepsTheFirstWarning() {
        val r = feed(run(), out(1), out(3), out(6)).runs.current!!
        assertEquals(RunGap(expected = 2, got = 3), r.gap)
        assertEquals(listOf("l1"), r.lines.map { it.text })
    }

    // --- RUN-CMD-2: the command editor's model and wire ------------------------------------------------

    @Test fun commandsCarryArgvAndGraceAndBumpTheRevision() {
        val s = feed(Envelope(Envelope.COMMANDS, """[{"id":"b","name":"B","cwd":"web","argv":["a","b"],"graceMillis":7000}]"""))
        assertEquals(listOf("a", "b"), s.runs.commands.single().argv)
        assertEquals(7000L, s.runs.commands.single().graceMillis)
        assertEquals(1, s.runs.commandsRevision)
    }

    @Test fun aCommandRefusalIsKeptForTheEditorAndTheNextListClearsIt() {
        val refused = feed(Envelope(Envelope.ERROR, "command_save refused: command ('x') has an empty argv"))
        assertEquals("command_save refused: command ('x') has an empty argv", refused.runs.commandError)
        assertEquals(1, refused.runs.commandsRevision, "a refusal answers the save too")
        assertEquals(emptyList(), refused.notices, "the Run tab shows it; no second global banner")
        val cleared = reduce(refused, Envelope(Envelope.COMMANDS, "[]"), 101)
        assertNull(cleared.runs.commandError)
        assertNull(feed(Envelope(Envelope.ERROR, "ticket_save refused: nope")).runs.commandError, "other refusals are not the editor's")
        assertEquals("command_delete refused: no such command 'x'", feed(Envelope(Envelope.ERROR, "command_delete refused: no such command 'x'")).runs.commandError)
    }

    @Test fun commandSaveSendsArgvAsAJsonList() {
        val e = WireRequests.commandSave("build-web", "Build web", listOf("gradlew.bat", "a b"), null, 5000)
        assertEquals(Envelope.COMMAND_SAVE, e.type)
        assertEquals("""{"id":"build-web","name":"Build web","argv":["gradlew.bat","a b"],"graceMillis":5000}""", e.data)
        assertEquals(
            """{"id":"i","name":"n","argv":["x"],"cwd":"web","graceMillis":1}""",
            WireRequests.commandSave("i", "n", listOf("x"), "web", 1).data,
        )
        assertEquals("""{"id":"build-web"}""", WireRequests.commandDelete("build-web").data)
    }
}
