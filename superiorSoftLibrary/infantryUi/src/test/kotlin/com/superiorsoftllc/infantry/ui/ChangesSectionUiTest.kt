// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.changes.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import kotlin.test.Test
import kotlin.test.assertTrue

/** GIT VIEW: what the pane actually draws for each of the three states. */
@OptIn(ExperimentalTestApi::class)
class ChangesSectionUiTest {

    @Test
    fun unknownIsDrawnAsUnknown_neverAsClean() {
        runDesktopComposeUiTest(900, 600) {
            setContent { ChangesSection(ChangesView.Unknown("worktree missing on disk")) }
            onNodeWithText("Changes unknown: worktree missing on disk").assertExists()
            onNodeWithText("Working tree clean. Nothing to commit.").assertDoesNotExist()
        }
    }

    @Test
    fun cleanIsDrawnAsClean() {
        runDesktopComposeUiTest(900, 600) {
            setContent { ChangesSection(ChangesView.Clean) }
            onNodeWithText("Working tree clean. Nothing to commit.").assertExists()
        }
    }

    @Test
    fun filesAreListedWithBadgeAndPath_underTheirSide() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                ChangesSection(
                    ChangesView.Files(
                        staged = listOf(ChangedFile("new name.kt", ChangeKind.RENAMED, staged = true, fromPath = "old name.kt")),
                        unstaged = listOf(
                            ChangedFile("a.kt", ChangeKind.MODIFIED, staged = false),
                            ChangedFile("b.txt", ChangeKind.UNTRACKED, staged = false),
                        ),
                        truncated = false,
                    ),
                )
            }
            onNodeWithText("3 changed files").assertExists()
            onNodeWithText("Staged (1)").assertExists()
            onNodeWithText("Not staged (2)").assertExists()
            onNodeWithText("old name.kt -> new name.kt").assertExists()
            onNodeWithText("M").assertExists()
            onNodeWithText("?").assertExists()
            onNodeWithText("Working tree clean. Nothing to commit.").assertDoesNotExist()
        }
    }

    /** Owner bug 2026-10-19: in a narrow panel a long path wrapped and drew "untracked" one letter per line. */
    @Test
    fun aLongPathStaysOnOneLine_keepsItsExtension_andTheLabelStaysHorizontal() {
        runDesktopComposeUiTest(900, 600) {
            var path by mutableStateOf("a.md")
            setContent {
                Box(Modifier.width(200.dp).testTag("changes")) {
                    ChangesSection(ChangesView.Files(emptyList(), listOf(ChangedFile(path, ChangeKind.UNTRACKED, staged = false)), truncated = false))
                }
            }
            val shortHeight = onNodeWithTag("changes").fetchSemanticsNode().size.height

            path = ".notes/research/2026-10-10-koog-vs-infantry-verdict.md"
            waitForIdle()
            val longHeight = onNodeWithTag("changes").fetchSemanticsNode().size.height
            assertTrue(longHeight <= shortHeight, "a long path must not wrap the row: $longHeight px vs $shortHeight px for a.md")

            onNodeWithText(".md").assertIsDisplayed()
            onNodeWithText("untracked").assertIsDisplayed()
            val label = onNodeWithText("untracked").fetchSemanticsNode().size
            assertTrue(label.width > label.height, "the status label is drawn horizontally: $label")
        }
    }
}
