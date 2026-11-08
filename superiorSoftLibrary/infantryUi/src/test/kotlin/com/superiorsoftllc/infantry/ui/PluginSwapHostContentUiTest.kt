// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.plugins.official.chat.ChatSlots
import com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat
import com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.ChildSlot
import com.superiorsoftllc.infantry.ui.plugin.ChildSlotRow
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.Panels
import com.superiorsoftllc.infantry.ui.plugin.PluginFaults
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * VOICEINPUT-BLANK-1: enabling a plugin live (a PLUGIN-HOTSWAP swap: a new [TabFactory] over the same module objects plus
 * the new one) must leave every other plugin's sections drawn: the chat feed and the left panel keep their content, the new
 * plugin's composer item shows, and a new plugin that throws hides only its own item.
 */
@OptIn(ExperimentalTestApi::class)
class PluginSwapHostContentUiTest {
    private val session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")
    private val agent = AgentView(session = session, role = "pm", entries = listOf(TranscriptEntry(1, LineKind.USER, "hi there", 1)))

    /** Stands in for Infantry_Agents: a `left` section loaded at start. */
    private val leftPanel = InfantryModule(
        ModuleManifest("Acme_Left", "1.0.0", author = "Acme", license = "NOASSERTION", panels = listOf(PanelDeclaration("acme.left", Panels.LEFT))),
        tier = Tier.OFFICIAL,
        panels = listOf(PanelProvider(id = "acme.left", panel = Panels.LEFT, order = 0, testTag = "sec-left", fill = true) { Text("agent list", Modifier.testTag("left-content")) }),
    )

    /** Shaped like Infantry_VoiceInput: one item in Infantry_Chat's composer actions slot. */
    private fun voice(throws: Boolean) = InfantryModule(
        ModuleManifest("Acme_Voice", "1.0.0", author = "Acme", license = "NOASSERTION", panels = listOf(PanelDeclaration("acme.mic", ChatSlots.COMPOSER_ACTIONS))),
        tier = Tier.COMMUNITY,
        panels = listOf(PanelProvider(id = "acme.mic", panel = ChatSlots.COMPOSER_ACTIONS, order = 10, testTag = "sec-mic") {
            if (throws) error("voice plugin bug")
            Text("mic", Modifier.testTag("mic"))
        }),
    )

    private val base = CoreModules.all + InfantryChat + InfantryChatFeed + leftPanel

    @BeforeTest fun clean() { OverrideHealth.clear(); PluginFaults.reset() }
    @AfterTest fun after() { OverrideHealth.clear(); PluginFaults.reset() }

    private fun swapIn(added: InfantryModule, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = Controller(scope, ChangesFakeLink(scope))
            val state = AppState(agents = mapOf("a" to agent))
            var factory by mutableStateOf(TabFactory(base))
            runDesktopComposeUiTest(900, 700) {
                setContent {
                    InfantryTheme {
                        CompositionLocalProvider(LocalTabFactory provides factory) {
                            Column {
                                ChildSlot(Panels.LEFT, state, controller, Modifier.fillMaxWidth().height(100.dp))
                                ChildSlot("chat.feed", state, controller, Modifier.fillMaxWidth().height(300.dp), args = mapOf("sessionId" to "a", "controller" to controller))
                                ChildSlotRow(ChatSlots.COMPOSER_ACTIONS, state, controller, args = mapOf("sessionId" to "a"))
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("hi there").assertExists()
                onNodeWithTag("left-content").assertExists()
                // The live swap: the same module objects keep generation 0; the new plugin loads in generation 1.
                factory = TabFactory(base + added, generation = { if (it == added.manifest.name) 1 else 0 })
                waitForIdle()
                mainClock.advanceTimeBy(500)
                waitForIdle()
                check()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `enabling a composer plugin live keeps the chat feed and the left panel drawn`() = swapIn(voice(throws = false)) {
        onNodeWithText("hi there").assertExists()
        onNodeWithTag("left-content").assertExists()
        onNodeWithTag("mic").assertExists()
    }

    @Test fun `a composer plugin that throws hides only its own item`() = swapIn(voice(throws = true)) {
        onNodeWithText("hi there").assertExists()
        onNodeWithTag("left-content").assertExists()
        onNodeWithTag("mic").assertDoesNotExist()
    }
}
