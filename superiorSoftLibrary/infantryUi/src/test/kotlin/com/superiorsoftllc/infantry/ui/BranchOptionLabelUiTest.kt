package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.changes.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BUG-BRANCH-WRAP: a long worktree branch keeps its name on one line and its "worktree" tail horizontal. */
@OptIn(ExperimentalTestApi::class)
class BranchOptionLabelUiTest {
    private val longName = "infantry/bug-branch-wrap-" + "x".repeat(70 - "infantry/bug-branch-wrap-".length)

    @Test
    fun longWorktreeNameStaysOneLineAndTailIsNotAColumn() = runComposeUiTest {
        assertEquals(70, longName.length)
        setContent {
            // The width the dropdown is capped to: far narrower than a 70-char mono name.
            Box(Modifier.width(BRANCH_MENU_MAX_WIDTH)) { BranchOptionLabel(longName, "worktree", selected = false) }
        }

        val name = layoutOf(BRANCH_SELECT_NAME_TAG + longName)
        assertEquals(1, name.lineCount, "the branch name must be single-line")
        assertTrue(name.hasVisualOverflow, "a 70-char name in the capped menu ellipsizes rather than wrapping")

        val tail = layoutOf(BRANCH_SELECT_TAIL_TAG + longName)
        assertEquals(1, tail.lineCount, "the worktree tag must not wrap per character")
        val tailHeight = onNodeWithTag(BRANCH_SELECT_TAIL_TAG + longName).getBoundsInRoot().height
        val oneLine = with(density) { tail.getLineBottom(0).toDp() - tail.getLineTop(0).toDp() }
        // 1dp absorbs px rounding of line metrics; a per-character column would be ~8 lines tall.
        assertEquals(oneLine.value, tailHeight.value, 1f, "tail node height equals one line")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.layoutOf(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }
}
