// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.plugins.official.chat.ChatPanel
import com.superiorsoftllc.infantry.plugins.official.chat.ChatSlots
import com.superiorsoftllc.infantry.plugins.official.chat.ComposerArgs
import com.superiorsoftllc.infantry.plugins.official.chat.ComposerInsert
import com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat
import com.superiorsoftllc.infantry.plugins.official.chat.MessageArgs
import com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed
import com.superiorsoftllc.infantry.plugins.official.chatfeed.MESSAGE_ACTIONS_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.plainMessageText
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.ChildSlot
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.LocalSlotArgs
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.SLOT_EMPTY_TAG
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CHAT-VOICE-SEAMS-1: Infantry_Chat's `chat.composer.actions` and `chat.message.actions` slots, filled by a test plugin built
 * only from the public API, get their context; a `chat.composer.insert` event lands in the draft (append / replace); its
 * `send: true` is ignored while `chat.allowPluginSend` is off; with no fill the composer and the feed draw as before.
 */
@OptIn(ExperimentalTestApi::class)
class ChatVoiceSeamsUiTest {
    private val json = Json { encodeDefaults = true }
    private val session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p")
    private val base = CoreModules.all + InfantryChat + InfantryChatFeed

    private fun slotModule(slot: String, tag: String, show: (Map<String, Any?>) -> String) = InfantryModule(
        ModuleManifest("Acme_Voice", "1.0.0", author = "Acme", license = "NOASSERTION", panels = listOf(PanelDeclaration("acme.$tag", slot))),
        tier = Tier.OFFICIAL,
        panels = listOf(PanelProvider(id = "acme.$tag", panel = slot, order = 0, testTag = "sec-$tag") { Text(show(LocalSlotArgs.current), Modifier.testTag(tag)) }),
    )

    private val composerProbe = slotModule(ChatSlots.COMPOSER_ACTIONS, "mic") { a ->
        "${a[ComposerArgs.SESSION_ID]}|${a[ComposerArgs.DRAFT]}|${a[ComposerArgs.SENDING]}"
    }

