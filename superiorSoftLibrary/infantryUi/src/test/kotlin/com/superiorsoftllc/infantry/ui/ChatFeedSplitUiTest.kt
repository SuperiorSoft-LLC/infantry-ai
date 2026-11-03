package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedPreference
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat
import com.superiorsoftllc.infantry.plugins.official.chat.ShellCenter
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.model.withTimeline
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ChildSlot
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.RowRenderer
import com.superiorsoftllc.infantry.ui.plugin.SLOT_EMPTY_TAG
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.superiorsoftllc.infantry.ui.block.layout.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHAT-FEED-SPLIT-1: the chat is a parent block and the feed a child plugin in its `chat.feed` slot. The golden tree, today's
 * rows through the slot, the override (replace, before, after) and the disabled feed, and the kind-keyed row renderers (a
 * test-only kind registered by a test module through the public API; an unknown kind draws the generic row).
 */
@OptIn(ExperimentalTestApi::class)
class ChatFeedSplitUiTest {
    private val agent = AgentView(
        session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        role = "pm",
        entries = listOf(
            TranscriptEntry(1, LineKind.USER, "hi there", 1),
            TranscriptEntry(2, LineKind.TEXT, "hello back", 2),
            TranscriptEntry(3, LineKind.FINISHED, "done", 3),
        ),
    )

    private fun item(id: String, kind: String = "", text: String = "ran", at: Long = 10, meta: Map<String, String> = emptyMap()) =
        TimelineItem(id, "a", at, "script", "AUTOMATION", text, meta = meta, kind = kind)

    private fun withFeedSlot(factory: TabFactory, items: List<TimelineItem> = emptyList(), body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = Controller(scope, ChangesFakeLink(scope))
            val state = AppState(agents = mapOf("a" to agent)).withTimeline(items)
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

    /** A test module built only from the public API: sections for the slot, renderers for kinds, and an optional replace preference. */
    private fun testModule(name: String, panels: List<PanelProvider> = emptyList(), kinds: Map<String, RowRenderer> = emptyMap(), replace: String? = null) = InfantryModule(
        ModuleManifest(
            name, "1.0.0", author = "Acme", license = "NOASSERTION",
            panels = panels.map { PanelDeclaration(it.id, it.panel) },
            implementations = kinds.keys.map { ImplementationDeclaration(FEED_RENDERERS, it) },
            preferences = if (replace != null) listOf(PreferenceDeclaration(replace, panels.first().id)) else emptyList(),
        ),
        tier = Tier.COMMUNITY,
        panels = panels,
        implementations = if (kinds.isEmpty()) emptyMap() else mapOf(FEED_RENDERERS to kinds),
    )

    private fun section(id: String, order: Int, tag: String, fill: Boolean = false) =
        PanelProvider(id = id, panel = "chat.feed", order = order, testTag = "sec-$tag", fill = fill) { Text(tag, Modifier.testTag(tag)) }

    private val base = CoreModules.all + InfantryChat + InfantryChatFeed

    private fun ids(b: Block): List<String> = listOf(b.id) + b.children.flatMap { ids(it) }

    @Test fun `the chat tree keeps its node ids and has no feed node of its own`() {
        val all = ids(ShellCenter)
        assertTrue("infantry.shell.center.chat.transcript" in all, all.toString())
        assertTrue("infantry.shell.center.chat.composer" in all, all.toString())
        assertTrue(all.none { "feed" in it }, all.toString())
    }

    @Test fun `today's rows draw through the slot`() {
        val f = TabFactory(base)
        assertEquals(emptyList(), f.problems)
        withFeedSlot(f, listOf(item("r1"))) {
            onNodeWithText("hello back").assertExists()
            onAllNodesWithTag(CONTRIBUTION_ROW_TAG).assertCountEquals(1)
            onAllNodesWithTag(SLOT_EMPTY_TAG).assertCountEquals(0)
        }
    }

    @Test fun `the feed registers exactly today's kinds`() {
        assertEquals(
            setOf("user", "text", "reasoning", "status", "error", "finished", "log", "write", "automation"),
            InfantryChatFeed.implementations.getValue(FEED_RENDERERS).keys,
        )
    }

    @Test fun `with the feed disabled the slot shows its generic empty state and nothing crashes`() {
        val f = TabFactory(CoreModules.all + InfantryChat)
        assertEquals(emptyList(), f.problems)
        withFeedSlot(f) {
            onNodeWithTag(SLOT_EMPTY_TAG).assertExists()
            onNodeWithText("hello back").assertDoesNotExist()
        }
    }

    @Test fun `a plugin replaces the feed through the public API`() {
        val acme = testModule("Acme_Feed", listOf(section("acme.feed", 0, "acme-feed", fill = true)), replace = "infantry.chatfeed")
        val f = TabFactory(base + acme, preferences = listOf(AcceptedPreference("Acme_Feed", "infantry.chatfeed", "acme.feed")))
        assertEquals(emptyList(), f.problems)
        withFeedSlot(f) {
            onNodeWithTag("acme-feed").assertExists()
            onNodeWithText("hello back").assertDoesNotExist()
            onAllNodesWithTag(SLOT_EMPTY_TAG).assertCountEquals(0)
        }
    }

    @Test fun `a plugin adds blocks before and after the feed`() {
        val acme = testModule("Acme_Around", listOf(section("acme.before", -10, "acme-before"), section("acme.after", 10, "acme-after")))
        val f = TabFactory(base + acme)
        assertEquals(emptyList(), f.problems)
        withFeedSlot(f) {
            val feedTop = onNodeWithText("hello back").fetchSemanticsNode().boundsInRoot.top
            assertTrue(onNodeWithTag("acme-before").fetchSemanticsNode().boundsInRoot.top < feedTop)
            assertTrue(onNodeWithTag("acme-after").fetchSemanticsNode().boundsInRoot.top > feedTop)
        }
    }

    @Test fun `a plugin's renderer draws its own kind and an unknown kind draws the generic row`() {
        val acme = testModule("Acme_Kind", kinds = mapOf("test.kind" to RowRenderer { Text("custom: ${(it.item as com.superiorsoftllc.infantry.ui.model.ChatItem.Contribution).item.meta["k"]}",Modifier.testTag("custom-row")) }))
        val f = TabFactory(base + acme)
        assertEquals(emptyList(), f.problems)
        withFeedSlot(f, listOf(
            item("k1", kind = "test.kind", text = "ignored", at = 10, meta = mapOf("k" to "v1")),
            item("k2", kind = "nobody.registered", text = "plain words", at = 11),
        )) {
            onNodeWithTag("custom-row").assertExists()
            onNodeWithText("custom: v1").assertExists()
            onNodeWithTag(GENERIC_ROW_TAG).assertExists()
            onNodeWithText("plain words").assertExists()
        }
    }
}
