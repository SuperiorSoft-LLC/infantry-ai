// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * M2-8: the Board is no longer a centre tab. A compact "Board" button at the top of the left sidebar opens the
 * existing board in a near-full-screen modal (header with title + close, then the board itself); the close
 * button or Esc dismisses it. Drives the REAL window ([App]) against a REAL in-process daemon (StubProvider).
 *
 * Tags are spelled out (not the production constants) so this file is the spec the UI must meet.
 */
@OptIn(ExperimentalTestApi::class)
class BoardModalUiTest {

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("infantry-ui-board-modal").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun withApp(body: ComposeUiTest.(Controller) -> Unit) {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1600, 900) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                body(controller)
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    @Test
    fun `the centre tab row has no Board tab`() {
        withApp {
            onNodeWithTag("center-tab-OUTPUT").assertExists()
            onNodeWithTag("center-tab-BOARD").assertDoesNotExist()
            onAllNodes(hasText("Board", substring = true) and hasAnyAncestor(hasTestTag("center-tab-bar"))).assertCountEquals(0)
        }
    }

    @Test
    fun `the sidebar button opens the board in a modal, and the close button closes it`() {
        withApp {
            onNodeWithTag("board-modal").assertDoesNotExist()
            onNodeWithTag("sidebar-board-button").assertExists()
            shoot("build/reports/m2-8/sidebar-board-button.png")

            onNodeWithTag("sidebar-board-button").performClick()
            waitForIdle()
            onNodeWithTag("board-modal").assertExists()
            // The existing board, inside the modal: its toolbar and its columns.
            onNode(hasText("+ New ticket") and hasAnyAncestor(hasTestTag("board-modal"))).assertExists()
            onNode(hasText("TO DO") and hasAnyAncestor(hasTestTag("board-modal"))).assertExists()
            onNode(hasText("IN PROGRESS") and hasAnyAncestor(hasTestTag("board-modal"))).assertExists()
            onNodeWithTag("board-modal-close").assertExists()
            shoot("build/reports/m2-8/board-modal-open.png")

            onNodeWithTag("board-modal-close").performClick()
            waitForIdle()
            onNodeWithTag("board-modal").assertDoesNotExist()
            onNodeWithTag("sidebar-board-button").assertExists()
        }
    }

    @Test
    fun `Esc closes the board modal`() {
        withApp {
            onNodeWithTag("sidebar-board-button").performClick()
            waitForIdle()
            onNodeWithTag("board-modal").assertExists()
            onNodeWithTag("board-modal").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag("board-modal").assertDoesNotExist()
        }
    }

    /** Every root (window content plus each popup/dialog layer) painted onto one image. */
    private fun ComposeUiTest.shoot(path: String) {
        waitForIdle()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val base = roots.first()
        val out = BufferedImage(base.size.width, base.size.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        roots.indices.forEach { i ->
            val img = onAllNodes(isRoot())[i].captureToImage().toAwtImage()
            val pos = roots[i].boundsInWindow
            g.drawImage(img, pos.left.toInt(), pos.top.toInt(), null)
        }
        g.dispose()
        val file = File(path).apply { parentFile.mkdirs() }
        ImageIO.write(out, "png", file)
        println("M2-8 screenshot: ${file.absolutePath}")
    }
}
