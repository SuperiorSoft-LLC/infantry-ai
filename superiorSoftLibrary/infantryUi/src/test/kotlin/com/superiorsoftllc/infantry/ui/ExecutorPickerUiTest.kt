// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.ExecutorPickerModel
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ROLE-FIRST S7: Harness -> Provider -> Model, each step a SearchableSelect, driven by ExecutorPickerModel. */
@OptIn(ExperimentalTestApi::class)
class ExecutorPickerUiTest {
    private val harnesses = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"), ProviderInfo("pi", defaultModel = "deepseek-chat"))
    private val catalog = listOf(
        ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner")),
        ProviderModels("opencode", "anthropic", "opencode / anthropic", listOf("anthropic/claude-sonnet-4")),
        ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet")),
        ProviderModels("pi", "", "pi", emptyList(), note = "could not list models: pi not on PATH"),
    )
    private var current = ExecutorPickerModel(harnesses, catalog)

    private fun ComposeUiTest.host(start: ExecutorPickerModel = ExecutorPickerModel(harnesses, catalog)) {
        current = start
        setContent {
            var m by remember { mutableStateOf(start) }
            ExecutorPicker(m, { m = it; current = it })
        }
    }

    @Test
    fun `picking a model on All selects its provider, the reverse arrow`() = runDesktopComposeUiTest(900, 900) {
        host()
        onNodeWithTag("executor-harness-opencode").performClick()
        onNodeWithTag("executor-provider-all").assertContentDescriptionContains(RADIO_PICKED)
        onNodeWithTag("executor-model-anthropic/claude-sonnet-4").performClick()
        waitForIdle()
        assertEquals(PickedModel("opencode", "anthropic/claude-sonnet-4"), current.picked)
        assertEquals("anthropic", current.selectedGroup)
        onNodeWithTag("executor-provider-anthropic").assertContentDescriptionContains(RADIO_PICKED)
        // The provider narrows the model list.
        onNodeWithTag("executor-model-deepseek/deepseek-chat").assertDoesNotExist()
    }

    @Test
    fun `a harness change clears a model it does not list, and a single-provider harness shows a label`() = runDesktopComposeUiTest(900, 900) {
        host(ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("opencode", "deepseek/deepseek-chat")))
        onNodeWithTag("executor-harness-claude").performClick()
        waitForIdle()
        assertEquals("claude", current.harness)
        assertNull(current.model)
        onNodeWithTag("executor-provider-label").assertTextContains("anthropic", substring = true)
        onNodeWithTag("executor-provider-search").assertDoesNotExist()
        onNodeWithTag("executor-model-sonnet").assertExists()
    }

    @Test
    fun `a harness with no catalog keeps a free-text model and shows why the list is missing`() = runDesktopComposeUiTest(900, 900) {
        host()
        onNodeWithTag("executor-harness-pi").performClick()
        onNodeWithTag("executor-note").assertTextContains("pi not on PATH", substring = true)
        onNodeWithTag("executor-model-search").assertDoesNotExist()
        onNodeWithTag("executor-model-freetext").performTextInput("my-model")
        waitForIdle()
        assertEquals(PickedModel("pi", "my-model"), current.picked)
    }

    @Test
    fun `typing and the keyboard choose a model`() = runDesktopComposeUiTest(900, 900) {
        host()
        onNodeWithTag("executor-harness-opencode").performClick()
        onNodeWithTag("executor-model-search").performTextInput("deep")
        onNodeWithTag("executor-model-anthropic/claude-sonnet-4").assertDoesNotExist()
        onNodeWithTag("executor-model-deepseek/deepseek-chat").assertIsSelected()
        onNodeWithTag("executor-model-search").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("executor-model-search").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("deepseek/deepseek-reasoner", current.model)
    }

    @Test
    fun `no harnesses yet is a loading line`() = runDesktopComposeUiTest(900, 900) {
        host(ExecutorPickerModel())
        onNodeWithTag("executor-loading").assertExists()
    }

    @Test
    fun `a stale stored model is flagged, not rewritten`() = runDesktopComposeUiTest(900, 900) {
        host(ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("claude", "claude-retired-1")))
        onNodeWithTag("executor-model-missing").assertTextContains("claude-retired-1", substring = true)
        assertEquals("claude-retired-1", current.model)
    }
}