    private fun withPanel(factory: TabFactory, chat: ChatPanelState = ChatPanelState(), body: androidx.compose.ui.test.ComposeUiTest.(ChangesFakeLink, ChatPanelState) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1000, 800) {
                setContent {
                    InfantryTheme {
                        CompositionLocalProvider(LocalTabFactory provides factory) {
                            ChatPanel("a", controller, Modifier.fillMaxSize(), chat = chat)
                        }
                    }
                }
                link.push(Envelope(Envelope.READY, "contract=1.200.0 provider=stub instance=i1"))
                link.push(Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), listOf(session))))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive && controller.state.value.agents.containsKey("a") }
                waitForIdle()
                body(link, chat)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a composer action fill is drawn in the composer with the composer context`() {
        val chat = ChatPanelState().apply { edit("draft one") }
        withPanel(TabFactory(base + composerProbe), chat) { _, _ ->
            onNodeWithTag("mic").assertTextEquals("a|draft one|false")
            chat.edit("draft two")
            waitForIdle()
            onNodeWithTag("mic").assertTextEquals("a|draft two|false")
            onAllNodesWithTag(SLOT_EMPTY_TAG).assertCountEquals(0)
        }
    }

    @Test fun `with no fill the composer draws no slot row and no empty state`() {
        withPanel(TabFactory(base)) { _, _ ->
            onNodeWithTag(PROMPT_BOX_TAG).assertExists()
            onAllNodesWithTag("sec-mic").assertCountEquals(0)
            onAllNodesWithTag(SLOT_EMPTY_TAG).assertCountEquals(0)
        }
    }

    @Test fun `an insert appends to or replaces the draft of its session`() {
        val factory = TabFactory(base)
        withPanel(factory, ChatPanelState().apply { edit("hello") }) { _, chat ->
            factory.events.publish(ComposerInsert("a", "world").toEvent())
            waitForIdle()
            assertEquals("hello\nworld", chat.draft)
            factory.events.publish(PluginEvent(ChatSlots.COMPOSER_INSERT_EVENT, mapOf("text" to "fresh", "mode" to "replace")))
            waitForIdle()
            assertEquals("fresh", chat.draft)
            // Another session's insert is not this composer's.
            factory.events.publish(ComposerInsert("b", "elsewhere").toEvent())
            waitForIdle()
            assertEquals("fresh", chat.draft)
        }
    }

    @Test fun `send true is ignored while the plugin-send setting is off`() {
        val factory = TabFactory(base)
        withPanel(factory) { link, chat ->
            factory.events.publish(ComposerInsert("a", "dictated", send = true).toEvent())
            waitForIdle()
            Thread.sleep(300)
            waitForIdle()
            assertEquals("dictated", chat.draft)
            assertTrue(link.sent.none { it.type == Envelope.PROMPT }, link.sent.map { it.type }.toString())
        }
    }

    @Test fun `the insert event parses its wire form`() {
        assertEquals(ComposerInsert(null, "x"), ComposerInsert.of(PluginEvent(ChatSlots.COMPOSER_INSERT_EVENT, mapOf("text" to "x"))))
        assertEquals(ComposerInsert("s", "x", replace = true, send = true), ComposerInsert.of(ComposerInsert("s", "x", true, true).toEvent()))
        assertNull(ComposerInsert.of(PluginEvent(ChatSlots.COMPOSER_INSERT_EVENT, mapOf("mode" to "append"))))
        assertNull(ComposerInsert.of(PluginEvent("other", mapOf("text" to "x"))))
    }

    @Test fun `markdown is stripped from the message text`() {
        assertEquals("Title\nSome bold and code and a link.\nitem one\nsnake_case_name", plainMessageText("# Title\nSome **bold** and `code` and a [link](http://x).\n- item one\nsnake_case_name"))
        assertEquals("let x = 1", plainMessageText("```kotlin\nlet x = 1\n```"))
    }

    // --- chat.message.actions, drawn by the feed on each message row ---

    private val agent = AgentView(
        session = session, role = "pm",
        entries = listOf(TranscriptEntry(1, LineKind.USER, "hi there", 1), TranscriptEntry(2, LineKind.TEXT, "hello **back**", 2)),
    )

    private val messageProbe = slotModule(ChatSlots.MESSAGE_ACTIONS, "read-aloud") { a ->
        listOf(MessageArgs.SESSION_ID, MessageArgs.MESSAGE_ID, MessageArgs.AUTHOR, MessageArgs.AUTHOR_ROLE, MessageArgs.TEXT).joinToString("|") { "${a[it]}" }
    }

    private fun withFeed(factory: TabFactory, body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = Controller(scope, ChangesFakeLink(scope))
            val state = AppState(agents = mapOf("a" to agent))
            runDesktopComposeUiTest(900, 700) {
                setContent {
                    InfantryTheme {
                        CompositionLocalProvider(LocalTabFactory provides factory) {
                            ChildSlot("chat.feed", state, controller, Modifier.fillMaxSize(), args = mapOf("sessionId" to "a", "controller" to controller))
                        }
                    }
                }
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a message action fill shows on hover with the message context`() {
        withFeed(TabFactory(base + messageProbe)) {
            onAllNodesWithTag(MESSAGE_ACTIONS_TAG).assertCountEquals(0)
            onNodeWithText("hello back", substring = true).performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithTag("read-aloud").assertTextEquals("a|1|agent|pm|hello back")
            onNodeWithText("hi there").performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithTag("read-aloud").assertTextEquals("a|0|owner|null|hi there")
        }
    }

    @Test fun `with no message action fill the feed draws no actions`() {
        withFeed(TabFactory(base)) {
            onNodeWithText("hi there").performMouseInput { moveTo(center) }
            waitForIdle()
            onAllNodesWithTag(MESSAGE_ACTIONS_TAG).assertCountEquals(0)
        }
    }

    // --- PLUGIN-SLOT-UX-1: no credit tag on inline fills; chat.message.header ---

    private fun communityModule(slot: String, tag: String, text: String) = InfantryModule(
        ModuleManifest("Acme_Voice", "1.0.0", author = "Acme", license = "Apache-2.0", panels = listOf(PanelDeclaration("acme.$tag", slot))),
        tier = Tier.COMMUNITY,
        panels = listOf(PanelProvider(id = "acme.$tag", panel = slot, order = 0, testTag = "sec-$tag") { Text(text, Modifier.testTag(tag)) }),
    )

    @Test fun `a community header fill draws on every card with no credit tag`() {
        val header = communityModule(ChatSlots.MESSAGE_HEADER, "hdr", "H")
        withFeed(TabFactory(base + header)) {
            onAllNodesWithTag("hdr").assertCountEquals(2)
            onAllNodesWithTag(com.superiorsoftllc.infantry.ui.plugin.COMMUNITY_BADGE_TAG).assertCountEquals(0)
        }
    }

    @Test fun `a community composer fill has no credit tag and an empty fill takes no height`() {
        val empty = InfantryModule(
            ModuleManifest("Acme_Voice", "1.0.0", author = "Acme", license = "Apache-2.0", panels = listOf(PanelDeclaration("acme.e", ChatSlots.COMPOSER_ACTIONS))),
            tier = Tier.COMMUNITY,
            panels = listOf(PanelProvider(id = "acme.e", panel = ChatSlots.COMPOSER_ACTIONS, order = 0, testTag = "sec-e") { }),
        )
        withPanel(TabFactory(base + empty)) { _, _ ->
            onAllNodesWithTag(com.superiorsoftllc.infantry.ui.plugin.COMMUNITY_BADGE_TAG).assertCountEquals(0)
            val h = onNodeWithTag("sec-e").fetchSemanticsNode().size.height
            assertEquals(0, h)
        }
    }
}
