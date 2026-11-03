// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.board.*
import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import com.superiorsoftllc.infantry.plugins.official.agents.*

import com.superiorsoftllc.infantry.plugins.official.header.*

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.AttentionItem
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.sound.INCOMING_SOUND_ENABLED_TAG
import com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSection
import com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSettings
import com.superiorsoftllc.infantry.ui.voice.VOICE_ENABLED_TAG
import com.superiorsoftllc.infantry.ui.voice.VoiceSettings
import com.superiorsoftllc.infantry.ui.voice.VoiceSettingsSection
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onParent
import kotlin.test.Test

/**
 * M2-10: every clickable shows the hand cursor on hover. The cursor itself is not readable from a test, so each
 * clickable also declares it in semantics under the key "HoverCursor" (like M2-9's "ResizeCursor"). The key name
 * and the expected icon are spelled out here, not taken from production, so this file is the spec.
 *
 * Covered: a centre tab, an agent card, its "⋯" button and a menu item, the confirm dialog's Button and
 * TextButton, the attention and connection chips, the three window controls, and the sidebar Board button.
 * A disabled control keeps the arrow.
 */
@OptIn(ExperimentalTestApi::class)
class HandCursorUiTest {

    private fun hasHandCursor() = SemanticsMatcher("hover cursor is the hand") { node ->
        node.config.any { (k, v) -> k.name == "HoverCursor" && v == PointerIcon.Hand }
    }

    private fun hasNoHandCursor() = SemanticsMatcher("hover cursor is not the hand") { node ->
        node.config.none { (k, v) -> k.name == "HoverCursor" && v == PointerIcon.Hand }
    }

    private val now = System.currentTimeMillis()
    private val qa = AgentView(
        session = SessionRecord(id = "qa000001", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = now, state = SessionState.IDLE, providerSessionId = "p"),
        role = "qa",
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "Ran the suite", 1)),
    )
    private val state = AppState(
        agents = mapOf(qa.id to qa),
        selectedId = qa.id,
        roles = listOf(Role("qa", "QA Engineer", "#10B981")),
        connection = ConnectionInfo(state = ConnState.CONNECTED),
        attention = listOf(AttentionItem(id = "a1", at = now, kind = "trigger", outcome = "tripped", detail = "x")),
    )

    private val chrome = object : WindowChrome {
        override val isMaximized = false
        override fun minimize() {}
        override fun toggleMaximize() {}
        override fun close() {}
    }

    private fun ComposeUiTest.assertHand(vararg tags: String) = tags.forEach { onNodeWithTag(it).assert(hasHandCursor()) }

    @Test
    fun `a centre tab shows the hand`() = runDesktopComposeUiTest(1000, 200) {
        setContent { CenterTabBar(state, "infantry.cli") {} }
        assertHand("center-tab-OUTPUT", "center-tab-GIT")
    }

    @Test
    fun `an agent card, its menu button, a menu item and the confirm buttons show the hand`() = runDesktopComposeUiTest(400, 600) {
        setContent { AgentsList(state, listOf(qa), onSelect = {}, onRemove = {}) }
        assertHand("agent-row-${qa.id}", "session-menu-${qa.id}")
        onNodeWithTag("session-menu-${qa.id}").performClick()
        assertHand("session-menu-open-${qa.id}", "session-remove-${qa.id}")
        onNodeWithTag("session-remove-${qa.id}").performClick()
        assertHand("session-remove-confirm", "session-remove-cancel")
    }

    @Test
    fun `a disabled menu button keeps the arrow`() = runDesktopComposeUiTest(400, 600) {
        val offline = state.copy(connection = ConnectionInfo(state = ConnState.FAILED))
        setContent { AgentsList(offline, listOf(qa), onSelect = {}, onRemove = {}) }
        onNodeWithTag("session-menu-${qa.id}").assert(hasNoHandCursor())
    }

    @Test
    fun `the title bar chips and window controls show the hand`() = runDesktopComposeUiTest(1400, 200) {
        setContent { TitleBar(state, AgentScope.entries.first(), onConnect = {}, onAttention = {}, chrome = chrome) }
        assertHand("title-bar-attention-chip", "title-bar-connection-chip", "title-bar-minimize", "title-bar-maximize", "title-bar-close")
    }

    @Test
    fun `the sidebar Board button shows the hand`() = runDesktopComposeUiTest(300, 100) {
        setContent { SidebarBoardButton(state, onOpen = {}) }
        assertHand("sidebar-board-button")
    }

    @Test
    fun `the voice settings switch and level chips show the hand`() = runDesktopComposeUiTest(600, 800) {
        setContent { VoiceSettingsSection(VoiceSettings(), onChange = {}) }
        assertHand(VOICE_ENABLED_TAG)
        // The level chips are FilterChips; the Urgent chip carries the hand.
        onAllNodesWithText("Urgent", useUnmergedTree = true)[0].onParent().assert(hasHandCursor())
    }

    @Test
    fun `the incoming comms sound switch shows the hand`() = runDesktopComposeUiTest(600, 600) {
        setContent { IncomingCommsSoundSection(IncomingCommsSoundSettings(), onChange = {}) }
        assertHand(INCOMING_SOUND_ENABLED_TAG)
    }
}
