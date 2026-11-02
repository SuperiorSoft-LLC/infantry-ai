package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-7: projects live in the title bar. A drop-down shows the current project and switches to another; a square
 * "+" box beside it opens the New Project modal (name, Create, Cancel), whose Create makes the project AND selects
 * it. The left sidebar no longer carries the projects list or the new-project field. Drives the REAL window
 * ([App]) against a REAL in-process daemon (StubProvider): the create/switch actions are the existing ones.
 *
 * Tags are spelled out (not the production constants) so this file is the spec the UI must meet.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectPickerUiTest {

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-projects").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
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

    /** One App window on a fresh daemon, live before [body] runs. */
    private fun withApp(body: ComposeUiTest.(Controller) -> Unit) {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1400, 900) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                body(controller)
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    /** Through the title bar's "+" box and the modal, exactly like a person. */
    private fun ComposeUiTest.createProject(controller: Controller, name: String) {
        onNodeWithTag("title-bar-new-project").performClick()
        onNodeWithTag("new-project-name").performTextInput(name)
        onNodeWithTag("new-project-create").performClick()
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == name } }
    }

    private fun Controller.idOf(name: String) = state.value.projects.first { it.name == name }.id

    @Test
    fun `the left sidebar no longer carries the projects list or the new-project field`() {
        withApp { controller ->
            fun assertNoProjectsBlock() {
                waitForIdle()
                assertEquals(0, onAllNodesWithText("PROJECTS").fetchSemanticsNodes().size, "no PROJECTS block in the sidebar")
                assertEquals(0, onAllNodesWithText("Create project").fetchSemanticsNodes().size, "no Create project button outside the modal")
                assertEquals(0, onAllNodesWithText("New project").fetchSemanticsNodes().size, "no New project field outside the modal")
            }
            assertNoProjectsBlock()
            createProject(controller, "Alpha")
            assertNoProjectsBlock()
        }
    }

    @Test
    fun `the plus box opens the New Project modal, an empty name keeps Create off, Create adds and selects, Cancel adds nothing`() {
        withApp { controller ->
            onNodeWithTag("new-project-dialog").assertDoesNotExist()
            onNodeWithTag("title-bar-new-project").performClick()
            onNodeWithTag("new-project-dialog").assertExists()
            onNodeWithTag("new-project-create").assertIsNotEnabled()
            onNodeWithTag("new-project-name").performTextInput("Alpha")
            onNodeWithTag("new-project-create").assertIsEnabled()
            onNodeWithTag("new-project-create").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == "Alpha" } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("new-project-dialog").fetchSemanticsNodes().isEmpty() }

            // A second project is selected as soon as the daemon has made it (not left on the first one).
            createProject(controller, "Beta")
            val beta = controller.idOf("Beta")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == beta }
            onNodeWithTag("title-bar-project-picker").assertTextContains("Beta", substring = true)

            // Cancel closes the modal and sends nothing.
            onNodeWithTag("title-bar-new-project").performClick()
            onNodeWithTag("new-project-name").performTextInput("Gamma")
            onNodeWithTag("new-project-cancel").performClick()
            waitForIdle()
            onNodeWithTag("new-project-dialog").assertDoesNotExist()
            Thread.sleep(300)
            assertTrue(controller.state.value.projects.none { it.name == "Gamma" }, "Cancel creates nothing")
        }
    }

    @Test
    fun `the drop-down lists every project and picking one switches project`() {
        withApp { controller ->
            createProject(controller, "Alpha")
            createProject(controller, "Beta")
            val alpha = controller.idOf("Alpha")
            val beta = controller.idOf("Beta")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == beta }

            onNodeWithTag("title-bar-project-picker").performClick()
            onNodeWithTag("project-option-$alpha").assertExists()
            onNodeWithTag("project-option-$beta").assertExists()
            shoot("build/reports/m2-7/project-dropdown-open.png")

            onNodeWithTag("project-option-$alpha").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == alpha }
            onNodeWithTag("project-option-$beta").assertDoesNotExist()
            onNodeWithTag("title-bar-project-picker").assertTextContains("Alpha", substring = true)

            onNodeWithTag("title-bar-new-project").performClick()
            onNodeWithTag("new-project-name").performTextInput("Gamma")
            shoot("build/reports/m2-7/new-project-modal-open.png")
            onNodeWithTag("new-project-cancel").performClick()
        }
    }

    @Test
    fun `a refused create keeps the form open, shows the reason and creates no project`() {
        withApp { controller ->
            onNodeWithTag("title-bar-new-project").performClick()
            onNodeWithTag("new-project-name").performTextInput("QA Stale")
            onNodeWithTag("new-project-directory").performTextInput("relative/qa-path")
            onNodeWithTag("new-project-create").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("directory must be an absolute path", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("new-project-dialog").assertExists()
            Thread.sleep(300)
            assertTrue(controller.state.value.projects.none { it.name == "QA Stale" }, "a refused save creates nothing")

            // The same form, fixed, creates the project WITH its directory.
            val abs = Files.createTempDirectory("agenthq-ui-dir").toFile()
            try {
                onNodeWithTag("new-project-directory").performTextReplacement(abs.absolutePath)
                onNodeWithTag("new-project-create").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == "QA Stale" } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("new-project-dialog").fetchSemanticsNodes().isEmpty() }
                assertEquals(1, controller.state.value.projects.count { it.name == "QA Stale" })
                assertTrue(controller.state.value.projects.first { it.name == "QA Stale" }.directory.isNotBlank())
            } finally { abs.deleteRecursively() }
        }
    }

    @Test
    fun `General settings edits the directory, refuses a relative one and clears it`() {
        withApp { controller ->
            createProject(controller, "Alpha")
            val id = controller.idOf("Alpha")
            val abs = Files.createTempDirectory("agenthq-ui-dir").toFile()
            try {
                onNodeWithTag("title-bar-edit-project").performClick()
                onNodeWithTag("project-directory-field").performTextInput("relative/x")
                onNodeWithTag("project-directory-save").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("directory must be an absolute path", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("", controller.state.value.projects.first { it.id == id }.directory)

                onNodeWithTag("project-directory-field").performTextReplacement(abs.absolutePath)
                onNodeWithTag("project-directory-save").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.first { it.id == id }.directory.isNotBlank() }

                onNodeWithTag("project-directory-clear").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.first { it.id == id }.directory.isBlank() }
            } finally { abs.deleteRecursively() }
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
        println("M2-7 screenshot: ${file.absolutePath}")
    }

    @Test
    fun `the pencil opens the dialog prefilled and Save renames the project under the same id`() {
        withApp { controller ->
            createProject(controller, "Alpha")
            val id = controller.idOf("Alpha")
            onNodeWithTag("title-bar-edit-project").performClick()
            onNodeWithTag("project-config-dialog").assertExists()
            onNodeWithTag("new-project-name").assertTextContains("Alpha", substring = true)
            onNodeWithTag("new-project-name").performTextReplacement("Renamed")
            onNodeWithTag("new-project-create").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.id == id && it.name == "Renamed" } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("project-config-dialog").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, controller.state.value.projects.size, "a rename adds no project")
            onNodeWithTag("title-bar-project-picker").assertTextContains("Renamed", substring = true)
        }
    }

    @Test
    fun `the Lanes tab raises a role's cap and the daemon stores it`() {
        withApp { controller ->
            createProject(controller, "Alpha")
            val id = controller.idOf("Alpha")
            onNodeWithTag("title-bar-edit-project").performClick()
            onNodeWithTag("project-config-tab-lanes").performClick()
            onNodeWithTag("project-config-cap-backend").assertTextContains("1")
            onNodeWithTag("project-config-cap-plus-backend").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.id == id && it.laneCaps["backend"] == 2 } }
            onNodeWithTag("project-config-cap-backend").assertTextContains("2")
        }
    }
}
