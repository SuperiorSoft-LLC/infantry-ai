// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
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
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * EPIC-MOVE-1: tick two cards on the REAL board over the REAL daemon (with the built Infantry_Board daemon jar), Move to
 * epic..., type a new epic name, Move: the daemon creates the epic, files both tickets under it, leaves the third alone,
 * and the selection bar goes away. Screenshots of the selection and of the dialog's answer land in build/reports/epic-move-1.
 */
@OptIn(ExperimentalTestApi::class)
class EpicMoveUiTest {
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boardPluginsRoot(dir: File): File {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "plugins/modules.json").isFile }
        File(dir, "plugins").mkdirs()
        File(dir, "plugins/modules.json").writeText("""{"folders":[{"path":"official","tier":"official"}],"modules":{}}""")
        val m = "Infantry_Board"
        val src = File(repo, "plugins/official/$m")
        val dst = File(dir, "plugins/official/$m")
        File(src, "module.json").copyTo(File(dst, "module.json"), overwrite = true)
        File(src, "policies").takeIf { it.isDirectory }?.copyRecursively(File(dst, "policies"), overwrite = true)
        val jar = "daemon/build/libs/$m-daemon.jar"
        check(File(src, jar).isFile) { "$jar is not built (:daemon-plugin-official-$m:jar)" }
        File(src, jar).copyTo(File(dst, jar), overwrite = true)
        return File(dir, "plugins")
    }

    private fun boot(): InfantryDaemon {
        val dir = Files.createTempDirectory("infantry-ui-epic-move").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
            pluginsRoot = boardPluginsRoot(dir),
            pluginTrust = com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.Trust(emptyList(), devTrust = true, bundledRoot = null),
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return daemon
    }

    private fun port(daemon: InfantryDaemon): Int = runBlocking {
        repeat(250) {
            daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
            delay(20)
        }
        error("no port")
    }

    private fun until(what: String, c: Controller, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + daemonWaitMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    @Test fun `tick two cards, Move to epic with a new name, and both are filed under the new epic`() {
        val daemon = boot()
        val port = port(daemon)
        val c = Controller(scope)
        val out = File("build/reports/epic-move-1").apply { mkdirs() }
        runDesktopComposeUiTest(1600, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.connection.isLive }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Epic move") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Epic move" } }.projects.single { it.name == "Epic move" }
            runBlocking { c.selectProject(project.id) }
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.activeProjectId == project.id }
            for (title in listOf("ship the site", "post-launch stats", "post-launch themes")) {
                assertIs<CommandResult.Sent>(runBlocking { c.createTicket(title, "", null, "") })
            }
            val tickets = until("tickets listed", c) { s -> s.tickets.count { it.projectId == project.id } == 3 }.tickets
            fun id(title: String) = tickets.single { it.title == title }.id
            val (keep, a, b) = listOf(id("ship the site"), id("post-launch stats"), id("post-launch themes"))

            onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
            waitForIdle()
            onNodeWithTag(TICKET_SELECT_TAG_PREFIX + a).performClick()
            onNodeWithTag(TICKET_SELECT_TAG_PREFIX + b).performClick()
            waitForIdle()
            onNodeWithTag(BOARD_SELECTION_BAR_TAG).assertExists()
            ImageIO.write(onNodeWithTag(BOARD_MODAL_TAG).captureToImage().toAwtImage(), "png", File(out, "board-selected.png"))

            onNodeWithTag(BOARD_MOVE_EPIC_TAG).performClick()
            waitForIdle()
            onNodeWithTag(EPIC_MOVE_NEW_NAME_TAG).performTextInput("Post-launch")
            waitForIdle()
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(out, "move-dialog.png"))
            onNodeWithTag(EPIC_MOVE_CONFIRM_TAG).performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasTestTag(EPIC_MOVE_RESULT_TAG)).fetchSemanticsNodes().isNotEmpty() }

            val s = until("both tickets filed", c) { st ->
                val e = st.epics[project.id].orEmpty().firstOrNull { it.name == "Post-launch" }
                e != null && st.tickets.filter { it.id == a || it.id == b }.all { it.epicId == e.id }
            }
            val epic = s.epics.getValue(project.id).single { it.name == "Post-launch" }
            assertEquals(epic.id, s.tickets.single { it.id == a }.epicId)
            assertNull(s.tickets.single { it.id == keep }.epicId, "the unticked ticket stays without an epic")
            waitForIdle()
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(out, "move-result.png"))
            // The move cleared the selection: the bar is gone.
            onNodeWithTag(BOARD_SELECTION_BAR_TAG).assertDoesNotExist()
            println("EPIC-MOVE-1 screenshots: ${out.absolutePath}")
        }
    }
}
