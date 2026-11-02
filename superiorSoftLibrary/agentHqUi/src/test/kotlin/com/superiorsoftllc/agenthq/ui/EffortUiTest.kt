package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.effortChip
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EFFORT-1: the reasoning effort field and chip as composed: unset / supported / best effort / unsupported ("(not
 * applied)"), in the shared field and in the New agent dialog. The wording rules are in EffortModelTest.
 */
@OptIn(ExperimentalTestApi::class)
class EffortUiTest {
    private val tags = EffortTags("effort-switch", "effort-note")

    @Test fun `the chip states render their words, and an unset effort composes nothing`() = runDesktopComposeUiTest {
        setContent {
            effortChip("high", "supported")?.let { EffortChipView(it) }
            effortChip("max", "best_effort")?.let { EffortChipView(it) }
            effortChip("low", "unsupported", "not mapped")?.let { EffortChipView(it) }
            effortChip(null, "supported")?.let { EffortChipView(it) }
        }
        onNodeWithText("effort high").assertIsDisplayed()
        onNodeWithText("effort max (best effort)").assertIsDisplayed()
        onNodeWithText("effort low (not applied)").assertIsDisplayed()
        onAllNodes(hasTestTag(EFFORT_CHIP_TAG), useUnmergedTree = true).assertCountEquals(3)
    }

    @Test fun `the field is off by default with only its note, and on it offers every level and reports the pick`() = runDesktopComposeUiTest {
        var on by mutableStateOf(false)
        var level by mutableStateOf("medium")
        setContent { EffortField(on, level, "claude applies the effort", tags, onOn = { on = it }, onLevel = { level = it }) }
        onNodeWithTag(tags.note).assertTextContains("claude applies the effort")
        onNodeWithTag(EFFORT_LEVEL_TAG_PREFIX + "high").assertDoesNotExist()

        onNodeWithTag(tags.switch).performClick()
        com.superiorsoftllc.agenthq.ui.model.EffortLevels.ALL.forEach { onNodeWithTag(EFFORT_LEVEL_TAG_PREFIX + it.id).assertIsDisplayed() }
        onNodeWithText("✓ Medium").assertIsDisplayed()
        onNodeWithTag(EFFORT_LEVEL_TAG_PREFIX + "xhigh").performClick()
        assertEquals("xhigh", level)
        onNodeWithText("✓ Extra high").assertIsDisplayed()
    }

    private val state = AppState(
        roles = listOf(Role("qa", "QA Engineer", "#445566")),
        providers = listOf(
            ProviderInfo("claude", default = true, effort = "supported", effortHow = "--effort"),
            ProviderInfo("pi", effort = "best_effort", effortHow = "--thinking"),
            ProviderInfo("opencode", effort = "unsupported", effortHow = "not mapped yet"),
            ProviderInfo("legacy"),
        ),
        modelCatalog = listOf(
            ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus")),
            ProviderModels("pi", "anthropic", "pi / anthropic", listOf("sonnet")),
            ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/chat")),
            ProviderModels("legacy", "x", "legacy / x", listOf("m")),
        ),
    )

    @Test fun `the New agent dialog names each harness's support and hands over the level only when it is on`() = runDesktopComposeUiTest(1200, 900) {
        var got: String? = "unset-marker"
        setContent { NewAgentDialog(state, { _, _, _, _, effort -> got = effort; null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "qa").performClick()

        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "pi").performClick()
        onNodeWithTag(NEW_AGENT_EFFORT_NOTE_TAG).assertTextContains("pi applies it best effort", substring = true)
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "opencode").performClick()
        onNodeWithTag(NEW_AGENT_EFFORT_NOTE_TAG).assertTextContains("opencode does not apply an effort (it is recorded, not applied)", substring = true)
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "legacy").performClick()
        onNodeWithTag(NEW_AGENT_EFFORT_NOTE_TAG).assertTextContains("does not report whether it honours an effort", substring = true)
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "claude").performClick()
        onNodeWithTag(NEW_AGENT_EFFORT_NOTE_TAG).assertTextContains("claude applies the effort", substring = true)
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "opus").performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")

        // Unset (the default): Add hands over no effort.
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertNull(got)

        // On, a level picked: Add hands it over.
        onNodeWithTag(NEW_AGENT_EFFORT_SWITCH_TAG).performClick()
        onNodeWithTag(EFFORT_LEVEL_TAG_PREFIX + "high").performClick()
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertEquals("high", got)
    }
}
