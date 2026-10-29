package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.approvals.*
import com.superiorsoftllc.agenthq.plugins.official.assistant.*
import com.superiorsoftllc.agenthq.plugins.official.changes.*
import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.changesWorktreeStatus
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * DIFF-MODAL in the REAL window: the whole [App] against the REAL daemon over a REAL temp git repository with one
 * modified file. Clicking its row in the Changes panel opens the diff modal; the added line is tagged diff-line-add,
 * the removed one diff-line-del, the header counts +1 −1, and Esc closes it.
 */
@OptIn(ExperimentalTestApi::class)
class DiffModalRealDaemonUiTest {
    /**
     * FLAKE-7 (the FLAKE-4/FLAKE-6 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Under suite load the fixed 15-20 s waits timed out on a daemon that
     * answered late (the project_git_diff answer, a git subprocess on the daemon, came after the 15 s wait). A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=AgentHQ UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun newRepo(): File {
        val repo = Files.createTempDirectory("agenthq-ui-git").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("hello\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "add the readme")
        return repo
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("agenthq-ui-git-daemon").toFile()
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
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
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

    private fun until(what: String, c: Controller, timeoutMs: Long = daemonWaitMs, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    @Test fun `clicking a changed file opens its diff modal with tagged lines, and Esc closes it`() {
        val repo = newRepo()
        File(repo, "README.md").writeText("hello from the diff modal\n")
        val port = boot()

        val c = Controller(scope)
        runDesktopComposeUiTest(1800, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.connection.state == ConnState.CONNECTED }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Diff modal project") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Diff modal project" } }.projects.single { it.name == "Diff modal project" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.activeProject?.directory == repo.absolutePath }

            // UX-CHANGES-1: the tab is "Changes ^3", and it reads the shown worktree (here the project's own checkout).
            onNodeWithText("Changes ^3").performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.changesWorktreeStatus?.available == true }
            onNodeWithTag(DIFF_MODAL_TAG).assertDoesNotExist()

            onNodeWithText("README.md", substring = true).performClick()
            onNodeWithTag(DIFF_MODAL_TAG).assertExists()
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.projectGitPatch[project.id]?.path == "README.md" }
            waitForIdle()
            onNode(hasTestTag(DIFF_LINE_ADD_TAG) and hasText("+hello from the diff modal", substring = true)).assertExists()
            onNode(hasTestTag(DIFF_LINE_DEL_TAG) and hasText("-hello", substring = true)).assertExists()
            onNode(hasTestTag(DIFF_MODAL_COUNTS_TAG) and hasText("+1", substring = true) and hasText("−1", substring = true)).assertExists()
            onNodeWithText("modified · not staged").assertExists()

            // Evidence for the ticket: the open modal as the window draws it (agentHqUi/build/screenshots/diff-modal.png).
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/screenshots/diff-modal.png").also { it.parentFile.mkdirs() })

            onNodeWithTag(DIFF_MODAL_TAG).performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(DIFF_MODAL_TAG).assertDoesNotExist()
        }
    }
}
