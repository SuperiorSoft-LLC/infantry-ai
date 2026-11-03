package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChangeBox
import com.superiorsoftllc.infantry.ui.model.ChangeKind
import com.superiorsoftllc.infantry.ui.model.DiffLineKind
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.changeBoxView
import com.superiorsoftllc.infantry.ui.model.fileWriteOf
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.FileEdit
import com.superiorsoftllc.infantry.ui.protocol.FileWrite
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UX-WRITE-1: what the chat's change box draws for a file-writing tool call (contract 1.80.0 `status.write`). */
class ChangeBoxModelTest {
    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun aFortyLineWriteIsCollapsedToTwelveRowsWithTwentyEightMore() {
        val view = changeBoxView(FileWrite("src/Big.kt", content = lines(40)))
        assertEquals(ChangeKind.CREATED, view.kind)
        assertEquals(40, view.rows.size)
        assertEquals(40, view.added)
        assertTrue(ChangeBox.collapsible(view))
        val preview = ChangeBox.visibleRows(view, expanded = false)
        assertEquals(12, preview.size)
        assertEquals((1..12).map { "line $it" }, preview.map { it.text })
        assertEquals((1..12).toList(), preview.map { it.newNo })
        assertEquals(28, ChangeBox.hiddenCount(view))
        assertEquals("+28 more lines", ChangeBox.moreLabel(view))
        assertEquals("View all (40 lines)", ChangeBox.viewAllLabel(view))
        assertEquals(40, ChangeBox.visibleRows(view, expanded = true).size)
    }

    @Test
    fun aShortWriteShowsInFullAndIsNotCollapsible() {
        val view = changeBoxView(FileWrite("notes.md", content = lines(5) + "\n"))
        assertEquals(5, view.rows.size, "a final newline opens no empty row")
        assertFalse(ChangeBox.collapsible(view))
        assertEquals(5, ChangeBox.visibleRows(view, expanded = false).size)
        assertEquals(0, ChangeBox.hiddenCount(view))
    }

    @Test
    fun anEditIsTheChangedSnippetDiffedNotTheWholeFile() {
        val view = changeBoxView(FileWrite("a.kt", edits = listOf(FileEdit("keep\nold\nkeep2", "keep\nnew\nkeep2"))))
        assertEquals(ChangeKind.EDITED, view.kind)
        assertEquals(
            listOf(DiffLineKind.CONTEXT to " keep", DiffLineKind.DEL to "-old", DiffLineKind.ADD to "+new", DiffLineKind.CONTEXT to " keep2"),
            view.rows.map { it.kind to it.text },
        )
        assertEquals(1, view.added)
        assertEquals(1, view.removed)
    }

    @Test
    fun aMultiEditHeadsEachEditAndAPatchIsStyledByItsPrefixes() {
        val multi = changeBoxView(FileWrite("a.kt", edits = listOf(FileEdit("a", "b"), FileEdit("c", "d"))))
        assertEquals(listOf("@@ edit 1 of 2 @@", "-a", "+b", "@@ edit 2 of 2 @@", "-c", "+d"), multi.rows.map { it.text })

        val patch = changeBoxView(FileWrite("src/c.ts", patch = "*** Begin Patch\n*** Update File: src/c.ts\n@@\n-old\n+new\n ctx\n*** End Patch"))
        assertEquals(
            listOf(DiffLineKind.META, DiffLineKind.META, DiffLineKind.HUNK, DiffLineKind.DEL, DiffLineKind.ADD, DiffLineKind.CONTEXT, DiffLineKind.META),
            patch.rows.map { it.kind },
        )
        assertEquals(1, patch.added)
        assertEquals(1, patch.removed)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")

    private fun agentWith(vararg frames: Envelope) = frames
        .fold(reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.80.0 instance=A since=0"), 0)) { s, f -> reduce(s, f, 0) }
        .agents.getValue("a")

    @Test
    fun aWriteStatusBecomesOneWriteLineAndOtherToolsAddNone() {
        val session = Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), rec), rec.id)
        val write = Envelope(Envelope.STATUS, """{"state":"BUSY","tool":"Edit","activity":"a.kt","write":{"path":"a.kt","edits":[{"oldText":"x","newText":"y"}]}}""", rec.id)
        val read = Envelope(Envelope.STATUS, """{"state":"BUSY","tool":"Read","activity":"a.kt"}""", rec.id)
        val agent = agentWith(session, read, write)
        val writes = agent.output.filter { it.kind == LineKind.WRITE }
        assertEquals(1, writes.size)
        assertEquals(FileWrite("a.kt", edits = listOf(FileEdit("x", "y"))), fileWriteOf(writes.single().text))
        assertEquals(listOf(LineKind.WRITE), agent.output.map { it.kind }, "a non-write tool adds no transcript line")
        assertEquals("Edit: a.kt", agent.activity?.label, "the activity row still reads the tool")
        assertEquals("✎ a.kt", agent.lastActivity, "the sidebar names the file, never the JSON")
    }
}
