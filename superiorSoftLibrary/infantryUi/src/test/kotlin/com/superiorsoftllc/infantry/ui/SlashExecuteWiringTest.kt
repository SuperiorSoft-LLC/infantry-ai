// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CP-4(d): in the REAL window against a REAL in-process daemon, sending a `/model` or `/effort` line from an agent's box
 * runs the command on that session, shows the outcome in the notice strip, and never shows the line as something the
 * user said to the agent. An unknown command shows the daemon's refusal listing the known ones.
 */
@OptIn(ExperimentalTestApi::class)
class SlashExecuteWiringTest {
    private fun withSpawnedAgent(body: ComposeUiTest.(Controller, InfantryDaemon) -> Unit) {
        val dir = Files.createTempDirectory("infantry-cp4d-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "cp4d", stdoutLines = listOf("""{"type":"step_start","sessionID":"cp4d-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
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
                dismissSetupWizard(controller)
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()
                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.isNotEmpty() }
                waitUntil(timeoutMillis = 15_000) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
                body(controller, daemon)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    /** Types [text] into the agent's box and presses Send; the box is empty (and says "Enter to send") again afterwards. */
    private fun ComposeUiTest.sendLine(text: String) {
        onNodeWithText("Enter to send", substring = true).performTextInput(text)
        waitForIdle()
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
    }

    private fun said(controller: Controller, id: String): List<String> =
        controller.state.value.agents.getValue(id).entries.filter { it.kind == LineKind.USER }.map { it.text }

    @Test
    fun `sending slash effort and slash model runs them on the session and shows the outcome without a chat line`() = withSpawnedAgent { controller, daemon ->
        val id = controller.state.value.agents.keys.single()

        sendLine("/effort high")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.any { "effort set to high" in it.text } }
        // VISIBLE: the notice strip shows the daemon's sentence.
        waitUntil(timeoutMillis = 5_000) { exists { onNodeWithTag(NOTICE_TAG).assertExists() } }
        onNodeWithText("effort set to high", substring = true).assertExists()
        assertTrue(controller.state.value.notices.last().info)
        assertEquals("high", daemon.manager.get(id)?.effort, "the daemon's session carries the effort")
        waitUntil(timeoutMillis = 5_000) { controller.state.value.agents[id]?.session?.effort == "high" }

        sendLine("/model stub-fast")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.agents[id]?.session?.model == "stub-fast" }
        assertEquals("stub-fast", daemon.manager.get(id)?.model)

        assertEquals(listOf("hello"), said(controller, id), "a command is not something the user said to the agent")
    }

    @Test
    fun `an unknown slash command shows the known list and changes nothing`() = withSpawnedAgent { controller, daemon ->
        val id = controller.state.value.agents.keys.single()
        sendLine("/frobnicate now")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.any { "frobnicate" in it.text } }
        val notice = assertNotNull(controller.state.value.notices.lastOrNull { "frobnicate" in it.text })
        assertTrue(listOf("/model", "/effort", "/compact").all { it in notice.text }, notice.text)
        assertTrue(!notice.info)
        assertNull(daemon.manager.get(id)?.effort)
        assertEquals(listOf("hello"), said(controller, id))
    }
}
