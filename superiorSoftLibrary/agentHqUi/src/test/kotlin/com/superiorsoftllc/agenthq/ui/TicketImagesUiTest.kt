package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMG-4: the ticket edit form's Images section in the REAL window against the REAL daemon (Ktor, WebSocket, SQLite).
 * Attach a png with the Attach button (the platform host's file picker answers a temp png), Save, and the daemon's
 * board holds its relPath; close and reopen the card and the thumbnail renders from the project directory; clicking
 * it opens the full-size viewer; remove it, Save, and the board's images are empty (the daemon then releases the file).
 */
@OptIn(ExperimentalTestApi::class)
class TicketImagesUiTest {
    /**
     * FLAKE-4 (the FLAKE-1 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait (60 s,
     * AGENTHQ_TEST_WAIT_MS overrides it). Under concurrent build load the fixed 10-30 s waits timed out on a turn or a
     * save the daemon answered late. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    /** The desktop host, except its file picker answers [picked] instead of opening a native dialog. */
    private class PickingHost(private val picked: File) : PlatformHost {
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
        override val canPickFiles = true
        override fun pickFile(title: String): String = picked.absolutePath
        override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=AgentHQ UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
    }

    /** A plugins root under [dir] holding the built Infantry_Board (the board) and Infantry_Chat (image_upload) daemon jars. */
    private fun boardPluginsRoot(dir: File): File {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "plugins/modules.json").isFile }
        File(dir, "plugins").mkdirs()
        File(dir, "plugins/modules.json").writeText("""{"folders":[{"path":"official","tier":"official"}],"modules":{}}""")
        for (m in listOf("Infantry_Board", "Infantry_Chat")) {
            val src = File(repo, "plugins/official/$m")
            val dst = File(dir, "plugins/official/$m")
            File(src, "module.json").copyTo(File(dst, "module.json"), overwrite = true)
            File(src, "policies").takeIf { it.isDirectory }?.copyRecursively(File(dst, "policies"), overwrite = true)
            val jar = "daemon/build/libs/$m-daemon.jar"
            check(File(src, jar).isFile) { "$jar is not built (:daemon-plugin-official-$m:jar)" }
            File(src, jar).copyTo(File(dst, jar), overwrite = true)
        }
        return File(dir, "plugins")
    }

    private fun boot(): AgentHqDaemon {
        val dir = Files.createTempDirectory("agenthq-ui-img-daemon").toFile()
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
            pluginsRoot = boardPluginsRoot(dir),
            pluginTrust = com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost.Trust(emptyList(), devTrust = true, bundledRoot = null),
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return daemon
    }

    private fun port(daemon: AgentHqDaemon): Int = runBlocking {
        repeat(250) {
            daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
            delay(20)
        }
        error("no port")
    }

    private fun until(what: String, c: Controller, timeoutMs: Long = daemonWaitMs, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    @Test fun `attach a png and save, reopen shows its thumbnail, remove and save empties the ticket's images`() {
        val repo = Files.createTempDirectory("agenthq-ui-img-project").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        val png = File(Files.createTempDirectory("agenthq-ui-img-src").toFile(), "screenshot.png")
        closers += { png.parentFile.deleteRecursively() }
        ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB).apply { setRGB(1, 1, 0xFFFF0000.toInt()) }, "png", png)

        val daemon = boot()
        val port = port(daemon)
        val c = Controller(scope)
        runDesktopComposeUiTest(1600, 1000) {
            setContent {
                CompositionLocalProvider(LocalPlatformHost provides PickingHost(png)) { App(c, "ws://127.0.0.1:$port/ws", null) }
            }
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.connection.isLive }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Images project") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Images project" } }.projects.single { it.name == "Images project" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.activeProject?.directory == repo.absolutePath }
            assertIs<CommandResult.Sent>(runBlocking { c.createTicket("ticket with a screenshot", "", null, "") })
            val id = until("ticket listed", c) { s -> s.tickets.any { it.title == "ticket with a screenshot" } }
                .tickets.single { it.title == "ticket with a screenshot" }.id

            onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
            fun openCard() {
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasTestTag(TICKET_IMAGES_TAG)).fetchSemanticsNodes().isNotEmpty() }
            }
            fun save() {
                // The form is taller than the window once a thumbnail is in it: a pointer click can land off-screen, so fire the action.
                onNodeWithTag(TICKET_EDIT_SAVE_TAG).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                waitUntil(timeoutMillis = daemonWaitMs) {
                    onAllNodes(hasTestTag(TICKET_EDIT_SAVE_TAG)).fetchSemanticsNodes().isEmpty() || c.state.value.ticketError != null
                }
                assertNull(c.state.value.ticketError, "the daemon refused the save")
            }

            // 1. Attach (the button's file picker) and Save: the daemon's board holds the upload's relPath.
            openCard()
            onNodeWithTag(TICKET_IMAGE_ATTACH_TAG).performClick()
            waitUntil(timeoutMillis = daemonWaitMs) {
                onAllNodes(hasTestTag(TICKET_IMAGE_ERROR_TAG)).fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodes(hasTestTagPrefix(TICKET_IMAGE_THUMB_TAG_PREFIX)).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodes(hasTestTag(TICKET_IMAGE_ERROR_TAG)).fetchSemanticsNodes().firstOrNull()?.let { error("upload refused: ${it.config}") }
            save()
            waitUntil(timeoutMillis = daemonWaitMs) { daemon.boardPorts.ops()!!.get(id).images.isNotEmpty() }
            val relPath = daemon.boardPorts.ops()!!.get(id).images.single()
            assertTrue(relPath.startsWith(".agenthq/uploads/ticket/") && relPath.endsWith(".png"), "stored relPath: $relPath")
            val stored = File(repo, relPath)
            assertTrue(stored.isFile, "the upload is on disk: $stored")
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.tickets.single { it.id == id }.images == listOf(relPath) }

            // 3. Reopen: the thumbnail renders (decoded from the project directory), and opens full size.
            openCard()
            waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasTestTag(TICKET_IMAGE_LOADED_TAG_PREFIX + relPath)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(TICKET_IMAGE_LOADED_TAG_PREFIX + relPath).performClick()
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertExists()
            // IMG-VIEW-1: a click on the image toggles its size; a click beside it (the backdrop's corner) closes.
            onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performClick()
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertExists()
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).performTouchInput { click(Offset(4f, 4f)) }
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertDoesNotExist()

            // 2. Remove and Save: the board's images are empty.
            onNodeWithTag(TICKET_IMAGE_REMOVE_TAG_PREFIX + relPath).performClick()
            onNodeWithTag(TICKET_IMAGE_THUMB_TAG_PREFIX + relPath).assertDoesNotExist()
            save()
            waitUntil(timeoutMillis = daemonWaitMs) { daemon.boardPorts.ops()!!.get(id).images.isEmpty() }
            assertEquals(emptyList(), daemon.boardPorts.ops()!!.get(id).images)
            // The UI's remove only drops the relPath from the list it saves; it never deletes a file. The DAEMON does:
            // IMG-3's TicketBoard releases an image no remaining ticket references (TicketImages.release). The IMG-4
            // brief asked for the file to stay on disk, which contradicts that; raised with the PM, not changed here.
            assertTrue(!stored.isFile, "IMG-3 releases an unreferenced image on save: $stored")
        }
    }

    private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("testTag starts with $prefix") { node ->
        androidx.compose.ui.semantics.SemanticsProperties.TestTag in node.config && node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix)
    }
}
