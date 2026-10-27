package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Cursor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-9: both side panels resize from a drag edge that shows the horizontal resize cursor (not the hand) and a
 * small visible grip. The left sidebar widens when dragged RIGHT, the right panel when dragged LEFT; both clamp
 * to a min/max. Widths are not persisted (owner's call). Drives the REAL window ([App]) against a REAL in-process
 * daemon (StubProvider).
 *
 * Tags, the semantics key name and the bounds are spelled out (not the production constants) so this file is
 * the spec the UI must meet.
 */
@OptIn(ExperimentalTestApi::class)
class ResizableSidebarsUiTest {

    private val eastWestResize: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

    /** The handle declares the hover cursor it applies, under the semantics key "ResizeCursor". */
    private fun hasResizeCursor() = SemanticsMatcher("hover cursor is E/W resize") { node ->
        node.config.any { (k, v) -> k.name == "ResizeCursor" && v == eastWestResize }
    }

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-resize").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
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

    private fun ComposeUiTest.widthDp(tag: String): Float {
        waitForIdle()
        return with(density) { onNodeWithTag(tag).fetchSemanticsNode().size.width.toDp().value }
    }

    /** Press on the handle, move horizontally in steps (past the drag slop), release. */
    private fun ComposeUiTest.dragHandle(tag: String, dxDp: Float) {
        val dxPx = with(density) { dxDp * this.density }
        onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(dxPx / 10f, 0f)) }
            release()
        }
        waitForIdle()
    }

    @Test
    fun `both drag edges carry the E-W resize cursor and a visible grip`() {
        withApp {
            onNodeWithTag("sidebar-resize-handle").assertExists().assert(hasResizeCursor())
            onNodeWithTag("right-panel-resize-handle").assertExists().assert(hasResizeCursor())
            onNode(hasTestTag("resize-grip") and hasAnyAncestor(hasTestTag("sidebar-resize-handle"))).assertExists()
            onNode(hasTestTag("resize-grip") and hasAnyAncestor(hasTestTag("right-panel-resize-handle"))).assertExists()
            shoot("build/reports/m2-9/resizable-sidebars.png")
        }
    }

    @Test
    fun `dragging the left edge right widens the sidebar, left narrows it`() {
        withApp {
            val start = widthDp("left-sidebar")
            assertEquals(260f, start, 1f, "the sidebar starts at its default width")
            dragHandle("sidebar-resize-handle", 120f)
            val wider = widthDp("left-sidebar")
            // The drag slop is eaten before the first delta, so allow up to ~20dp less than the pointer travel.
            assertTrue(wider in (start + 90f)..(start + 121f), "dragged right by 120dp: $start -> $wider")
            dragHandle("sidebar-resize-handle", -60f)
            val narrower = widthDp("left-sidebar")
            assertTrue(narrower in (wider - 61f)..(wider - 30f), "dragged left by 60dp: $wider -> $narrower")
            shoot("build/reports/m2-9/left-sidebar-widened.png")
        }
    }

    @Test
    fun `the left sidebar clamps at its min and max`() {
        withApp {
            dragHandle("sidebar-resize-handle", 2000f)
            assertEquals(480f, widthDp("left-sidebar"), 1f, "max")
            dragHandle("sidebar-resize-handle", -2000f)
            assertEquals(200f, widthDp("left-sidebar"), 1f, "min")
        }
    }

    @Test
    fun `the right panel still resizes from its edge and clamps`() {
        withApp {
            val start = widthDp("right-panel")
            assertEquals(320f, start, 1f, "the right panel starts at its default width")
            dragHandle("right-panel-resize-handle", -120f)
            val wider = widthDp("right-panel")
            assertTrue(wider in (start + 90f)..(start + 121f), "dragged left by 120dp: $start -> $wider")
            dragHandle("right-panel-resize-handle", -2000f)
            assertEquals(640f, widthDp("right-panel"), 1f, "max")
            dragHandle("right-panel-resize-handle", 2000f)
            assertEquals(280f, widthDp("right-panel"), 1f, "min")
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
        println("M2-9 screenshot: ${file.absolutePath}")
    }
}
