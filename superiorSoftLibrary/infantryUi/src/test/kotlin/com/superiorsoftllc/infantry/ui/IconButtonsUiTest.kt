// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.changes.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX-ICON-1: the composer's action row, the sidebar's New agent link and the Changes panel's Refresh are icon buttons
 * with tooltips. The composer runs in the REAL window against a REAL in-process daemon (stub harness), so "Send sends"
 * is the daemon receiving the line, not a callback firing.
 */
@OptIn(ExperimentalTestApi::class)
class IconButtonsUiTest {
    private fun withSpawnedAgent(body: ComposeUiTest.(Controller) -> Unit) {
        val dir = Files.createTempDirectory("infantry-uxicon1-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "uxicon1", stdoutLines = listOf("""{"type":"step_start","sessionID":"uxicon1-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
        )
        try {
            daemon.server.start(wait = false)
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()
                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.isNotEmpty() }
                waitUntil(timeoutMillis = 15_000) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
                body(controller)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    private data class IconButtonNode(val tooltip: String, val description: String, val text: String)

    /** Every icon action on screen, read from semantics: its tooltip, its contentDescription and its visible text. */
    private fun ComposeUiTest.iconButtons(): List<IconButtonNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(IconActionTooltip)).fetchSemanticsNodes().map { it.iconButton() }

    private fun SemanticsNode.iconButton() = IconButtonNode(
        tooltip = config[IconActionTooltip],
        description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" "),
        text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text },
    )

    @Test fun `the Send icon carries the description Send and clicking it sends the prompt to the daemon`() = withSpawnedAgent { controller ->
        val id = controller.state.value.agents.keys.single()
        onNodeWithText("Enter to send", substring = true).performTextInput("ship the icons")
        waitForIdle()
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 15_000) {
            controller.state.value.agents[id]?.entries.orEmpty().any { it.kind == LineKind.USER && it.text == "ship the icons" }
        }
    }

    @Test fun `hovering Send shows the tooltip naming the action and its shortcut`() = withSpawnedAgent { _ ->
        // Let the spawn's turn come to rest first (Stop thinking goes disabled): while a session changes state the buttons flip
        // enabled, and M3's TooltipBox closes an open tooltip on that focus re-report.
        waitUntil(timeoutMillis = 15_000) { exists { onNodeWithContentDescription(STOP_TURN_LABEL).assertIsNotEnabled() } }
        waitForIdle()
        // The button as it sits after a spawn: an empty box, so Send is disabled. A disabled icon still names itself.
        onAllNodesWithText("Send (Enter)").assertCountEquals(0)
        // A pointer comes from somewhere: over the prompt box, then onto Send. (The test window's very first mouse event
        // landing straight on the button does not open it, which no real pointer does.)
        onNodeWithTag(PROMPT_BOX_TAG).performMouseInput { enter(center) }
        onNodeWithContentDescription("Send").performMouseInput { moveTo(center) }
        // The window's animations keep frames coming, so waitUntil never lets the tooltip's hover delay elapse; step the clock.
        repeat(10) { mainClock.advanceTimeBy(100) }
        waitForIdle()
        onAllNodesWithText("Send (Enter)").assertCountEquals(1)
    }

    @Test fun `every converted button is an icon with a non-empty tooltip and name, and composer tooltips carry the shortcut`() {
        withSpawnedAgent { _ ->
            val found = iconButtons()
            found.forEach { b ->
                assertTrue(b.tooltip.isNotBlank(), "empty tooltip: $b")
                assertTrue(b.description.isNotBlank() || b.text.isNotBlank(), "an icon button with no accessible name: $b")
            }
            val tooltips = found.map { it.tooltip }
            val composer = listOf(
                "Send (Enter)", "Switch agent (Ctrl+Shift+N)", "Resume (Ctrl+R)", "Stop background processes (Ctrl+Shift+K)", "End session (Ctrl+Alt+K)", "Stop thinking (current turn)",
            )
            composer.forEach { t -> assertTrue(t in tooltips, "no icon button with tooltip \"$t\" in $tooltips") }
            // The composer's buttons are icon-only: their name is the description, never a visible label.
            found.filter { it.tooltip in composer && it.description.isNotBlank() }.forEach { b ->
                assertTrue(b.text.isBlank(), "composer icon button shows text: $b")
                assertTrue(b.tooltip.startsWith(b.description), "tooltip does not name the action \"${b.description}\": $b")
            }
            // The sidebar's New agent: the same tooltip, with its label beside the icon.
            assertTrue(found.any { it.tooltip == "New agent (Ctrl+N)" && it.text == NEW_AGENT_LABEL }, "sidebar New agent missing: $found")
        }
        // The Changes panel's Refresh, in the same walk.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(400, 800) {
                setContent {
                    val state by controller.state.collectAsState()
                    ProjectChangesPanel(state, controller, 60_000)
                }
                link.push(Envelope(Envelope.READY, "contract=1.36.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope(Envelope.PROJECTS, Json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "Alpha", directory = "/srv/alpha")))))
                waitUntil(timeoutMillis = 10_000) { iconButtons().isNotEmpty() }
                assertTrue(iconButtons().all { it.tooltip.isNotBlank() && it.description.isNotBlank() }, "${iconButtons()}")
                assertTrue(iconButtons().any { it.tooltip == "Refresh" && it.description == "Refresh" }, "${iconButtons()}")
            }
        } finally {
            scope.cancel()
        }
    }
}
