package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.RoleIcons
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test

/**
 * ROLE-PILL-ICONS-1: the Switch agent picker's role pills paint the SAME role icon the role cards do (ROLE-ICON-1,
 * read from its [RoleIconKey] semantics, so the glyph is asserted, not pixels); "none" and "custom" paint the neutral
 * [RoleIcons.FALLBACK]. Each harness pill carries a bundled harness glyph (HarnessIconKey semantics).
 */
@OptIn(ExperimentalTestApi::class)
class RolePillIconsUiTest {
    // The tags and semantics keys are spelled out here, not taken from production, so this file is the spec.
    private fun paints(key: String) = SemanticsMatcher("role icon paints $key") { n -> n.config.any { (k, v) -> k.name == "RoleIconKey" && v == key } }
    private fun harnessPaints(key: String) = SemanticsMatcher("harness icon paints $key") { n -> n.config.any { (k, v) -> k.name == "HarnessIconKey" && v == key } }
    private fun roleIcon(roleId: String) = "role-icon:switch:$roleId"
    private fun harnessIcon(harness: String) = "harness-icon:$harness"

    private val now = System.currentTimeMillis()
    private val agent = AgentView(
        session = SessionRecord(id = "sw000001", provider = "opencode", workingDir = "/w", spawnedAt = 1, lastHeartbeat = now, state = SessionState.IDLE, providerSessionId = "p"),
        role = "qa",
    )
    private val state = AppState(
        agents = mapOf(agent.id to agent),
        selectedId = agent.id,
        roles = listOf(Role("backend", "Backend", "#3B82F6"), Role("qa", "QA", "#10B981"), Role("custom", "Custom", "#888888")),
        providers = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"), ProviderInfo("pi")),
        connection = ConnectionInfo(state = ConnState.CONNECTED),
    )

    @Test
    fun `the switch agent role pills paint the role-card icon and the harness pills a harness glyph`() = runDesktopComposeUiTest(1400, 600) {
        setContent { SwitchAgentPicker(state, agent, SwitchAgentAsk(agent.id), { _, _ -> CommandResult.Sent }, {}) }
        waitForIdle()
        onNodeWithTag(roleIcon("backend"), useUnmergedTree = true).assert(paints(RoleIcons.defaultFor("backend")))
        onNodeWithTag(roleIcon("qa"), useUnmergedTree = true).assert(paints(RoleIcons.defaultFor("qa")))
        onNodeWithTag(roleIcon("custom"), useUnmergedTree = true).assert(paints(RoleIcons.FALLBACK))
        onNodeWithTag(roleIcon("none"), useUnmergedTree = true).assert(paints(RoleIcons.FALLBACK))
        // The pill keeps its own tag and its hand cursor; the icon sits inside it.
        onNodeWithTag(SWITCH_ROLE_TAG_PREFIX + "backend").assertExists()
        listOf("opencode", "claude", "pi").forEach { h ->
            onNodeWithTag(harnessIcon(h), useUnmergedTree = true).assert(harnessPaints(h))
        }
    }
}
