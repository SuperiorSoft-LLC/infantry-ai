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
import kotlin.test.assertTrue

/**
 * CP-4(e): in the REAL window against a REAL in-process daemon, a `/` line in the New agent box is never a task: an
 * unknown command is refused with the known list, `/compact` is refused (no session yet), `/effort` and `/model` set
 * the next spawn's selection, and none of them creates a session.
 */
@OptIn(ExperimentalTestApi::class)
class SpawnBoxSlashWiringTest {
    private fun withSpawnBox(body: ComposeUiTest.(Controller, InfantryDaemon) -> Unit) {
        val dir = Files.createTempDirectory("infantry-cp4e-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "cp4e", stdoutLines = listOf("""{"type":"step_start","sessionID":"cp4e-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
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

    private fun ComposeUiTest.spawnLine(text: String) {
        onNodeWithText("Task for a NEW agent", substring = true).performTextInput(text)
        waitForIdle()
        onNodeWithContentDescription("Spawn").performClick()
    }

    @Test
    fun `an unknown slash command in the spawn box shows the known list and spawns nothing`() = withSpawnBox { controller, daemon ->
        spawnLine("/frobnicate")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.any { "frobnicate" in it.text } }
        val notice = assertNotNull(controller.state.value.notices.lastOrNull { "frobnicate" in it.text })
        assertTrue(listOf("/model", "/effort", "/compact").all { it in notice.text }, notice.text)
        waitUntil(timeoutMillis = 5_000) { exists { onNodeWithTag(NOTICE_TAG).assertExists() } }
        Thread.sleep(500)
        assertEquals(emptyList(), daemon.manager.list(), "a command never creates a session")
        assertTrue(controller.state.value.agents.isEmpty())
        assertEquals(null, controller.state.value.pendingSpawn, "no `new` frame was sent: nothing is waiting to spawn")
    }

    @Test
    fun `compact is refused in the spawn box because there is no session yet`() = withSpawnBox { controller, daemon ->
        spawnLine("/compact")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.any { "/compact" in it.text && "not spawned" in it.text } }
        Thread.sleep(500)
        assertEquals(emptyList(), daemon.manager.list())
        assertEquals(null, controller.state.value.pendingSpawn)
    }

    @Test
    fun `slash effort and slash model in the spawn box set the next spawn and the session starts with them`() = withSpawnBox { controller, daemon ->
        spawnLine("/effort high")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.pickedEffort == "high" }
        waitUntil(timeoutMillis = 5_000) { exists { onNodeWithText("the next agent will start with effort high", substring = true).assertExists() } }
        spawnLine("/model stub-fast")
        waitUntil(timeoutMillis = 10_000) { controller.state.value.pickedModel?.model == "stub-fast" }
        assertEquals(emptyList(), daemon.manager.list(), "setting a selection spawns nothing")
        assertTrue(controller.state.value.agents.isEmpty())

        spawnLine("hello")
        waitUntil(timeoutMillis = 15_000) { daemon.manager.list().isNotEmpty() }
        val session = daemon.manager.list().single()
        assertEquals("high", session.effort, "the spawn started with the chosen effort")
        assertEquals("stub-fast", session.model, "the spawn started with the chosen model")
    }
}
