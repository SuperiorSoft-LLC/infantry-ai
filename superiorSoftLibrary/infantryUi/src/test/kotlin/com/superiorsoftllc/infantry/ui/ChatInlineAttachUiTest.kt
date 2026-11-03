// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.shell.PickedImage
import com.superiorsoftllc.infantry.ui.shell.PlatformHost
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
 * ATTACH-INLINE-1: attachments are inline tags in the composer's text, in the REAL window against the REAL daemon.
 * The provider records every PROMPT the daemon runs; the host fakes only the native file dialog and the clipboard.
 */
@OptIn(ExperimentalTestApi::class)
class ChatInlineAttachUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

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

    private class RecordingProvider(private val inner: StubProvider, val prompts: MutableList<String>) : AgentProvider by inner {
        override fun spec(request: RunRequest): ProcessSpec { prompts += request.prompt; return inner.spec(request) }
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Infantry UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun boot(prompts: MutableList<String>): InfantryDaemon {
        val dir = Files.createTempDirectory("infantry-ui-inline-daemon").toFile()
        val stub = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = RecordingProvider(stub, prompts), runner = stub.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
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
        File(Files.createTempDirectory("infantry-ui-inline-src").toFile().also { d -> closers += { d.deleteRecursively() } }, name).apply { writeBytes(bytes) }

    private fun withApp(host: FakeHost, prompts: MutableList<String>, body: ComposeUiTest.(Controller) -> Unit) {
        val repo = Files.createTempDirectory("infantry-ui-inline-project").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        val daemon = boot(prompts)
        val port = port(daemon)
        val c = Controller(scope)
        runDesktopComposeUiTest(1600, 1000) {
            setContent { CompositionLocalProvider(LocalPlatformHost provides host) { App(c, "ws://127.0.0.1:$port/ws", null) } }
            waitUntil(timeoutMillis = 20_000) { c.state.value.connection.isLive }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Inline attach") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Inline attach" } }.projects.single { it.name == "Inline attach" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.directory == repo.absolutePath }
            body(c)
        }
    }

    /** What the composer shows: its text after the visual transformation, so a tag reads as its pill. */
    private fun ComposeUiTest.shown(): String =
        onNodeWithTag(PROMPT_BOX_TAG).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun ComposeUiTest.waitPill(name: String) = waitUntil(timeoutMillis = 15_000) {
        onAllNodes(hasTestTag(CHAT_IMAGE_ERROR_TAG)).fetchSemanticsNodes().firstOrNull()?.let { error("upload refused: ${it.config}") }
        shown().contains("$ATTACH_PILL_MARK $name") && onAllNodes(hasTestTag(CHAT_ATTACH_BUSY_TAG)).fetchSemanticsNodes().isEmpty()
    }

    @Test fun `type, attach a png with the paperclip, type more, send - the reference sits between the two texts`() {
        val prompts = CopyOnWriteArrayList<String>()
        val png = tempFile("shot.png", pngBytes(0xFFFF0000.toInt()))
        withApp(FakeHost(ArrayDeque(listOf(png))), prompts) { _ ->
            onNodeWithContentDescription(ATTACH_FILE_LABEL).assertExists()
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("before ")
            onNodeWithTag(CHAT_IMAGE_ATTACH_TAG).performClick()
            waitPill("shot.png")
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput(" after")
            assertTrue(shown().startsWith("before $ATTACH_PILL_MARK shot.png"), "the pill sits where the caret was: ${shown()}")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) { prompts.isNotEmpty() }
            val prompt = prompts.first()
            // The daemon may put its own preamble first: the user's message is the last line.
            val m = Regex("""^before \[image: ([^\]]+)] after$""").find(prompt.trim().lines().last()) ?: error("the reference is not between the texts: $prompt")
            val stored = File(m.groupValues[1])
            assertTrue(stored.isAbsolute && stored.isFile, "names a stored file: $stored")
            assertTrue(stored.invariantSeparatorsPath.contains("/.infantry/uploads/chat/"), "stored as a chat image: $stored")
            waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(CHAT_IMAGE_SENT_TAG_PREFIX + stored.path)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun `a non-image file is sent as a file reference, Backspace drops a tag, and an oversize file is refused inline`() {
        val prompts = CopyOnWriteArrayList<String>()
        val dropped = tempFile("dropped.txt", "first notes".toByteArray())
        val kept = tempFile("notes.csv", "a,b\n1,2\n".toByteArray())
        val huge = tempFile("huge.bin", ByteArray(CHAT_FILE_MAX_BYTES + 1))
        withApp(FakeHost(ArrayDeque(listOf(dropped, huge, kept))), prompts) { _ ->
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput("x ")
            onNodeWithTag(CHAT_IMAGE_ATTACH_TAG).performClick()
            waitPill("dropped.txt")
            // Backspace right after the tag removes it, all of it, in one key.
            onNodeWithTag(PROMPT_BOX_TAG).performKeyInput { pressKey(Key.Backspace) }
            waitUntil(timeoutMillis = 5_000) { !shown().contains(ATTACH_PILL_MARK) }
            assertEquals("x ", shown())

            // Over the limit: an inline refusal and no tag.
            onNodeWithTag(CHAT_IMAGE_ATTACH_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(CHAT_IMAGE_ERROR_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(shown().contains(ATTACH_PILL_MARK), "a refused file inserts no tag: ${shown()}")

            // Ctrl+Shift+A is the paperclip.
            onNodeWithTag(PROMPT_BOX_TAG).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.A) } } }
            waitUntil(timeoutMillis = 15_000) { shown().contains("$ATTACH_PILL_MARK notes.csv") && onAllNodes(hasTestTag(CHAT_ATTACH_BUSY_TAG)).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag(PROMPT_BOX_TAG).performTextInput(" y")
            onNodeWithContentDescription("Spawn").performClick()
            waitUntil(timeoutMillis = 15_000) { prompts.isNotEmpty() }
            val prompt = prompts.first()
            val m = Regex("""^x \[file: ([^\]]+)] y$""").find(prompt.trim().lines().last()) ?: error("one file reference between the texts: $prompt")
            val stored = File(m.groupValues[1])
            assertTrue(stored.isAbsolute && stored.isFile, "names a stored file: $stored")
            assertTrue(stored.invariantSeparatorsPath.contains("/.infantry/uploads/file/"), "stored as a chat file: $stored")
            assertEquals("a,b\n1,2\n", stored.readText(), "the kept file's bytes, not the dropped one's")
            assertFalse(prompt.contains("[image: "), "no image line: $prompt")
        }
    }
}
