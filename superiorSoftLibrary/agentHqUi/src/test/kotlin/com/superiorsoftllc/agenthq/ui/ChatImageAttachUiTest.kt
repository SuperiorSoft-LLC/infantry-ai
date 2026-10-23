package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IMG-2: images in the agent chat composer, in the REAL window against the REAL daemon (Ktor, WebSocket, SQLite).
 * The daemon's provider is the stub wrapped so every PROMPT it is asked to run is recorded: that is what the agent
 * actually receives. The platform host stands in only for the native file dialog and the system clipboard.
 */
@OptIn(ExperimentalTestApi::class)
class ChatImageAttachUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    /** The file picker answers [picks] in order; the clipboard holds [clipboard]. */
    private class FakeHost(private val picks: ArrayDeque<File>, var clipboard: PickedImage? = null) : PlatformHost {
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
        override val canPickFiles = true
        override fun pickFile(title: String): String? = picks.removeFirstOrNull()?.absolutePath
        override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
        override fun fileExists(path: String): Boolean? = File(path).isFile
        override fun clipboardImage(): PickedImage? = clipboard
    }

    /** The stub provider, recording the prompt of every run the daemon starts. */
    private class RecordingProvider(private val inner: StubProvider, val prompts: MutableList<String>) : AgentProvider by inner {
        override fun spec(request: RunRequest): ProcessSpec { prompts += request.prompt; return inner.spec(request) }
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=AgentHQ UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun boot(prompts: MutableList<String>): AgentHqDaemon {
        val dir = Files.createTempDirectory("agenthq-ui-chatimg-daemon").toFile()
        val stub = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            port = 0, host = "127.0.0.1", provider = RecordingProvider(stub, prompts), runner = stub.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
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

    private fun until(what: String, c: Controller, timeoutMs: Long = 20_000, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    private fun pngBytes(rgb: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB).apply { setRGB(1, 1, rgb) }, "png", it)
    }.toByteArray()

    private fun tempFile(name: String, bytes: ByteArray): File =
        File(Files.createTempDirectory("agenthq-ui-chatimg-src").toFile().also { d -> closers += { d.deleteRecursively() } }, name).apply { writeBytes(bytes) }

    /** A daemon, a git project with a directory, selected, and the App on [host]; then [body] with the controller. */
    private fun withApp(host: FakeHost, prompts: MutableList<String>, body: ComposeUiTest.(Controller) -> Unit) {
        val repo = Files.createTempDirectory("agenthq-ui-chatimg-project").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        val daemon = boot(prompts)
        val port = port(daemon)
        val c = Controller(scope)
        runDesktopComposeUiTest(1600, 1000) {
            setContent { CompositionLocalProvider(LocalPlatformHost provides host) { App(c, "ws://127.0.0.1:$port/ws", null) } }
            waitUntil(timeoutMillis = 20_000) { c.state.value.connection.isLive }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Chat images") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Chat images" } }.projects.single { it.name == "Chat images" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.directory == repo.absolutePath }
            body(c)
        }
    }

    private fun ComposeUiTest.chipPaths(): List<String> = onAllNodes(hasTestTagPrefix(CHAT_IMAGE_CHIP_TAG_PREFIX)).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix(CHAT_IMAGE_CHIP_TAG_PREFIX) }

    private fun ComposeUiTest.attachByButton(expectChips: Int) {
        onNodeWithTag(CHAT_IMAGE_ATTACH_TAG).performClick()
        waitUntil(timeoutMillis = 15_000) {
            onAllNodes(hasTestTag(CHAT_IMAGE_ERROR_TAG)).fetchSemanticsNodes().isNotEmpty() || chipPaths().size == expectChips
        }
    }

    private fun imageLine(path: String) = "[image: $path]"

    @Test fun `attach a png with the paperclip, send, and the PROMPT carries its path and the transcript its thumbnail`() {
        val prompts = CopyOnWriteArrayList<String>()
        val png = tempFile("shot.png", pngBytes(0xFFFF0000.toInt()))
        withApp(FakeHost(ArrayDeque(listOf(png))), prompts) { c ->
            attachByButton(expectChips = 1)
            onAllNodes(hasTestTag(CHAT_IMAGE_ERROR_TAG)).fetchSemanticsNodes().firstOrNull()?.let { error("upload refused: ${it.config}") }
            val path = chipPaths().single()
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("what is in this picture")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) { prompts.isNotEmpty() }
            val prompt = prompts.first()
            assertTrue(prompt.contains("what is in this picture"), "the typed text reached the agent: $prompt")
            assertTrue(prompt.contains(imageLine(path)), "the PROMPT names the image: $prompt")
            val stored = File(path)
            assertTrue(stored.isAbsolute && stored.isFile, "the image line names a file that exists: $stored")
            assertTrue(stored.invariantSeparatorsPath.contains("/.agenthq/uploads/chat/"), "stored as a chat upload: $stored")
            // Sent: the chip is gone, and the sent message in the transcript shows the image.
            waitUntil(timeoutMillis = 5_000) { chipPaths().isEmpty() }
            waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(CHAT_IMAGE_SENT_TAG_PREFIX + path)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(c.state.value.connection.isLive)
        }
    }

    /** IMG-VIEW-1: a chat image opens full size on a click (with its file name), and Esc closes it. */
    @Test fun `clicking a chat image opens it full size and Esc closes it`() {
        val prompts = CopyOnWriteArrayList<String>()
        val png = tempFile("full.png", pngBytes(0xFF00FF00.toInt()))
        withApp(FakeHost(ArrayDeque()), prompts) { _ ->
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("see this\n${imageLine(png.absolutePath)}")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodes(hasTestTag(CHAT_IMAGE_SENT_TAG_PREFIX + png.absolutePath)).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasContentDescription("Image full.png")).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertDoesNotExist()
            onNodeWithTag(CHAT_IMAGE_SENT_TAG_PREFIX + png.absolutePath).performClick()
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertExists()
            onNodeWithTag(IMAGE_VIEWER_NAME_TAG, useUnmergedTree = true).assertTextEquals("full.png")
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertDoesNotExist()
            // The close button closes it too.
            onNodeWithTag(CHAT_IMAGE_SENT_TAG_PREFIX + png.absolutePath).performClick()
            onNodeWithTag(IMAGE_VIEWER_CLOSE_TAG).performClick()
            onNodeWithTag(TICKET_IMAGE_VIEWER_TAG).assertDoesNotExist()
        }
    }

    /** IMG-5: a turn whose image the retention sweep deleted keeps its text and shows "image expired", not a blank thumb. */
    @Test fun `a sent image line naming a swept file shows image expired`() {
        val prompts = CopyOnWriteArrayList<String>()
        val swept = File(Files.createTempDirectory("agenthq-ui-chatimg-swept").toFile().also { d -> closers += { d.deleteRecursively() } },
            ".agenthq/uploads/chat/0000.png").absolutePath
        withApp(FakeHost(ArrayDeque()), prompts) { _ ->
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("look at this\n${imageLine(swept)}")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) { prompts.isNotEmpty() }
            assertTrue(prompts.first().contains("look at this"), "the text still travels: ${prompts.first()}")
            waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(CHAT_IMAGE_EXPIRED_TAG_PREFIX + swept)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodes(hasTestTag(CHAT_IMAGE_SENT_TAG_PREFIX + swept)).fetchSemanticsNodes().isEmpty(), "no blank thumbnail for a swept file")
        }
    }

    @Test fun `a removed chip and a refused file send no image line, and a pasted image becomes a chip`() {
        val prompts = CopyOnWriteArrayList<String>()
        val first = tempFile("first.png", pngBytes(0xFF00FF00.toInt()))
        val notAnImage = tempFile("fake.png", "this is text, not a png".toByteArray())
        val host = FakeHost(ArrayDeque(listOf(first, notAnImage)), clipboard = PickedImage("pasted.png", "image/png", pngBytes(0xFF0000FF.toInt())))
        withApp(host, prompts) { _ ->
            // A png, then removed before sending.
            attachByButton(expectChips = 1)
            val removed = chipPaths().single()
            onNodeWithTag(CHAT_IMAGE_REMOVE_TAG_PREFIX + removed).performClick()
            waitUntil(timeoutMillis = 5_000) { chipPaths().isEmpty() }

            // 3. A refused file: the daemon's error shows inline, nothing is attached.
            attachByButton(expectChips = 1)
            onNodeWithTag(CHAT_IMAGE_ERROR_TAG).assertExists()
            assertEquals(emptyList(), chipPaths(), "a refused upload attaches nothing")

            // 4. Ctrl+V with an image on the (platform host's) clipboard: a chip.
            onNodeWithTag(PROMPT_BOX_TAG).performClick()
            onNodeWithTag(PROMPT_BOX_TAG).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            waitUntil(timeoutMillis = 15_000) { chipPaths().size == 1 }
            val pasted = chipPaths().single()
            assertTrue(pasted != removed, "the pasted image is its own upload")

            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("only the pasted one")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) { prompts.isNotEmpty() }
            val prompt = prompts.first()
            assertTrue(prompt.contains(imageLine(pasted)), "the pasted image is in the PROMPT: $prompt")
            assertFalse(prompt.contains(imageLine(removed)), "2. the removed chip's line is not in the PROMPT: $prompt")
            assertEquals(1, Regex("""\[image: """).findAll(prompt).count(), "3. no line for the refused file: $prompt")
        }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") { node ->
        SemanticsProperties.TestTag in node.config && node.config[SemanticsProperties.TestTag].startsWith(prefix)
    }
}
