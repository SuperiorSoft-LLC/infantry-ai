// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertTrue

/** SWITCH-MODAL-ANDROID-1: at phone size (360 x 640 dp) the Switch agent footer buttons show their text and sit inside the window. */
@OptIn(ExperimentalTestApi::class)
class SwitchPickerPhoneUiTest {
    private val agent = AgentView(
        session = SessionRecord(id = "sw000002", provider = "opencode", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        role = "qa",
    )
    private val state = AppState(
        agents = mapOf(agent.id to agent), selectedId = agent.id,
        roles = listOf(Role("backend", "Backend", "#3B82F6"), Role("qa", "QA", "#10B981"), Role("custom", "Custom", "#888888")),
        providers = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"), ProviderInfo("pi")),
        connection = ConnectionInfo(state = ConnState.CONNECTED),
    )

    @Test
    fun `footer buttons are labelled and fully inside a 360x640 window`() = runDesktopComposeUiTest(360, 640) {
        setContent { SwitchAgentPicker(state, agent, SwitchAgentAsk(agent.id), { _, _ -> CommandResult.Sent }, {}) }
        waitForIdle()
        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsDisplayed()
        onNodeWithText("Apply", useUnmergedTree = true).assertIsDisplayed()
        listOf(onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).getBoundsInRoot(), onNodeWithText("Cancel").getBoundsInRoot()).forEach { b ->
            assertTrue(b.left >= 0.dp && b.top >= 0.dp && b.right <= 360.dp && b.bottom <= 640.dp, "button outside window: $b")
            assertTrue(b.bottom - b.top >= 40.dp, "button squeezed: $b")
        }
    }
}
