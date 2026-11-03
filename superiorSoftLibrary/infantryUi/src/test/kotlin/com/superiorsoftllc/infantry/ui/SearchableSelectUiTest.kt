// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.SearchText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ROLE-FIRST S7: the generic searchable list every executor step is built from. Rendered for real. */
@OptIn(ExperimentalTestApi::class)
class SearchableSelectUiTest {
    private val ids = listOf("claude-opus-4", "claude-sonnet-4", "claude-sonnet-4-5", "gpt-5")

    private fun ComposeUiTest.host(items: List<String>, picked: (String) -> Unit = {}, backOut: (() -> Unit)? = null) {
        setContent {
            var q by remember { mutableStateOf("") }
            SearchableSelect(
                items = items, query = q, onQueryChange = { q = it }, onPick = picked,
                key = { it }, label = { it }, searchTag = "sel", itemTag = { "sel:$it" },
                text = { SearchText(id = it) }, isSelected = { it == "gpt-5" },
                badge = { if (it.startsWith("claude")) "anthropic" else null },
                emptyText = "No models listed", onBackOut = backOut,
            )
        }
    }

    private fun ComposeUiTest.press(key: Key) { onNodeWithTag("sel").performKeyInput { pressKey(key) }; waitForIdle() }

    @Test
    fun `typing filters with every term, ranked, and the count says how many of how many`() = runDesktopComposeUiTest(800, 600) {
        host(ids)
        onNodeWithTag("sel-count").assertTextContains("4 of 4")
        onNodeWithTag("sel").performTextInput("son 4")
        onNodeWithTag("sel:claude-opus-4").assertDoesNotExist()
        onNodeWithTag("sel:gpt-5").assertDoesNotExist()
        onNodeWithTag("sel:claude-sonnet-4").assertExists()
        onNodeWithTag("sel:claude-sonnet-4-5").assertExists()
        onNodeWithTag("sel-count").assertTextContains("2 of 4")
    }

    @Test
    fun `the chosen row is marked and the badge shows beside it`() = runDesktopComposeUiTest(800, 600) {
        host(ids)
        // PICKER-UX: the chosen row carries the radio mark ("picked"), the others do not.
        onNodeWithTag("sel:gpt-5").assertTextContains("gpt-5", substring = true).assertContentDescriptionContains(RADIO_PICKED)
        onNodeWithTag("sel:claude-opus-4").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        onNodeWithTag("sel:claude-opus-4").assertTextContains("anthropic", substring = true)
    }

    @Test
    fun `Down Up move the highlight, Enter picks it, Esc clears the query`() = runDesktopComposeUiTest(800, 600) {
        var got: String? = null
        host(ids, picked = { got = it })
        onNodeWithTag("sel").performClick()
        onNodeWithTag("sel:claude-opus-4").assertIsSelected()
        press(Key.DirectionDown); press(Key.DirectionDown)
        onNodeWithTag("sel:claude-sonnet-4-5").assertIsSelected()
        onNodeWithTag("sel:claude-opus-4").assertIsNotSelected()
        press(Key.DirectionUp)
        onNodeWithTag("sel:claude-sonnet-4").assertIsSelected()
        press(Key.Enter)
        assertEquals("claude-sonnet-4", got)

        onNodeWithTag("sel").performTextInput("gpt")
        onNodeWithTag("sel:claude-opus-4").assertDoesNotExist()
        press(Key.Escape)
        onNodeWithTag("sel:claude-opus-4").assertExists()
    }

    @Test
    fun `Backspace on an empty box backs out to the previous step`() = runDesktopComposeUiTest(800, 600) {
        var backs = 0
        host(ids, backOut = { backs++ })
        onNodeWithTag("sel").performClick()
        press(Key.Backspace)
        assertEquals(1, backs)
    }

    @Test
    fun `no hit and nothing listed are each said on screen, and Enter on no hit picks nothing`() = runDesktopComposeUiTest(800, 600) {
        var got: String? = null
        host(ids, picked = { got = it })
        onNodeWithTag("sel").performTextInput("zzz")
        onNodeWithTag("sel-nohit").assertTextContains("zzz", substring = true)
        press(Key.Enter)
        assertNull(got)
    }

    @Test
    fun `an empty list says so instead of showing nothing`() = runDesktopComposeUiTest(800, 600) {
        host(emptyList())
        onNodeWithTag("sel-empty").assertExists()
        onNodeWithText("No models listed").assertExists()
    }
}
