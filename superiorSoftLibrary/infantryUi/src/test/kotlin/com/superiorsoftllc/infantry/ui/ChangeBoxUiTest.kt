package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.protocol.FileEdit
import com.superiorsoftllc.infantry.ui.protocol.FileWrite
import kotlin.test.Test
import kotlin.test.assertEquals

/** UX-WRITE-1: the chat's change box, on its own. */
@OptIn(ExperimentalTestApi::class)
class ChangeBoxUiTest {
    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun collapsedShowsTwelveLinesAFadeAndTheButtonExpandedShowsAllAndCollapseFoldsItBack() {
        runDesktopComposeUiTest(1000, 2400) {
            setContent { ChangeBox(FileWrite("src/Big.kt", content = lines(40))) }
            onNodeWithText("src/Big.kt").assertExists()
            onNodeWithText("created").assertExists()
            onNodeWithTag(CHANGE_BOX_COUNTS_TAG, useUnmergedTree = false).assertTextEquals("+40")
            assertEquals(12, onAllNodesWithTag("diff-line-context").fetchSemanticsNodes().size)
            onNodeWithTag(CHANGE_BOX_FADE_TAG).assertExists()
            onNodeWithTag(CHANGE_BOX_MORE_TAG).assertTextEquals("+28 more lines")
            onNodeWithTag(CHANGE_BOX_VIEW_ALL_TAG).assertTextEquals("View all (40 lines)").performClick()

            assertEquals(40, onAllNodesWithTag("diff-line-context").fetchSemanticsNodes().size)
            onNodeWithText("line 40").assertExists()
            onNodeWithTag(CHANGE_BOX_FADE_TAG).assertDoesNotExist()
            onNodeWithTag(CHANGE_BOX_MORE_TAG).assertDoesNotExist()
            onNodeWithTag(CHANGE_BOX_COLLAPSE_TAG).performClick()

            assertEquals(12, onAllNodesWithTag("diff-line-context").fetchSemanticsNodes().size)
            onNodeWithTag(CHANGE_BOX_VIEW_ALL_TAG).assertExists()
        }
    }

    @Test
    fun aShortWriteShowsInFullWithNoControlAndNoFade() {
        runDesktopComposeUiTest(1000, 1200) {
            setContent { ChangeBox(FileWrite("notes.md", content = lines(5))) }
            assertEquals(5, onAllNodesWithTag("diff-line-context").fetchSemanticsNodes().size)
            onNodeWithTag(CHANGE_BOX_VIEW_ALL_TAG).assertDoesNotExist()
            onNodeWithTag(CHANGE_BOX_COLLAPSE_TAG).assertDoesNotExist()
            onNodeWithTag(CHANGE_BOX_FADE_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun anEditShowsOnlyTheChangedSnippetWithAddAndRemoveStyling() {
        val edit = FileWrite("src/A.kt", edits = listOf(FileEdit("fun a() {\n    return 1\n}", "fun a() {\n    return 2\n}")))
        runDesktopComposeUiTest(1000, 1200) {
            setContent { ChangeBox(edit) }
            onNodeWithText("edited").assertExists()
            onNodeWithTag(CHANGE_BOX_COUNTS_TAG).assertTextEquals("+1", "−1")
            // The changed line is drawn as a removal and an addition, in the Changes panel's diff styling.
            onNodeWithTag(DIFF_LINE_DEL_TAG).assertExists()
            onNodeWithText("-    return 1").assertExists()
            onNodeWithTag(DIFF_LINE_ADD_TAG).assertExists()
            onNodeWithText("+    return 2").assertExists()
            assertEquals(1, onAllNodesWithTag(DIFF_LINE_DEL_TAG).fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithTag(DIFF_LINE_ADD_TAG).fetchSemanticsNodes().size)
            assertEquals(2, onAllNodesWithTag("diff-line-context").fetchSemanticsNodes().size)
        }
    }
}
